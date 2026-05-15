package dev.atvremote.protocol.pairing

import dev.atvremote.protocol.PairingState
import dev.atvremote.protocol.connection.FrameTransport
import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.goldentrace.GoldenTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Connection double scripted from the SYNTHETIC pair-setup golden trace's
 * **accessory** frames.
 *
 * It implements [FrameTransport] (the same abstraction [CompanionConnection]
 * implements, so [PairingHandleImpl] depends on it rather than a concrete
 * socket) and additionally [PairingKeys], which carries the fixture's *fixed*
 * controller seed / pairing id so the real golden-validated [PairSetup]
 * reproduces exactly the controller frames the synthetic accessory expects —
 * mirroring `PairSetupGoldenTest`. Production's [CompanionConnection] does NOT
 * implement [PairingKeys], so the real path uses a fresh random seed.
 *
 * Replay is deterministic and synchronous: when the controller `send`s a
 * pair-setup frame, the matching accessory fixture frame is emitted into a
 * replay-buffered [MutableSharedFlow] immediately, so the locked `runTest`
 * completes without any real delay / virtual-time advancement.
 *
 *  - controller M1 (`PS_Start`) → emit accessory M2 (`inFrame(1)`) as `PS_Next`
 *  - controller M3 (`PS_Next`)  → emit accessory M4 (`inFrame(3)`) as `PS_Next`
 *  - controller M5 (`PS_Next`)  → emit accessory M6 (`inFrame(5)`) as `PS_Next`
 *
 * Also implements [AutoCloseable] so that [PairingHandleImpl.cancel]'s
 * connection-close path (which dispatches to any [AutoCloseable] test double)
 * is observable in tests without touching the locked [FrameTransport] API.
 */
class ScriptedConnection private constructor(
    private val gt: GoldenTrace,
) : FrameTransport, PairingKeys, AutoCloseable {

    override val seed: ByteArray get() = gt.fixedSeed
    override val pairingId: ByteArray get() = gt.fixedPairingId

    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64,
    )

    // Index into the accessory reply script (M2, M4, M6).
    private var replyStep = 0
    private val accessoryReplies = listOf(gt.inFrame(1), gt.inFrame(3), gt.inFrame(5))

    /** Number of frames the controller has sent (M1, M3, M5 = 3 for a full exchange). */
    var sendCount = 0
        private set

    /** Set to true when [close] is invoked; observable by tests asserting I1 cleanup. */
    @Volatile
    var closed = false
        private set

    override fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames.asSharedFlow()

    override suspend fun send(type: FrameType, payload: ByteArray) {
        sendCount++
        // Every controller pair-setup frame triggers exactly one accessory reply.
        if (replyStep < accessoryReplies.size) {
            _frames.emit(FrameType.PS_Next to accessoryReplies[replyStep])
            replyStep++
        }
    }

    override fun close() {
        closed = true
    }

    companion object {
        fun fromGoldenTrace(name: String): ScriptedConnection =
            ScriptedConnection(GoldenTrace.load(name))
    }
}

/**
 * A connection double that never emits any inbound frames, simulating a silent
 * (unresponsive) peer. Used to verify Fix C1: the 5 s [withTimeout] in
 * [PairingHandleImpl.awaitNext] must convert a [TimeoutCancellationException]
 * into an [IOException] so [submitPin] sets [PairingState.Failed] rather than
 * leaving the handle stuck in [PairingState.AwaitingPin].
 *
 * Implements [PairingKeys] with the golden-trace's fixed seed / pairing id so
 * [PairSetup] can be constructed (SRP is deterministic with the fixed inputs),
 * and [AutoCloseable] for the cancel-close seam.
 */
class SilentScriptedConnection(
    private val gt: GoldenTrace,
) : FrameTransport, PairingKeys, AutoCloseable {

    override val seed: ByteArray get() = gt.fixedSeed
    override val pairingId: ByteArray get() = gt.fixedPairingId

    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64,
    )

    @Volatile
    var closed = false
        private set

    override fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames.asSharedFlow()

    // Never emits any reply frames — simulates a silent peer.
    override suspend fun send(type: FrameType, payload: ByteArray) = Unit

    override fun close() {
        closed = true
    }
}

