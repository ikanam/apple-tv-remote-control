package dev.atvremote.protocol.connection
import dev.atvremote.protocol.crypto.ChaCha
import dev.atvremote.protocol.frame.Frame
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
class CompanionConnectionTest {

    /**
     * Robustness regression: a single undecodable/undecryptable inbound frame
     * must NOT kill the read loop. Companion framing is length-prefixed with a
     * plaintext header, so the reader can resync past a bad frame and keep
     * delivering subsequent valid frames. (Before this, `readLoop`'s
     * `catch (_: Exception) {}` swallowed the decrypt failure and silently
     * killed the reader — which masked the entire Task-17 Bug C chain.)
     *
     * Real-clock `runBlocking` + JUnit `@Timeout` (not `runTest` virtual time)
     * because the read loop runs on real `Dispatchers.IO`.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun readLoopSkipsUndecryptableFrameAndContinues() = runBlocking {
        val key = ByteArray(32) { (it + 1).toByte() }
        val server = ServerSocket(0)
        // Server-side cipher: outKey == the connection's inKey, so counters stay
        // aligned (every frame, good or corrupt, spends one counter each side).
        val sc = ChaCha(key, key)
        val b0 = byteArrayOf(0xA0.toByte(), 0xA1.toByte())
        val b2 = byteArrayOf(0xC0.toByte(), 0xC1.toByte(), 0xC2.toByte())
        val f0 = Frame.encode(FrameType.E_OPACK, b0, sc)                      // counter 0
        val f1 = Frame.encode(FrameType.E_OPACK, byteArrayOf(1, 2, 3, 4), sc) // counter 1
        f1[f1.size - 1] = (f1[f1.size - 1] + 1).toByte()                      // corrupt tag → decrypt throws
        val f2 = Frame.encode(FrameType.E_OPACK, b2, sc)                      // counter 2

        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            val s = server.accept()
            gate.await() // wait until the connection enabled encryption
            s.getOutputStream().apply { write(f0); write(f1); write(f2); flush() }
        }
        val conn = CompanionConnection("127.0.0.1", server.localPort)
        conn.connect()
        conn.enableEncryption(key, key)
        gate.complete(Unit)

        // The corrupt f1 must be skipped; f0 and f2 must both be delivered.
        val got = withTimeoutOrNull(5_000) { conn.frames().take(2).toList() }
            ?: error("read loop died on a bad frame — fewer than 2 frames delivered")
        assertEquals(2, got.size)
        assertTrue(got[0].second.contentEquals(b0), "first valid frame must arrive")
        assertTrue(got[1].second.contentEquals(b2),
            "the valid frame AFTER the corrupt one must still arrive")
        conn.close(); job.cancel(); server.close()
    }

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
