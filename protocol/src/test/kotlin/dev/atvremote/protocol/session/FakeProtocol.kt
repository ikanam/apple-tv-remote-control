package dev.atvremote.protocol.session

import dev.atvremote.protocol.connection.SessionChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeProtocol : SessionChannel {
    val exchanges = mutableListOf<Pair<String, Map<String, Any?>>>()
    val sentEvents = mutableListOf<Pair<String, Map<String, Any?>>>()
    var onExchange: (String, Map<String, Any?>) -> Map<String, Any?> = { _, _ -> emptyMap() }
    // replay=1 (NOT 0, unlike the plan's literal spec and production
    // CompanionProtocol): runTest's StandardTestDispatcher schedules coroutines
    // lazily, so a `launch { events.first() }` collector may start AFTER an
    // emitEvent() — with replay=0 that event is silently dropped and the
    // collector hangs (runTest times out). replay=1 makes the double robust to
    // that scheduling jitter. Consequence for Plan-2 test authors (T16/T17):
    // a late events.first() returns the LAST emitted event, not the next one —
    // launch your collector before emitEvent if you need strict ordering.
    // This is intentional TEST-ONLY behaviour; production CompanionProtocol
    // keeps replay=0.
    private val _events = MutableSharedFlow<Pair<String, Map<String, Any?>>>(
        replay = 1, extraBufferCapacity = 64)
    override val events: SharedFlow<Pair<String, Map<String, Any?>>> = _events.asSharedFlow()

    /** Every exchange()/sendEvent() call in invocation order: (name to content).
     *  Needed because _hidT is sendEvent (separate list from exchange's) yet
     *  click/swipe sequences interleave _hidC (exchange) and _hidT (sendEvent).
     *  Prefer `exchanges`/`sentEvents` for single-frame targeted assertions; use `calls` when the assertion requires ordering across both exchange and sendEvent call sites. */
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun exchange(name: String, content: Map<String, Any?>): Map<String, Any?> {
        exchanges.add(name to content); calls.add(name to content); return onExchange(name, content)
    }
    override suspend fun sendEvent(name: String, content: Map<String, Any?>) {
        sentEvents.add(name to content); calls.add(name to content)
    }
    suspend fun emitEvent(name: String, content: Map<String, Any?>) { _events.emit(name to content) }
}
