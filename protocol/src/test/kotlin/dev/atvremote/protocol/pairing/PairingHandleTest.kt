package dev.atvremote.protocol.pairing

import dev.atvremote.protocol.PairingState
import dev.atvremote.protocol.connection.FrameTransport
import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.goldentrace.GoldenTrace
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
 */
class ScriptedConnection private constructor(
    private val gt: GoldenTrace,
) : FrameTransport, PairingKeys {

    override val seed: ByteArray get() = gt.fixedSeed
    override val pairingId: ByteArray get() = gt.fixedPairingId

    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64,
    )

    // Index into the accessory reply script (M2, M4, M6).
    private var replyStep = 0
    private val accessoryReplies = listOf(gt.inFrame(1), gt.inFrame(3), gt.inFrame(5))

    override fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames.asSharedFlow()

    override suspend fun send(type: FrameType, payload: ByteArray) {
        // Every controller pair-setup frame triggers exactly one accessory reply.
        if (replyStep < accessoryReplies.size) {
            _frames.emit(FrameType.PS_Next to accessoryReplies[replyStep])
            replyStep++
        }
    }

    companion object {
        fun fromGoldenTrace(name: String): ScriptedConnection =
            ScriptedConnection(GoldenTrace.load(name))
    }
}

class PairingHandleTest {
    @Test fun awaitingPinThenCompleted() = runTest {
        val handle = PairingHandleImpl(ScriptedConnection.fromGoldenTrace("pair-setup.json"))
        assertTrue(handle.state.value is PairingState.AwaitingPin)
        handle.submitPin("1234")
        assertTrue(handle.state.value is PairingState.Completed)
    }
}
