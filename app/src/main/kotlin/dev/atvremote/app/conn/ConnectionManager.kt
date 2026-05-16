package dev.atvremote.app.conn

import dev.atvremote.app.data.CredentialStore
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.AppleTvRemote
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Connector indirection so tests can inject a fake without :protocol's connect(). */
fun interface SessionConnector {
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession
}

/**
 * Owns the single CompanionSession (spec §7). Maps ConnectionState -> UiConnectionState,
 * does indexed-backoff reconnect, and clears creds + asks for re-pair on credential-invalid.
 *
 * Observer/reconnect scope + collector dispatch (deliberate — analogous to the
 * T16 `backgroundScope` reconciliation; reconciliation #1 for the plan's racy
 * draft):
 *
 *  1. The long-running `connectionState` collector and the backoff
 *     `scheduleReconnect` loop run in a scope **derived from the first
 *     `connect()` call's coroutine context**, not a construction-time
 *     `CoroutineScope(SupervisorJob())` pinned to `Dispatchers.Default`. Under
 *     `runTest`, `coroutineContext` carries the `TestDispatcher` + virtual
 *     scheduler, so `scheduleReconnect`'s `delay(backoff)` runs on virtual time
 *     (auto-advanced by `runTest`) instead of wall-clock; in production
 *     `connect()` is invoked from the `ConnectionService`'s real dispatcher, so
 *     the backoff is real wall-clock delay exactly as a Default scope would be.
 *     The derived scope uses its own `SupervisorJob` (parented to the connect()
 *     job) so one failed observer/reconnect child never tears the others down,
 *     and `disconnect()` cancels all children deterministically.
 *
 *  2. The `connectionState` collector is launched **`UNDISPATCHED` on
 *     `Dispatchers.Unconfined`**. The plan draft launched it on the scope's
 *     (Default) dispatcher: `connect()` then `connFlow.value = Reconnecting`
 *     then `uiState.first()` is a data race — `StateFlow.first()` returns the
 *     CURRENT value without suspending, and a background-dispatched collector
 *     may not have propagated `Reconnecting` yet (flaky/failing the verbatim
 *     contract test, AND a real lost-update window in production). `UNDISPATCHED`
 *     makes the collector observe the StateFlow's current value before
 *     `launch` returns; `Unconfined` makes every subsequent emission resume the
 *     collector **synchronously in the thread that set `connectionState`**, so
 *     `_uiState` is updated in-line with the protocol state change — no
 *     scheduler hop, deterministic in tests and lossless in production. This is
 *     a pure StateFlow→StateFlow mirror (no blocking work in the collector), so
 *     running it Unconfined is sound.
 *
 * Layering contract (who owns reconnect): the session's `connectionState` is
 * owned by `:protocol`'s `ResilientSession`, which already does transient
 * backoff reconnect itself and emits `Reconnecting` during its own retries; it
 * emits `Disconnected` only as a TERMINAL credential-rejection signal (see
 * `RemoteImpl.kt:294-300`: it sets `Disconnected` then `scope.cancel(); return`
 * — it permanently gives up). `ConnectionManager` therefore mirrors
 * `Connected`/`Reconnecting` (no second loop) and treats `Disconnected` as
 * terminal (clear creds → `CredentialInvalid`). Its own `scheduleReconnect`
 * exists SOLELY for the pre-`ResilientSession` initial-`connect()` failure path
 * (the `catch (e: Exception)` in `connect()`, before any `ResilientSession`
 * exists, e.g. "no route").
 *
 * Threading contract: `ConnectionManager` confines state mutation via an
 * internal `Mutex`; `connect()`/`disconnect()` are safe to call from different
 * coroutines (T7's `ConnectionService` drives bind/unbind concurrently).
 *
 * T7 long-lived-scope footgun: the first `connect()` must be invoked from a
 * scope that lives as long as the desired connection (e.g. the
 * `ConnectionService` lifecycle scope) — the observer/reconnect work scope is
 * parented to that call's `coroutineContext` Job and dies with it. Do NOT call
 * the first `connect()` from a short-lived/`runBlocking`/one-shot coroutine.
 */
