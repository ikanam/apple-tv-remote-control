package dev.atvremote.protocol.pairing

import dev.atvremote.protocol.PairingHandle
import dev.atvremote.protocol.PairingState
import dev.atvremote.protocol.connection.CompanionConnection
import dev.atvremote.protocol.connection.FrameTransport
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional seam exposing the controller's pair-setup key material.
 *
 * Production [dev.atvremote.protocol.connection.CompanionConnection] does NOT
 * implement this, so [PairingHandleImpl] generates a fresh random Ed25519 seed
 * and pairing id per pairing attempt. The scripted golden-trace double DOES
 * implement it, supplying the fixture's *fixed* seed / pairing id so the
 * golden-validated [PairSetup] reproduces exactly the controller frames the
 * synthetic accessory script expects (mirroring `PairSetupGoldenTest`).
 *
 * This keeps the locked single-argument constructor
 * `PairingHandleImpl(conn)` intact — the fixed values flow in *through the
 * connection*, not through extra constructor parameters the locked test does
 * not pass.
 */
interface PairingKeys {
    /** Controller Ed25519 long-term auth private seed (32B). */
    val seed: ByteArray
    /** Controller pairing identifier bytes. */
    val pairingId: ByteArray
}

/**
 * Drives a HAP pair-setup (M1..M6) over a live [FrameTransport] and exposes it
 * as the locked [PairingHandle] state machine.
 *
 * ## PIN-timing resolution
 * [PairSetup] takes the PIN in its *constructor* (`buildM1` calls
 * `srp.step1(pin)`), whereas [PairingHandle.submitPin] supplies the PIN
 * *later*. Rather than build a throwaway [PairSetup] with a placeholder PIN,
 * the entire M1..M6 exchange is **deferred to [submitPin]**: at construction
 * the handle only records the key material and publishes
 * [PairingState.AwaitingPin]. When the PIN arrives a single [PairSetup] is
 * created with the real PIN and the full exchange runs. This matches the
 * locked test ([PairingState.AwaitingPin] before `submitPin`,
 * [PairingState.Completed] after it returns) and needs no changes to
 * [PairSetup].
 *
 * ## Seed / pairing-id seam
 * If [conn] also implements [PairingKeys] (the scripted golden double), its
 * fixed seed / pairing id are used so [PairSetup] reproduces the fixture's
 * controller frames; otherwise a fresh random Ed25519 seed and a random UUID
 * pairing id are generated for real-device pairing.
 *
 * ## State machine
 * `AwaitingPin` → (`submitPin` success) `Completed(creds)`
 *               → (failure / timeout / `cancel`) `Failed(reason)`.
 * Terminal states are never overwritten; partial credentials are never exposed.
 *
 * ## Connection lifecycle
 * [cancel] is the public cleanup hook (there is no `close()`). It offloads the
 * actual [conn] close to [closerScope] (a separate [SupervisorJob]-backed IO
 * scope) so the non-suspend [cancel] can initiate a suspend [CompanionConnection.close]
 * without blocking. [closerScope] is never cancelled — it runs to completion.
 * [conn] is closed via [CompanionConnection.close] if it is a real connection,
 * or via [AutoCloseable.close] for any test double that implements it.
 *
 * > NOTE: only the scripted path is unit-tested here. The real-device pair
 * > flow (via [dev.atvremote.protocol.RemoteConnect.pair] building a real
 * > [dev.atvremote.protocol.connection.CompanionConnection]) is exercised
 * > end-to-end by **Task 17** (CLI smoke test on a real Apple TV).
 */
