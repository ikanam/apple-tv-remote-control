package dev.atvremote.protocol.connection

import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.opack.Opack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ProtocolException(message: String) : Exception(message)

/**
 * Minimal interface required by [CompanionProtocol].
 * Both [CompanionConnection] and the test [FakeConnection] implement this.
 */
interface FrameTransport {
    suspend fun send(type: FrameType, payload: ByteArray)
    fun frames(): SharedFlow<Pair<FrameType, ByteArray>>
}

/**
 * Minimal abstraction over the Companion protocol's request/event surface.
 *
 * Introduced so that session-layer components (e.g. [dev.atvremote.protocol.session.SessionHandshake])
 * can depend on an interface rather than the concrete [CompanionProtocol], enabling
 * lightweight test doubles (see `RecordingProtocol` in `SessionHandshakeTest`).
 *
 * [CompanionProtocol] implements this interface; all production code uses that class directly.
 */
interface CommandChannel {
    /**
     * Sends a named request with the given [content] and suspends until the response arrives.
     * Returns the decoded response dict (string keys; integers decoded as [Long]).
     */
    suspend fun exchange(name: String, content: Map<String, Any?>): Map<String, Any?>

    /**
     * Sends a one-way named event; does not wait for a response.
     */
    suspend fun sendEvent(name: String, content: Map<String, Any?>)
}

/**
 * Protocol layer on top of a [FrameTransport]:
 * - Request/response correlation via `_x` (XID)
 * - Event fan-out for `_t == 1` messages
 * - Auth exchange (pairing: PS/PV frame pairs)
 */
class CompanionProtocol(
    private val conn: FrameTransport,
    context: CoroutineContext = SupervisorJob() + Dispatchers.Default,
) : CommandChannel {

    // Always create a fresh SupervisorJob as a child of any Job in the provided context.
    // This ensures close() cancels only this scope's job, never the caller's job (e.g. a test scope).
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))

    // XID counter: atomic so concurrent exchange() calls never duplicate a correlation id.
    // Masked to 16 bits on each use to stay within Companion's 16-bit _x field range.
    private val xidCounter = AtomicInteger(Random.nextInt(0, 65536))

    // Pending exchange deferreds keyed by Long XID (as Opack decodes ints as Long)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()

    // Pending auth deferred: only one at a time; keyed by expected response FrameType
    private val pendingAuth = ConcurrentHashMap<FrameType, CompletableDeferred<Map<String, Any?>>>()

    // Events flow for inbound _t == 1 messages
    private val _events = MutableSharedFlow<Pair<String, Map<String, Any?>>>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<Pair<String, Map<String, Any?>>> = _events.asSharedFlow()

    init {
        // Start the internal collector before any exchange/send calls
        scope.launch { collectFrames() }
    }

    /**
     * Builds and sends a request, then suspends until the matching response arrives.
     * Timeout: 5 seconds.
     */
    override suspend fun exchange(name: String, content: Map<String, Any?>): Map<String, Any?> {
        val myXid = xidCounter.getAndIncrement() and 0xFFFF
        val myXidLong = myXid.toLong()
        val payload = Opack.pack(
            mapOf("_i" to name, "_t" to 2, "_c" to content, "_x" to myXid)
        )
        val deferred = CompletableDeferred<Map<String, Any?>>()
        pending[myXidLong] = deferred
        try {
            conn.send(FrameType.E_OPACK, payload)
            return withTimeout(5_000) { deferred.await() }
        } finally {
            pending.remove(myXidLong)
        }
    }

    /**
     * Sends a one-way event (no wait for response).
     */
    override suspend fun sendEvent(name: String, content: Map<String, Any?>) {
        val payload = Opack.pack(
            mapOf("_i" to name, "_t" to 1, "_c" to content)
        )
        conn.send(FrameType.E_OPACK, payload)
    }

    /**
     * Sends a pairing frame and awaits the corresponding response frame.
     * Mapping: PS_Start → PS_Next, PV_Start → PV_Next, else same type.
     */
    suspend fun sendAuth(type: FrameType, opack: Map<String, Any?>): Map<String, Any?> {
        val responseType = when (type) {
            FrameType.PS_Start -> FrameType.PS_Next
            FrameType.PV_Start -> FrameType.PV_Next
            else -> type
        }
        val payload = Opack.pack(opack)
        val deferred = CompletableDeferred<Map<String, Any?>>()
        pendingAuth[responseType] = deferred
        try {
            conn.send(type, payload)
            return withTimeout(5_000) { deferred.await() }
        } finally {
            pendingAuth.remove(responseType)
        }
    }

    /** Cancels the internal collector scope. */
    fun close() {
        scope.cancel()
    }

    // ---- private ----

    private suspend fun collectFrames() {
        conn.frames().collect { (frameType, payload) ->
            when (frameType) {
                FrameType.E_OPACK -> handleOpack(payload)
                FrameType.PS_Next, FrameType.PV_Next -> handleAuth(frameType, payload)
                else -> {
                    // Other frame types: try auth deferred first (same-type fallback)
                    if (pendingAuth.containsKey(frameType)) {
                        handleAuth(frameType, payload)
                    }
                }
            }
        }
    }

    private fun handleOpack(payload: ByteArray) {
        val dict = runCatching {
            @Suppress("UNCHECKED_CAST")
            Opack.unpack(payload).first as? Map<String, Any?>
        }.getOrNull() ?: return

        val tRaw = dict["_t"]
        val t = (tRaw as? Number)?.toInt() ?: return

        when (t) {
            3 -> {
                // Response to an exchange
                val xidRaw = dict["_x"] ?: return
                val xidLong = (xidRaw as? Number)?.toLong() ?: return
                val deferred = pending[xidLong] ?: return
                val em = dict["_em"]
                if (em != null) {
                    deferred.completeExceptionally(ProtocolException("Command failed: $em"))
                } else {
                    deferred.complete(dict)
                }
            }
            1 -> {
                // Inbound event
                val name = dict["_i"] as? String ?: return
                @Suppress("UNCHECKED_CAST")
                val content = dict["_c"] as? Map<String, Any?> ?: emptyMap()
                // Non-blocking emit; if the buffer is full we drop (extraBufferCapacity=64)
                _events.tryEmit(Pair(name, content))
            }
            else -> { /* ignore other _t values */ }
        }
    }

    private fun handleAuth(frameType: FrameType, payload: ByteArray) {
        val deferred = pendingAuth[frameType] ?: return
        val dict = runCatching {
            @Suppress("UNCHECKED_CAST")
            Opack.unpack(payload).first as? Map<String, Any?>
        }.getOrNull()
        if (dict == null) {
            deferred.completeExceptionally(ProtocolException("Auth response unpack failed"))
            return
        }
        deferred.complete(dict)
    }
}
