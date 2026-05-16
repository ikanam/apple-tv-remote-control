package dev.atvremote.protocol.connection

import dev.atvremote.protocol.crypto.ChaCha
import dev.atvremote.protocol.frame.Frame
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.net.Socket

class CompanionConnection(private val host: String, private val port: Int) : FrameTransport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // replay = 16 so frames emitted before the first collector subscribes are not lost
    private val _frames = MutableSharedFlow<Pair<FrameType, ByteArray>>(
        replay = 16,
        extraBufferCapacity = 64
    )

    // replay = 1: the closed signal is sticky — any late subscriber sees it immediately.
    private val _closed = MutableSharedFlow<Unit>(replay = 1)

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
    override fun frames(): SharedFlow<Pair<FrameType, ByteArray>> = _frames

    /**
     * Returns a [SharedFlow] that emits [Unit] exactly once when the read loop terminates
     * for any reason (EOF, SocketException, cancellation-driven [close], or any other
     * exception). [replay] = 1 so a late subscriber immediately sees the signal.
     * The §7 supervisor uses this as the real socket-drop signal to trigger reconnect.
     */
    fun awaitClosed(): SharedFlow<Unit> = _closed.asSharedFlow()

    /** Encodes and sends a frame; safe to call from any coroutine. */
    override suspend fun send(type: FrameType, payload: ByteArray) {
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
        try {
            val accumulator = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            try {
                val inputStream = socket.getInputStream()
                while (true) {
                    val n = withContext(Dispatchers.IO) { inputStream.read(tmp) }
                    if (n == -1) break          // EOF — peer closed
                    accumulator.write(tmp, 0, n)

                    // Drain all complete frames from the accumulated buffer.
                    var buf = accumulator.toByteArray()
                    var consumed = true
                    while (consumed) {
                        // The 4-byte header (type + 3-byte BE length) is plaintext,
                        // so a frame's total size is known without decrypting. A
                        // decode/decrypt failure of ONE frame must not kill the
                        // reader: resync past it by its declared length and keep
                        // going. (Companion is length-prefixed, so this is safe.)
                        if (buf.size < Frame.HEADER) break
                        val len = ((buf[1].toInt() and 0xFF) shl 16) or
                            ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                        val total = Frame.HEADER + len
                        if (buf.size < total) break // wait for the rest of this frame
                        val result = try {
                            Frame.decode(buf, cipher)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // Undecodable/undecryptable frame — skip it, stay alive.
                            buf = buf.copyOfRange(total, buf.size)
                            continue
                        }
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
        } finally {
            // Emit the close signal once on every termination path:
            // EOF (normal exit), SocketException, cancellation-driven close(), or any
            // other exception. replay=1 so a late subscriber sees it immediately.
            // tryEmit never suspends and is safe in a finally (including cancellation).
            _closed.tryEmit(Unit)
        }
    }
}