class PairingHandleImpl(
    private val conn: FrameTransport,
) : PairingHandle {

    private val seed: ByteArray
    private val pairingId: ByteArray

    init {
        if (conn is PairingKeys) {
            seed = conn.seed
            pairingId = conn.pairingId
        } else {
            seed = Curves.newEd25519().first
            pairingId = UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    /**
     * Dedicated scope for closing the underlying connection asynchronously from
     * the non-suspend [cancel] call. Uses a separate [SupervisorJob] so it is
     * never cancelled alongside any handle-internal scope; it runs to completion.
     */
    private val closerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<PairingState>(PairingState.AwaitingPin)
    override val state: StateFlow<PairingState> = _state.asStateFlow()

    /**
     * Atomic guard: CAS from false→true at the top of [submitPin] so concurrent
     * callers cannot both proceed (which would corrupt the protocol with duplicate
     * M1 frames). A second call short-circuits immediately without suspending.
     */
    private val started = AtomicBoolean(false)

    /**
     * Count of inbound `PS_Next` accessory frames already consumed (M2, M4, M6).
     *
     * [FrameTransport.frames] is a *replay-buffered* [kotlinx.coroutines.flow.SharedFlow]
     * (both the production [dev.atvremote.protocol.connection.CompanionConnection] and
     * the scripted double use `replay = 16`), so a naive `first { PS_Next }` would
     * keep re-delivering M2. Pair-setup replies arrive strictly in order, so we
     * positionally take the `(consumed + 1)`-th `PS_Next` frame each step — robust
     * for both the scripted and the real connection.
     */
    private var psNextConsumed = 0

    override suspend fun submitPin(pin: String) {
        // Atomic single-use guard: only the first caller proceeds.
        if (!started.compareAndSet(false, true)) return
        // Belt-and-suspenders: bail if already in a terminal state (e.g. cancel() raced).
        if (_state.value !is PairingState.AwaitingPin) return
        try {
            val ps = PairSetup(seed = seed, pairingId = pairingId, pin = pin)

            // M1 controller → ATV (PS_Start), M2 ATV → controller (PS_Next)
            conn.send(FrameType.PS_Start, ps.buildM1())
            ps.consumeM2(awaitNext())

            // M3 controller → ATV (PS_Next), M4 ATV → controller (PS_Next)
            conn.send(FrameType.PS_Next, ps.buildM3())
            ps.consumeM4(awaitNext())

            // M5 controller → ATV (PS_Next), M6 ATV → controller (PS_Next)
            conn.send(FrameType.PS_Next, ps.buildM5())
            val creds = ps.consumeM6(awaitNext())

            // Only publish a terminal state if we are still pending (not cancelled).
            if (_state.value is PairingState.AwaitingPin) {
                _state.value = PairingState.Completed(creds)
            }
        } catch (c: CancellationException) {
            // Real coroutine cancellation (not a timeout) — re-throw so the
            // coroutine machinery can propagate it; do NOT set Failed here
            // (cancel() already set it, or the caller's scope was cancelled).
            throw c
        } catch (t: Throwable) {
            // IOException (wraps TimeoutCancellationException from awaitNext),
            // protocol errors, and any other non-CancellationException land here.
            if (_state.value is PairingState.AwaitingPin) {
                _state.value = PairingState.Failed(t.message ?: t::class.simpleName ?: "pair-setup failed")
            }
        }
    }

    /**
     * Awaits the next (positionally) inbound accessory `PS_Next` frame, bounded
     * by a 5 s real-clock timeout so a misbehaving / silent peer cannot hang the
     * pairing flow (parity with `RemoteConnect.connect`'s pair-verify await).
     *
     * Collects `(psNextConsumed + 1)` `PS_Next` frames from the replay-buffered
     * flow and returns the last one; increments the consumed counter so the next
     * call skips frames already handled.
     *
     * A [TimeoutCancellationException] (which is a [CancellationException] subclass)
     * is caught here and re-thrown as a plain [IOException] so that [submitPin]'s
     * `catch (t: Throwable)` — which re-throws real [CancellationException] but
     * handles any other [Throwable] — will set state to [PairingState.Failed] with
     * a structural message. This prevents a silent peer from leaving the handle
     * stuck in [PairingState.AwaitingPin] forever.
     */
    private suspend fun awaitNext(): ByteArray {
        val want = psNextConsumed + 1
        val frame = try {
            withTimeout(5_000) {
                conn.frames()
                    .filter { (ft, _) -> ft == FrameType.PS_Next }
                    .take(want)
                    .toList()
                    .last()
            }
        } catch (e: TimeoutCancellationException) {
            // Convert the CancellationException subtype to a plain IOException so
            // submitPin's catch(t: Throwable) will set Failed rather than re-throwing.
            // The message deliberately uses only structural information (no key bytes).
            throw IOException("pair-setup: peer silent (timeout) at step $want", e)
        }
        psNextConsumed = want
        return frame.second
    }

    /**
     * Cancels the pairing attempt and closes the underlying connection.
     *
     * - If still in [PairingState.AwaitingPin], transitions to
     *   [PairingState.Failed]`("cancelled")`. Terminal states ([PairingState.Completed],
     *   [PairingState.Failed]) are never overwritten.
     * - Offloads the actual [conn] close to [closerScope] (non-suspend, fire-and-forget).
     *   If [conn] is a [CompanionConnection] its socket + read-loop are closed; if it
     *   is any other [AutoCloseable] (e.g. a test double) its `close()` is invoked.
     *   Errors during close are swallowed via [runCatching].
     * - Safe to call multiple times and from any thread (idempotent state CAS,
     *   close guarded by [runCatching]).
     */
    override fun cancel() {
        // Only flip to Failed if still AwaitingPin; never overwrite a terminal state.
        _state.compareAndSet(PairingState.AwaitingPin, PairingState.Failed("cancelled"))

        // Offload suspend close to a dedicated scope that won't be cancelled itself.
        closerScope.launch {
            when (val c = conn) {
                is CompanionConnection -> runCatching { c.close() }
                is AutoCloseable -> runCatching { c.close() }
                else -> {}
            }
        }
    }
}
