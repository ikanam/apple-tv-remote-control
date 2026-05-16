package dev.atvremote.protocol.connection

import dev.atvremote.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decorator over a live CompanionSession implementing spec §7 resilience policy:
 *  - connectionState is OWNED here (authority); mutated only via setState()/
 *    onReconnected() from the supervisor. It does NOT mirror the delegate
 *    (a standalone CompanionSessionImpl is always Connected — Task 19).
 *  - while Reconnecting/Disconnected:
 *      touch()/button()/click() -> dropped silently, no queue and no replay
 *      keyboard/app/power/media -> throw CompanionUnavailableException
 *  - onReconnected() only swaps the delegate + flips state to Connected;
 *    nothing is replayed.
 *
 * Owner-approved Plan-2 §7 change (2026-05-16): button()/click() are dropped
 * while not Connected — exact parity with touch() — with no bounded queue and
 * no reconnect flush. Remote commands are ephemeral, contextual and
 * non-idempotent (the same reason touch() is dropped); a reconnect is
 * structurally multi-second (backoff + PairVerify + SessionHandshake re-auth),
 * so any replay is always stale and a stale burst is surprising/destructive.
 * connectionState is exposed so callers re-issue when back. NOT configurable.
 *
 * The actual socket-drop detection + backoff + pair-verify + handshake + subscription
 * restore is driven by RemoteConnect.connect's supervisor, which calls
 * setState()/swapDelegate()/onReconnected() on this object (revision-log B/C).
 */
class ResilientSession(initial: CompanionSession) : CompanionSession {
    @Volatile private var delegate: CompanionSession = initial
    private val _state = MutableStateFlow(ConnectionState.Connected)
    override val connectionState: StateFlow<ConnectionState> = _state

    /**
     * Assigned by [dev.atvremote.protocol.RemoteConnect.connect] immediately after the
     * supervisor coroutine is launched. Invoked in [close] to cancel the supervisor scope
     * before tearing down the delegate, ensuring a deliberate close never triggers a
     * reconnect attempt. Kept minimal — no over-hardening.
     */
    @Volatile internal var cancelSupervisor: (() -> Unit)? = null

    fun setState(s: ConnectionState) { _state.value = s }

    /** Supervisor: install the freshly-reconnected session. */
    fun swapDelegate(next: CompanionSession) { delegate = next }

    /**
     * Called by the supervisor after a successful reconnect: optionally swaps the
     * delegate and flips state to Connected. Nothing is replayed — button()/click()
     * issued while not Connected were dropped (owner-approved Plan-2 §7 change,
     * 2026-05-16; see class KDoc).
     * [next] non-null on a real reconnect (supervisor swaps in the new session);
     * null in unit tests that exercise the state flip without a delegate swap.
     * (`suspend` is retained for call-site compatibility — the supervisor awaits it.)
     *
     * NOTE: `keyboardFocus` is a `get()` over the current delegate, so callers should
     * read `session.keyboardFocus` per-use rather than caching the StateFlow across a
     * reconnect (documented decorator limitation; acceptable for v1).
     */
    suspend fun onReconnected(next: CompanionSession? = null) {
        if (next != null) delegate = next
        _state.value = ConnectionState.Connected
    }

    private fun connected() = _state.value == ConnectionState.Connected

    private fun requireUp(call: String) {
        if (!connected()) throw CompanionUnavailableException(
            "$call unavailable: connection is ${_state.value}")
    }

    override suspend fun button(button: RemoteButton, down: Boolean) {
        if (connected()) delegate.button(button, down)   // else drop silently
    }

    override suspend fun click(action: InputAction) {
        if (connected()) delegate.click(action)          // else drop silently
    }

    override suspend fun touch(x: Int, y: Int, phase: TouchPhase) {
        if (connected()) delegate.touch(x, y, phase)     // else drop silently
    }

    override suspend fun textGet(): String { requireUp("textGet"); return delegate.textGet() }
    override suspend fun textSet(text: String) { requireUp("textSet"); delegate.textSet(text) }
    override suspend fun textClear() { requireUp("textClear"); delegate.textClear() }
    override suspend fun textAppend(text: String) { requireUp("textAppend"); delegate.textAppend(text) }
    override suspend fun listApps(): List<InstalledApp> { requireUp("listApps"); return delegate.listApps() }
    override suspend fun launchApp(bundleId: String) { requireUp("launchApp"); delegate.launchApp(bundleId) }
    override suspend fun power(on: Boolean) { requireUp("power"); delegate.power(on) }
    override suspend fun powerStatus(): PowerStatus { requireUp("powerStatus"); return delegate.powerStatus() }
    override suspend fun media(command: MediaCommand) { requireUp("media"); delegate.media(command) }

    override val keyboardFocus get() = delegate.keyboardFocus

    override suspend fun close() {
        // Cancel the supervisor first so a deliberate close() does not trigger a reconnect.
        cancelSupervisor?.invoke()
        delegate.close()
    }
}
