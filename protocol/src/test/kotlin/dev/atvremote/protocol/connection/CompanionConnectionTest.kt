package dev.atvremote.protocol.connection
import dev.atvremote.protocol.frame.Frame
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    /**
     * After close(), the read-loop coroutine must be cancelled and no new frames emitted.
     * The server holds the connection open without sending data so the read loop is
     * parked in inputStream.read(). close() cancels the scope + closes the socket.
     * Because no data is ever sent, the replay buffer is empty; frames().first() will
     * suspend indefinitely on a live connection but returns null under withTimeoutOrNull
     * once the loop is dead — virtual-time in runTest advances the 500 ms instantly.
     */
    @Test fun closeCancelsReadLoopJob() = runTest {
        val server = ServerSocket(0)
        // Accept and hold the connection open — never send data.
        val serverJob = launch(Dispatchers.IO) {
            val s = server.accept()
            // Block until this job is cancelled (keeps the peer socket alive).
            try { delay(Long.MAX_VALUE) } finally { s.close() }
        }
        val conn = CompanionConnection("127.0.0.1", server.localPort)
        conn.connect()

        // Give the read loop a moment to park in inputStream.read().
        yield()

        conn.close()

        // After close(), the internal scope is cancelled and the socket is closed.
        // The read loop exits (via CancellationException or SocketException) and will
        // never emit another frame. frames().first() suspends forever on a dead flow,
        // so withTimeoutOrNull must return null. In runTest, virtual-time advances the
        // 500 ms delay without wall-clock waiting — no sleep, fully deterministic.
        val result = withTimeoutOrNull(500) { conn.frames().first() }
        assertNull(result, "Expected no frame after close(), but got one — read loop was not cancelled")

        serverJob.cancel()
        server.close()
    }
}
