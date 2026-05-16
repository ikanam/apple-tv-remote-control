package dev.atvremote.protocol.session

import dev.atvremote.protocol.connection.CommandChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Test double for [CommandChannel] that records each exchanged command name in order
 * and returns the caller-supplied response map. Also records the full content map for
 * each exchange call so tests can assert on payload contents.
 *
 * @param rec mutable list to which each [exchange] call appends the command name.
 * @param respond lambda invoked with the command name; its return value is returned by [exchange].
 */
class RecordingProtocol(
    private val rec: MutableList<String>,
    private val respond: (name: String) -> Map<String, Any?>,
) : CommandChannel {
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    /** Records (name, content) for every exchange() call, in order. */
    val exchanged = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun exchange(name: String, content: Map<String, Any?>): Map<String, Any?> {
        rec += name
        exchanged += (name to content)
        return respond(name)
    }

    override suspend fun sendEvent(name: String, content: Map<String, Any?>) {
        // Events are not recorded in 'rec' per the locked test (only 4 exchange calls checked)
        events += (name to content)
    }
}

class SessionHandshakeTest {
    @Test fun sendsCommandsInOrder() = runTest {
        val rec = mutableListOf<String>()
        val proto = RecordingProtocol(rec) { name -> mapOf("_c" to mapOf("_sid" to 42L)) }
        SessionHandshake(proto, deviceId = "dev", clientId = "cli", name = "Pixel", model = "Pixel 8").run()
        assertEquals(listOf("_systemInfo", "_touchStart", "_sessionStart", "_tiStart"), rec.take(4))
        assertEquals(listOf("_interest"), proto.events.map { it.first })
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("_iMC"), proto.events.single().second["_regEvents"])
    }

    /**
     * Verifies that `_systemInfo._i` is the injected rpId (pyatv `info.rp_id` — a distinct
     * random identifier, NOT the HAP pairing client id), and that `_idsID`/`_pubID` remain
     * correctly set to clientId/deviceId respectively.
     *
     * pyatv reference: api.py system_info() L199–L208:
     *   "_i"     = info.rp_id     (random hex string, distinct from client_id)
     *   "_idsID" = creds.client_id (the HAP pairing id)
     *   "_pubID" = info.device_id
     */
    @Test fun systemInfoUsesDistinctRpIdNotClientId() = runTest {
        val rec = mutableListOf<String>()
        val proto = RecordingProtocol(rec) { mapOf("_c" to mapOf("_sid" to 1L)) }
        val fixedRpId = "cafecafecafe"   // fixed injected rp_id for deterministic assertion
        SessionHandshake(
            proto,
            deviceId = "FF:70:79:61:74:76",
            clientId = "test-client-id",
            name = "Android",
            model = "Android",
            rpId = fixedRpId,
        ).run()

        val sysInfoContent = proto.exchanged
            .first { it.first == "_systemInfo" }
            .second

        // _i must equal the injected rpId — distinct from clientId
        assertEquals(fixedRpId, sysInfoContent["_i"],
            "_systemInfo._i must be the injected rpId (pyatv info.rp_id)")
        assertNotEquals("test-client-id", sysInfoContent["_i"],
            "_systemInfo._i must NOT be clientId")

        // _idsID must remain the HAP pairing client id
        assertEquals("test-client-id", sysInfoContent["_idsID"],
            "_systemInfo._idsID must be clientId (pyatv creds.client_id)")

        // _pubID must remain the device id
        assertEquals("FF:70:79:61:74:76", sysInfoContent["_pubID"],
            "_systemInfo._pubID must be deviceId (pyatv info.device_id)")
    }
}
