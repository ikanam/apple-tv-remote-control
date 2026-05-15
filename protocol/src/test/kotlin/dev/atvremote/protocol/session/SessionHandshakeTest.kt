package dev.atvremote.protocol.session

import dev.atvremote.protocol.connection.CommandChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test double for [CommandChannel] that records each exchanged command name in order
 * and returns the caller-supplied response map.
 *
 * @param rec mutable list to which each [exchange] call appends the command name.
 * @param respond lambda invoked with the command name; its return value is returned by [exchange].
 */
class RecordingProtocol(
    private val rec: MutableList<String>,
    private val respond: (name: String) -> Map<String, Any?>,
) : CommandChannel {
    override suspend fun exchange(name: String, content: Map<String, Any?>): Map<String, Any?> {
        rec += name
        return respond(name)
    }

    override suspend fun sendEvent(name: String, content: Map<String, Any?>) {
        // Events are not recorded in 'rec' per the locked test (only 4 exchange calls checked)
    }
}

class SessionHandshakeTest {
    @Test fun sendsCommandsInOrder() = runTest {
        val rec = mutableListOf<String>()
        val proto = RecordingProtocol(rec) { name -> mapOf("_c" to mapOf("_sid" to 42L)) }
        SessionHandshake(proto, deviceId = "dev", clientId = "cli", name = "Pixel", model = "Pixel 8").run()
        assertEquals(listOf("_systemInfo", "_touchStart", "_sessionStart", "_tiStart"), rec.take(4))
    }
}
