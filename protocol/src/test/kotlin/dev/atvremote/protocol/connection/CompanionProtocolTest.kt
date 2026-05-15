package dev.atvremote.protocol.connection

import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.opack.Opack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals

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

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CompanionProtocolTest {
    @Order(1)
    @Test fun exchangeMatchesByXid() = runTest {
        val fake = FakeConnection { sentOpack ->
            val xid = (Opack.unpack(sentOpack).first as Map<*, *>)["_x"]
            Opack.pack(mapOf("_t" to 3, "_x" to xid, "_c" to mapOf("ok" to true)))
        }
        val proto = CompanionProtocol(fake)
        val resp = proto.exchange("_systemInfo", mapOf("name" to "Pixel"))
        assertEquals(true, (resp["_c"] as Map<*, *>)["ok"])
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
     * Parallelism & determinism: the body runs under withContext(Dispatchers.Default) so that
     * withTimeout inside exchange() uses the real clock (not virtual time). This also causes the
     * 50 async coroutines to run on real thread-pool threads, genuinely exercising AtomicInteger's
     * memory-visibility guarantee and ConcurrentHashMap's thread-safety. The test is still
     * deterministic: FakeConnection echoes requests synchronously in send(), so responses are
     * always in the replay buffer before exchange() suspends; no sleeps or timing-sensitive
     * assertions are needed. Running under Dispatchers.Default also ensures the test fully drains
     * all thread-pool work before returning, preventing cross-test interference with
     * exchangeMatchesByXid() (which relies on Dispatchers.Default threads being idle).
     */
    @Order(2)
    @Test fun concurrentExchangesDoNotCollideXids() = runTest {
        val fake = FakeConnection { sentOpack ->
            @Suppress("UNCHECKED_CAST")
            val req = Opack.unpack(sentOpack).first as Map<String, Any?>
            val xid = req["_x"]
            // Echo _x in both the correlation field and inside _c so callers can verify identity.
            Opack.pack(mapOf("_t" to 3, "_x" to xid, "_c" to mapOf("echo" to xid)))
        }
        val proto = CompanionProtocol(fake)

        val results: List<Map<String, Any?>> = withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until 50).map { i ->
                    async { proto.exchange("_cmd", mapOf("n" to i)) }
                }.awaitAll()
            }
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
        // Drain Dispatchers.Default: submit a no-op task and await it to ensure all prior
        // Dispatchers.Default work (including the cancelled collectFrames coroutine) has fully
        // completed before this runTest block returns. This prevents cross-test interference with
        // exchangeMatchesByXid(), which requires Dispatchers.Default threads to be idle so its
        // collectFrames coroutine can be scheduled promptly under runTest's virtual time clock.
        withContext(Dispatchers.Default) {}
    }
}
