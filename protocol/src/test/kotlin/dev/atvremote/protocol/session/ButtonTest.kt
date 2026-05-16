package dev.atvremote.protocol.session

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.connection.CommandChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CommandChannel test double that records each exchange(name, content) call
 * as a (name to content) pair into [sent]. sendEvent is a no-op.
 */
class RecordingProtocol2(
    private val sent: MutableList<Pair<String, Map<String, Any?>>>,
) : CommandChannel {
    override suspend fun exchange(name: String, content: Map<String, Any?>): Map<String, Any?> {
        sent += name to content
        return emptyMap()
    }
    override suspend fun sendEvent(name: String, content: Map<String, Any?>) { /* no-op */ }
}

class ButtonTest {
    @Test fun menuSendsHidC() = runTest {
        val sent = mutableListOf<Pair<String, Map<String,Any?>>>()
        val session = CompanionSessionImpl(RecordingProtocol2(sent))
        session.button(RemoteButton.Menu, down = true)
        assertEquals("_hidC", sent.last().first)
        assertEquals(1, sent.last().second["_hBtS"]); assertEquals(5, sent.last().second["_hidC"])
    }

    /**
     * Real-device (Bug C follow-up): tvOS rejects `_sessionStop` with
     * "No sessionID" unless the negotiated combined session id is supplied.
     * Verified against pyatv `_session_stop`:
     * `{ "_srvT": "com.apple.tvremoteservices", "_sid": sid }`.
     *
     * pyatv `disconnect()` (api.py:109-128) sends `_session_stop()` THEN
     * `_touch_stop()` in that order. `_touch_stop` sends `_touchStop {"_i":1}`
     * via `_send_command` (api.py:456-458, request/response = exchange).
     * Both are wrapped in a single try/except so teardown is best-effort.
     * This test asserts the pyatv-correct teardown sequence: _sessionStop first,
     * then _touchStop, both sent via exchange.
     */
    @Test fun closeSendsSessionStopWithSid() = runTest {
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        val session = CompanionSessionImpl(RecordingProtocol2(sent), sid = 434356147L)
        session.close()
        // 1. _sessionStop with correct sid (Bug C6 fix, device-validated)
        val sessionStop = sent.first { it.first == "_sessionStop" }
        assertEquals("_sessionStop", sessionStop.first)
        assertEquals("com.apple.tvremoteservices", sessionStop.second["_srvT"])
        assertEquals(434356147L, sessionStop.second["_sid"])
        // 2. _touchStop {"_i":1} via exchange, AFTER _sessionStop (pyatv disconnect parity)
        val sessionStopIdx = sent.indexOf(sessionStop)
        val touchStop = sent.drop(sessionStopIdx + 1).firstOrNull { it.first == "_touchStop" }
            ?: error("Expected _touchStop exchange after _sessionStop but was not found. sent=$sent")
        assertEquals("_touchStop", touchStop.first)
        assertEquals(1, touchStop.second["_i"])
    }
}