class PairingHandleTest {
    /**
     * Bug B regression (real-device, 2026-05-16): the Apple TV displays its
     * pair-setup PIN only *after* it receives M1 (`PS_Start`) and replies M2.
     * The old [PairingHandleImpl] deferred the entire M1..M6 exchange into
     * [PairingHandleImpl.submitPin], so the CLI prompted "Enter PIN shown on
     * TV" before M1 was ever sent — the TV showed nothing and real pairing
     * could not proceed.
     *
     * Corrected behaviour: M1 is sent (and raw M2 received) at construction,
     * *before* [PairingHandleImpl.submitPin] — so the TV is already displaying
     * its PIN while the caller is still being prompted. The handle stays
     * [PairingState.AwaitingPin] (the only non-terminal public state) until the
     * user-entered PIN drives M3..M6.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun m1IsSentAtConstructionBeforeSubmitPin() = runTest {
        val conn = ScriptedConnection.fromGoldenTrace("pair-setup.json")
        // Directly-scheduled test scope (NOT backgroundScope): advanceUntilIdle()
        // must deterministically run the eager prePinJob even though nothing is
        // awaiting it yet — that is exactly the property under test.
        val handle = PairingHandleImpl(conn, CoroutineScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()

        assertEquals(1, conn.sendCount,
            "M1 (PS_Start) must be sent at construction, before submitPin — " +
                "the Apple TV only shows its PIN after receiving M1")
        assertTrue(handle.state.value is PairingState.AwaitingPin,
            "still AwaitingPin: M1 sent & M2 received, now waiting for the user-entered PIN")

        handle.submitPin("1234")
        assertIs<PairingState.Completed>(handle.state.value)
        assertEquals(3, conn.sendCount,
            "exactly M1 + M3 + M5 sent across the full exchange")
    }

    @Test fun awaitingPinThenCompleted() = runTest {
        val handle = PairingHandleImpl(
            ScriptedConnection.fromGoldenTrace("pair-setup.json"), backgroundScope,
        )
        assertTrue(handle.state.value is PairingState.AwaitingPin)
        handle.submitPin("1234")
        assertTrue(handle.state.value is PairingState.Completed)
    }

    /**
     * Proves Fix C1: a silent peer (no accessory reply) causes [withTimeout] to fire
     * a [TimeoutCancellationException], which [awaitNext] converts to an [IOException],
     * which sets [PairingState.Failed] rather than leaving the handle stuck.
     *
     * Post Bug-B fix the timeout now occurs in the **eager M1/M2 `prePinJob`**
     * (it sends M1 then awaits M2 from the silent peer); `submitPin` joins that
     * job and observes the already-[PairingState.Failed] terminal state. Under
     * [runTest]'s virtual-time scheduler (the handle's `prePinJob` runs on
     * [backgroundScope], same test scheduler) [withTimeout] expires instantly —
     * no real 5-second wait, deterministic and non-flaky.
     *
     * Without Fix C1 the [TimeoutCancellationException] would escape the
     * `catch (c: CancellationException) { throw c }` branch without setting
     * [PairingState.Failed], leaving the handle stuck in [PairingState.AwaitingPin].
     */
    @Test fun timeoutLeavesFailedNotStuck() = runTest {
        val gt = GoldenTrace.load("pair-setup.json")
        val conn = SilentScriptedConnection(gt)
        val handle = PairingHandleImpl(conn, backgroundScope)
        assertTrue(handle.state.value is PairingState.AwaitingPin)

        // prePinJob sends M1 then blocks at awaitNext() waiting for M2 from the
        // silent peer; withTimeout(5_000) fires immediately in runTest's virtual
        // clock → IOException → Failed. submitPin joins prePinJob and returns.
        handle.submitPin("1234")

        val state = handle.state.value
        assertFalse(state is PairingState.AwaitingPin,
            "handle must NOT remain AwaitingPin after a peer-silent timeout")
        assertIs<PairingState.Failed>(state,
            "handle must transition to Failed after peer-silent timeout, got: $state")
    }

