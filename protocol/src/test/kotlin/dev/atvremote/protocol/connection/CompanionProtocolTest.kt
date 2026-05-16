package dev.atvremote.protocol.connection

import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.opack.Opack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * A test double that intercepts the E_OPACK send, invokes [respond], and pushes the result back.
 *
 * NOTE (m-4): This double emits the response with the same frame type as the request. For
 * E_OPACK exchanges that is correct (the exchangeMatchesByXid test below relies on it).
 * Task 11/12 auth tests must use a separate double that maps PS_Start→PS_Next /
 * PV_Start→PV_Next, because auth frames are keyed on the *response* frame type, not the
 * request type.
 */
class FakeConnection(
    private val respond: suspend (sentOpack: ByteArray) -> ByteArray
) : FrameTransport {

    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64
    )

    override fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames

    override suspend fun send(type: FrameType, payload: ByteArray) {
        val responsePayload = respond(payload)
        _frames.emit(Pair(type, responsePayload))
    }
}

/**
 * A [FrameTransport] that records every (type, payload) sent to it.
 * Unlike [FakeConnection] it does NOT auto-echo a response — suitable for
 * testing fire-and-forget sends such as [CompanionProtocol.sendEvent].
 */
class RecordingConnection : FrameTransport {
    val sent = mutableListOf<Pair<FrameType, ByteArray>>()

    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64
    )

    override fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames

    override suspend fun send(type: FrameType, payload: ByteArray) {
        sent.add(type to payload)
        // No auto-response — caller must push via pushFrame if needed.
    }

    /** Push an inbound frame (for tests that need to simulate a response). */
    suspend fun pushFrame(type: FrameType, payload: ByteArray) {
        _frames.emit(type to payload)
    }
}

class CompanionProtocolTest {
    @Test fun exchangeMatchesByXid() = runTest {
        val fake = FakeConnection { sentOpack ->
            val xid = (Opack.unpack(sentOpack).first as Map<*, *>)["_x"]
            Opack.pack(mapOf("_t" to 3, "_x" to xid, "_c" to mapOf("ok" to true)))
        }
        // bind collector to runTest scheduler so withTimeout virtual-time is coherent (no real/virtual race)
        val proto = CompanionProtocol(fake, coroutineContext)
        val resp = proto.exchange("_systemInfo", mapOf("name" to "Pixel"))
        assertEquals(true, (resp["_c"] as Map<*, *>)["ok"])
        proto.close()
    }

    /**
     * Verifies that concurrent exchange() calls each receive the response matched to their own
     * XID and that no two calls collide on the same correlation id.
     *
     * Design: FakeConnection echoes the request's _x back in both the response _x and in a
     * _c["echo"] field. Each async caller then checks that the _c["echo"] it received equals the
     * _x in that response — proving no cross-talk. We also collect all echoed XIDs and assert
     * they are all distinct (no two calls were assigned the same 16-bit id, which would have
     * caused one to clobber the other in the pending map and produce a hang or wrong result).
     *
     * Parallelism & determinism: the collector is bound to runTest's scheduler via coroutineContext,
     * so withTimeout inside exchange() uses virtual time. FakeConnection echoes requests
     * synchronously in send(), so responses are always in the replay buffer before exchange()
     * suspends; no sleeps or timing-sensitive assertions are needed. The AtomicInteger counter
     * and ConcurrentHashMap are still exercised for correctness of the correlation logic.
     */
    @Test fun concurrentExchangesDoNotCollideXids() = runTest {
        val fake = FakeConnection { sentOpack ->
            @Suppress("UNCHECKED_CAST")
            val req = Opack.unpack(sentOpack).first as Map<String, Any?>
            val xid = req["_x"]
            // Echo _x in both the correlation field and inside _c so callers can verify identity.
            Opack.pack(mapOf("_t" to 3, "_x" to xid, "_c" to mapOf("echo" to xid)))
        }
        val proto = CompanionProtocol(fake, coroutineContext)

        val results: List<Map<String, Any?>> = coroutineScope {
            (0 until 50).map { i ->
                async { proto.exchange("_cmd", mapOf("n" to i)) }
            }.awaitAll()
        }

        // Every call must have completed (awaitAll would throw if any timed out).
        assertEquals(50, results.size)

        // Collect the echoed XIDs from each response.
        val echoedXids = results.map { resp ->
            @Suppress("UNCHECKED_CAST")
            val c = resp["_c"] as Map<String, Any?>
            (c["echo"] as Number).toLong()
        }

        // All 50 XIDs must be distinct — any collision means two exchanges shared a correlation id.
        val uniqueXids = echoedXids.toSet()
        assertEquals(50, uniqueXids.size,
            "XID collision detected: only ${uniqueXids.size} unique XIDs among 50 exchanges")

        // The echoed XID in each response must match the _x of that response (sanity check on
        // the fake's echo logic and the protocol's routing).
        results.forEach { resp ->
            val responseXid = (resp["_x"] as Number).toLong()
            @Suppress("UNCHECKED_CAST")
            val echoXid = ((resp["_c"] as Map<String, Any?>)["echo"] as Number).toLong()
            assertEquals(responseXid, echoXid,
                "Response _x=$responseXid does not match echoed _c.echo=$echoXid — cross-talk detected")
        }

        proto.close()
    }