class ConnectionManager(
    private val connector: SessionConnector =
        SessionConnector { d, c -> AppleTvRemote.connect(d, c) },
    private val credentialStore: CredentialStore? = null,
    scope: CoroutineScope? = null,
    private val backoffMs: List<Long> = listOf(500, 1000, 2000, 4000, 8000, 15000),
) {
    private val _uiState = MutableStateFlow<UiConnectionState>(UiConnectionState.Idle)
    val uiState: StateFlow<UiConnectionState> = _uiState.asStateFlow()

    @Volatile private var session: CompanionSession? = null
    private var observeJob: Job? = null
    private var currentCreds: HapCredentials? = null

    /**
     * Confines mutation of the shared state (`session`, `observeJob`,
     * `currentCreds`, `workScope`) so `connect()`/`disconnect()`/reconnect-success
     * cannot interleave-corrupt it when driven concurrently (T7). The backoff
     * `delay(...)` is deliberately held OUTSIDE this lock.
     */
    private val mutex = Mutex()

    /**
     * Long-lived scope for the connectionState collector + backoff loop. When an
     * explicit [scope] is injected it is used verbatim; otherwise it is created
     * lazily from the first `connect()`'s coroutine context (see class KDoc) so
     * the verbatim test stays deterministic and production stays on the caller's
     * (real) dispatcher.
     */
    private var workScope: CoroutineScope? = scope

    fun currentSession(): CompanionSession? = session
    fun requireSession(): CompanionSession =
        session ?: error("No active CompanionSession")

    private suspend fun ensureScope(): CoroutineScope =
        workScope ?: CoroutineScope(coroutineContext + SupervisorJob()).also { workScope = it }

    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials) {
        val scope = mutex.withLock {
            val sc = ensureScope()
            currentCreds = credentials
            _uiState.value = UiConnectionState.Connecting(device)
            sc
        }
        try {
            val s = connector.connect(device, credentials)
            mutex.withLock {
                session = s
                _uiState.value = UiConnectionState.Connected(device)
                observeSessionState(scope, device, s)
            }
        } catch (e: CredentialInvalidException) {
            credentialStore?.clear(device.id)
            _uiState.value = UiConnectionState.CredentialInvalid(device)
        } catch (e: Exception) {
            _uiState.value =
                UiConnectionState.Failed(device, e.message ?: e::class.simpleName ?: "error")
            scheduleReconnect(scope, device, credentials)
        }
    }

    private fun observeSessionState(scope: CoroutineScope, device: AppleTvDevice, s: CompanionSession) {
        observeJob?.cancel()
        observeJob = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            s.connectionState.collect { cs ->
                _uiState.value = when (cs) {
                    ConnectionState.Connected -> UiConnectionState.Connected(device)
                    ConnectionState.Reconnecting -> UiConnectionState.Reconnecting(device)
                    ConnectionState.Disconnected -> {
                        // ResilientSession (the :protocol connectionState authority) emits Disconnected
                        // ONLY on terminal credential rejection — RemoteImpl.kt:294-300 sets
                        // Disconnected then scope.cancel(); return (it permanently gives up; all
                        // transient failures are surfaced by ResilientSession itself as Reconnecting,
                        // which it already retries). So Disconnected here means: stored creds rejected.
                        // Do NOT start a second backoff loop (ResilientSession already owns transient
                        // reconnect — double-supervising is wrong and would end in a bogus Failed).
                        credentialStore?.clear(device.id)
                        UiConnectionState.CredentialInvalid(device)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(scope: CoroutineScope, device: AppleTvDevice, creds: HapCredentials?) {
        creds ?: return
        scope.launch {
            for (wait in backoffMs) {
                delay(wait) // backoff held OUTSIDE the state mutex
                try {
                    val s = connector.connect(device, creds)
                    mutex.withLock {
                        session = s
                        _uiState.value = UiConnectionState.Connected(device)
                        observeSessionState(scope, device, s)
                    }
                    return@launch
                } catch (e: CredentialInvalidException) {
                    credentialStore?.clear(device.id)
                    _uiState.value = UiConnectionState.CredentialInvalid(device)
                    return@launch
                } catch (_: Exception) {
                    _uiState.value = UiConnectionState.Reconnecting(device)
                }
            }
            _uiState.value =
                UiConnectionState.Failed(device, "Reconnect attempts exhausted")
        }
    }

    suspend fun disconnect() = mutex.withLock {
        observeJob?.cancel(); observeJob = null
        // Cancel any in-flight backoff reconnect child too (children of workScope's
        // SupervisorJob) so disconnect() ends Idle deterministically and no
        // reconnect races the Idle transition.
        workScope?.coroutineContext?.get(Job)?.cancelChildren()
        session?.close()
        session = null
        _uiState.value = UiConnectionState.Idle
    }
}