    /**
     * Proves Fix I2: the [AtomicBoolean] CAS guard in [submitPin] ensures that a
     * second call to [submitPin] after a successful first call is a no-op. The
     * [ScriptedConnection] has exactly 3 accessory replies (M2, M4, M6); if a second
     * [submitPin] bypassed the guard and attempted M1 again, there would be no M2 reply
     * available, causing either a hang or a spurious [PairingState.Failed] — neither of
     * which should happen.
     *
     * The test also confirms the terminal-state protection: calling [submitPin] after
     * [PairingState.Completed] must leave the state [PairingState.Completed], not raise
     * an exception or flip to [PairingState.Failed].
     *
     * Without Fix I2 (non-atomic `var started`), two concurrent callers could both pass
     * the `if (started)` check before either sets `started = true`, corrupting the
     * protocol with duplicate M1 frames. Even in the non-concurrent sequential case the
     * second call would re-enter and exhaust the scripted reply buffer.
     */
    @Test fun secondSubmitPinIsNoOp() = runTest {
        val conn = ScriptedConnection.fromGoldenTrace("pair-setup.json")
        val handle = PairingHandleImpl(conn, backgroundScope)

        handle.submitPin("1234")
        assertIs<PairingState.Completed>(handle.state.value,
            "first submitPin must reach Completed")
        assertEquals(3, conn.sendCount,
            "exactly 3 controller frames (M1, M3, M5) must be sent in one full exchange")

        // Second call must be a no-op: CAS from false→true fails (already true),
        // so the body never runs.
        handle.submitPin("1234")
        assertIs<PairingState.Completed>(handle.state.value,
            "state must remain Completed after second submitPin")
        assertEquals(3, conn.sendCount,
            "sendCount must not increase — second submitPin must not send any frames")
    }

    /**
     * Proves Fix I1: [PairingHandleImpl.cancel] must close the underlying connection
     * (socket / read-loop leak prevention). The [ScriptedConnection] implements
     * [AutoCloseable] and records whether [close] was invoked, so the test double's
     * close hook is observable without modifying [CompanionConnection] or [FrameTransport].
     *
     * Also verifies:
     * - State transitions from [PairingState.AwaitingPin] to [PairingState.Failed]
     *   with reason "cancelled" on the first [cancel] call.
     * - A second [cancel] call is idempotent: state stays [PairingState.Failed]
     *   (not changed, no exception thrown).
     * - Calling [cancel] on an already-[PairingState.Completed] handle does NOT
     *   overwrite the terminal [PairingState.Completed] state — terminal-state
     *   protection is intact.
     *
     * The [closerScope] launch in [cancel] is asynchronous. [advanceUntilIdle] drives
     * the [runTest] virtual-time scheduler until all pending coroutines complete,
     * making the close-flag assertion deterministic (no real sleeps needed).
     *
     * Without Fix I1, [cancel] only calls `scope.cancel()` on a dead scope and the
     * real [CompanionConnection] (or test-double) close is never invoked — leaking
     * the socket and the read-loop coroutine.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelBeforeSubmitClosesAndFails() = runTest {
        val conn = ScriptedConnection.fromGoldenTrace("pair-setup.json")
        val handle = PairingHandleImpl(conn, backgroundScope)
        assertTrue(handle.state.value is PairingState.AwaitingPin)

        // cancel() before any submitPin: must set Failed("cancelled"), cancel the
        // eager prePinJob, and close conn.
        handle.cancel()
        val stateAfterCancel = handle.state.value
        assertIs<PairingState.Failed>(stateAfterCancel,
            "cancel() before submitPin must set Failed, got: $stateAfterCancel")
        assertEquals("cancelled", stateAfterCancel.reason,
            "Failed reason must be 'cancelled'")

        // Advance virtual clock so the closerScope.launch { conn.close() } runs.
        advanceUntilIdle()
        assertTrue(conn.closed,
            "cancel() must invoke close() on the connection to prevent socket/read-loop leak")

        // Second cancel() is idempotent: state stays Failed, no exception thrown.
        handle.cancel()
        assertIs<PairingState.Failed>(handle.state.value,
            "second cancel() must not change state")

        // Calling cancel() on a Completed handle must NOT overwrite Completed.
        val conn2 = ScriptedConnection.fromGoldenTrace("pair-setup.json")
        val handle2 = PairingHandleImpl(conn2, backgroundScope)
        handle2.submitPin("1234")
        assertIs<PairingState.Completed>(handle2.state.value,
            "handle2 must be Completed after submitPin")
        handle2.cancel()
        assertIs<PairingState.Completed>(handle2.state.value,
            "cancel() on a Completed handle must NOT flip state to Failed")
    }
}
