package dev.atvremote.protocol.connection

import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.opack.Opack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A test double that intercepts the E_OPACK send, invokes [respond], and pushes the result back. */
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

class CompanionProtocolTest {
    @Test fun exchangeMatchesByXid() = runTest {
        val fake = FakeConnection { sentOpack ->
            val xid = (Opack.unpack(sentOpack).first as Map<*, *>)["_x"]
            Opack.pack(mapOf("_t" to 3, "_x" to xid, "_c" to mapOf("ok" to true)))
        }
        val proto = CompanionProtocol(fake)
        val resp = proto.exchange("_systemInfo", mapOf("name" to "Pixel"))
        assertEquals(true, (resp["_c"] as Map<*, *>)["ok"])
    }
}