    /**
     * pyatv-wins (DIV-1): send_opack injects _x into EVERY outbound OPACK frame,
     * including fire-and-forget events (_t=1). sendEvent must add _x from the
     * shared xid counter — exactly like exchange() does.
     *
     * RED: before the fix, the sent frame has no _x key → assertNotNull fails.
     */
    @Test fun sendEventIncludesXid() = runTest {
        val conn = RecordingConnection()
        val proto = CompanionProtocol(conn, coroutineContext)

        proto.sendEvent("_interest", mapOf("_regEvents" to listOf("_iMC")))

        proto.close()

        assertEquals(1, conn.sent.size)
        val (_, payload) = conn.sent[0]
        @Suppress("UNCHECKED_CAST")
        val decoded = Opack.unpack(payload).first as Map<String, Any?>
        // Must have _t=1 (event semantics unchanged)
        assertEquals(1L, (decoded["_t"] as Number).toLong())
        // Must have _x (pyatv send_opack always injects _x)
        assertNotNull(decoded["_x"], "_x must be present in sendEvent frame (pyatv-wins)")
    }

    /**
     * pyatv-wins (DIV-1): sendEvent and exchange draw from ONE shared monotonic
     * counter. Two consecutive calls (one sendEvent, one exchange) must produce
     * distinct, increasing xids — proving they share the same sequence.
     *
     * RED: before the fix, sendEvent produces no _x so the xids cannot be compared.
     */
    @Test fun sendEventAndExchangeShareMonotonicXidCounter() = runTest {
        val conn = RecordingConnection()
        val proto = CompanionProtocol(conn, coroutineContext)

        // Fire the event first — no response expected.
        proto.sendEvent("_interest", mapOf("_regEvents" to listOf("_iMC")))

        // Now do an exchange; push the response manually so exchange() can complete.
        // We'll do this without actually awaiting — just capture what was sent.
        // To avoid a timeout we can push the response synchronously after send.
        // Use a separate FakeConnection for the exchange part so we don't need to
        // coordinate with RecordingConnection's pushFrame timing. Instead, capture
        // both frames from two successive sendEvent calls (both fire-and-forget) so
        // no response coordination is needed.
        proto.sendEvent("_hidT", mapOf("_hid" to 0))

        proto.close()

        assertEquals(2, conn.sent.size)
        @Suppress("UNCHECKED_CAST")
        val xid0 = ((Opack.unpack(conn.sent[0].second).first as Map<String, Any?>)["_x"] as Number).toLong()
        @Suppress("UNCHECKED_CAST")
        val xid1 = ((Opack.unpack(conn.sent[1].second).first as Map<String, Any?>)["_x"] as Number).toLong()

        assertNotNull(xid0, "first sendEvent must have _x")
        assertNotNull(xid1, "second sendEvent must have _x")
        assertNotEquals(xid0, xid1, "consecutive sendEvent calls must draw distinct xids from the shared counter")
        // Xids are monotonically increasing (mod 0xFFFF wrap is possible in theory
        // but not in two consecutive calls starting from a random < 65536).
        assertEquals(xid0 + 1, xid1, "xids must be consecutive (shared counter increments by 1)")
    }
}
