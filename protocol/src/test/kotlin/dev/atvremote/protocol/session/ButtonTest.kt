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
}
