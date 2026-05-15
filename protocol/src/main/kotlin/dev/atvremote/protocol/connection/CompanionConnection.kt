package dev.atvremote.protocol.connection

import dev.atvremote.protocol.crypto.ChaCha
import dev.atvremote.protocol.frame.Frame
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.net.Socket

class CompanionConnection(private val host: String, private val port: Int) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // replay = 16 so frames emitted before the first collector subscribes are not lost
    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64
    )

    @Volatile
    private var cipher: ChaCha? = null

    private lateinit var socket: Socket
    private val sendMutex = Mutex()

    /** Opens a TCP connection to the Apple TV and starts the frame read loop. */
    suspend fun connect() {
        socket = withContext(Dispatchers.IO) { Socket(host, port) }
        scope.launch { readLoop() }
    }

    /** Returns the SharedFlow of decoded (FrameType, payload) pairs. */
    fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames

    /** Encodes and sends a frame; safe to call from any coroutine. */
    suspend fun send(type: FrameType, payload: ByteArray) {
        sendMutex.withLock {
            val encoded = Frame.encode(type, payload, cipher)
            withContext(Dispatchers.IO) {
                socket.getOutputStream().write(encoded)
                socket.getOutputStream().flush()
            }
        }
    }

    /**
     * Enables ChaCha20-Poly1305 encryption from this point onward.
     * Subsequent send() calls encrypt with outKey and the read loop decrypts with inKey.
     */
    fun enableEncryption(outKey: ByteArray, inKey: ByteArray) {
        cipher = ChaCha(outKey, inKey)
    }

    /** Cancels the read loop and closes the socket. */
    suspend fun close() {
        scope.cancel()
        withContext(Dispatchers.IO) {
            runCatching { socket.close() }
        }
    }

    // ---- private ----

    private suspend fun readLoop() {
        val accumulator = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        try {
            val inputStream = socket.getInputStream()
            while (true) {
                val n = withContext(Dispatchers.IO) { inputStream.read(tmp) }
                if (n == -1) break          // EOF — peer closed
                accumulator.write(tmp, 0, n)

                // Drain all complete frames from the accumulated buffer
                var buf = accumulator.toByteArray()
                var consumed = true
                while (consumed) {
                    val result = Frame.decode(buf, cipher)
                    if (result != null) {
                        val (type, body, bytesConsumed) = result
                        _frames.emit(Pair(type, body))
                        buf = buf.copyOfRange(bytesConsumed, buf.size)
                    } else {
                        consumed = false
                    }
                }
                // Replace accumulator with the leftover bytes
                accumulator.reset()
                accumulator.write(buf)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Socket closed or cancelled — exit gracefully
        }
    }
}
