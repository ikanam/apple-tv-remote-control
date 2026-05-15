package dev.atvremote.protocol.connection
import dev.atvremote.protocol.frame.Frame
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
class CompanionConnectionTest {
    @Test fun receivesFramedPayload() = runTest {
        val server = ServerSocket(0)
        val job = launch(Dispatchers.IO) {
            val s = server.accept(); s.getOutputStream().write(Frame.encode(FrameType.U_OPACK, byteArrayOf(9,9), null)); s.getOutputStream().flush()
        }
        val conn = CompanionConnection("127.0.0.1", server.localPort)
        conn.connect()
        val (ft, body) = conn.frames().first { true }   // first frame
        assertEquals(FrameType.U_OPACK, ft); assertEquals(2, body.size)
        conn.close(); job.cancel(); server.close()
    }
}
