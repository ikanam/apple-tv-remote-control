package dev.atvremote.protocol.discovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class DiscoveryParseTest {
    @Test fun parsesRpflAndModel() {
        val d = JmdnsDiscovery.toDevice(
            name = "Living Room", host = "10.0.0.5", port = 49152,
            txt = mapOf("rpmd" to "AppleTV14,1", "rpfl" to "0x4000"))
        assertEquals("AppleTV14,1", d.model); assertTrue(d.pairable)
        val disabled = JmdnsDiscovery.toDevice("X","10.0.0.6",1, mapOf("rpfl" to "0x4"))
        assertTrue(!disabled.pairable)
    }
}
