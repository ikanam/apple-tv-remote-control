package dev.atvremote.protocol.discovery

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [selectBindAddress]. Uses [NicCandidate] data class (avoids
 * [java.net.NetworkInterface] which has no public constructor).
 */
class BindAddressSelectionTest {

    private fun addr(ip: String): InetAddress = InetAddress.getByName(ip)

    // ── helpers to build candidates ──────────────────────────────────────────

    private fun lanNic(
        name: String,
        ipv4: List<String> = emptyList(),
        isVirtual: Boolean = false,
    ) = NicCandidate(
        name = name,
        isUp = true,
        isLoopback = false,
        isVirtual = isVirtual,
        supportsMulticast = true,
        ipv4 = ipv4.map { addr(it) },
    )

    private fun loopbackNic() = NicCandidate(
        name = "lo0",
        isUp = true,
        isLoopback = true,
        isVirtual = false,
        supportsMulticast = false,
        ipv4 = listOf(addr("127.0.0.1")),
    )

    private fun utunNic(name: String = "utun0") = NicCandidate(
        name = name,
        isUp = true,
        isLoopback = false,
        isVirtual = false,
        supportsMulticast = true,
        // utun interfaces typically only have link-local IPv6, no IPv4
        ipv4 = emptyList(),
    )

    // ── test (a) typical multi-homed macOS scenario ───────────────────────────

    @Test
    fun `given utun lo0 bridge100 en1 returns en1 address 192_168_7_131`() {
        val cands = listOf(
            utunNic("utun0"),
            loopbackNic(),
            NicCandidate(
                name = "bridge100",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                supportsMulticast = true,
                ipv4 = listOf(addr("192.168.139.3")),
            ),
            lanNic("en1", listOf("192.168.7.131")),
        )
        val result = selectBindAddress(cands)
        assertEquals("192.168.7.131", result?.hostAddress)
    }

    // ── test (b) virtual / utun / awdl / llw / bridge excluded ───────────────

    @Test
    fun `virtual isVirtual nic excluded even with site-local IPv4`() {
        val cands = listOf(
            lanNic("en0", listOf("192.168.1.50"), isVirtual = true),
            lanNic("en1", listOf("192.168.7.131"), isVirtual = false),
        )
        val result = selectBindAddress(cands)
        assertEquals("192.168.7.131", result?.hostAddress)
    }

    @Test
    fun `utun interface excluded even with site-local IPv4`() {
        val cands = listOf(
            lanNic("utun3", listOf("10.8.0.2")),
            lanNic("en1", listOf("192.168.7.131")),
        )
        val result = selectBindAddress(cands)
        assertEquals("192.168.7.131", result?.hostAddress)
    }

    @Test
    fun `awdl interface excluded`() {
        val cands = listOf(
            lanNic("awdl0", listOf("169.254.1.1")),
            lanNic("en1", listOf("192.168.7.131")),
        )
        val result = selectBindAddress(cands)
        assertEquals("192.168.7.131", result?.hostAddress)
    }

    @Test
    fun `llw interface excluded`() {
        val cands = listOf(
            lanNic("llw0", listOf("169.254.200.1")),
            lanNic("en1", listOf("172.16.0.5")),
        )
        val result = selectBindAddress(cands)
        assertEquals("172.16.0.5", result?.hostAddress)
    }

    @Test
    fun `bridge interface excluded`() {
        val cands = listOf(
            lanNic("bridge0", listOf("192.168.2.1")),
            lanNic("en1", listOf("192.168.7.131")),
        )
        val result = selectBindAddress(cands)
        assertEquals("192.168.7.131", result?.hostAddress)
    }

    @Test
    fun `ppp ipsec tap tun interfaces excluded`() {
        val excluded = listOf("ppp0", "ipsec0", "tap0", "tun0").map {
            lanNic(it, listOf("10.0.0.1"))
        }
        // none of them should win; result null if no other candidate
        val result = selectBindAddress(excluded)
        assertNull(result)
    }

    // ── test (c) only loopback / link-local → null ────────────────────────────

    @Test
    fun `only loopback returns null`() {
        val cands = listOf(loopbackNic())
        assertNull(selectBindAddress(cands))
    }

    @Test
    fun `only link-local IPv4 169_254_x_x returns null`() {
        val cands = listOf(
            NicCandidate(
                name = "en0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                supportsMulticast = true,
                ipv4 = listOf(addr("169.254.0.1")),
            ),
        )
        assertNull(selectBindAddress(cands))
    }

    @Test
    fun `down interface excluded`() {
        val cands = listOf(
            NicCandidate(
                name = "en1",
                isUp = false,
                isLoopback = false,
                isVirtual = false,
                supportsMulticast = true,
                ipv4 = listOf(addr("192.168.7.131")),
            ),
        )
        assertNull(selectBindAddress(cands))
    }

    @Test
    fun `interface without multicast excluded`() {
        val cands = listOf(
            NicCandidate(
                name = "en1",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                supportsMulticast = false,
                ipv4 = listOf(addr("192.168.7.131")),
            ),
        )
        assertNull(selectBindAddress(cands))
    }

    // ── test (d) ordering: first matching site-local IPv4 wins ───────────────

    @Test
    fun `first qualifying NIC in list wins`() {
        val cands = listOf(
            lanNic("en0", listOf("10.0.0.2")),
            lanNic("en1", listOf("192.168.7.131")),
        )
        val result = selectBindAddress(cands)
        // en0 comes first and is valid → its IP wins
        assertEquals("10.0.0.2", result?.hostAddress)
    }

    @Test
    fun `empty list returns null`() {
        assertNull(selectBindAddress(emptyList()))
    }

    // ── TXT case-insensitive mapping → toDevice ───────────────────────────────

    @Test
    fun `camelCase TXT rpMd rpFl lowercased yields correct device`() {
        // Simulate what serviceResolved does after the fix: lowercase all keys
        val rawTxt = mapOf("rpMd" to "AppleTV14,1", "rpFl" to "0x36782")
        val lowercased = rawTxt.entries.associate { it.key.lowercase() to it.value }
        val device = JmdnsDiscovery.toDevice(
            name = "客厅",
            host = "192.168.7.134",
            port = 49153,
            txt = lowercased,
        )
        assertEquals("AppleTV14,1", device.model)
        // 0x36782 & 0x4000 != 0  → true; 0x36782 & 0x04 == 0 → true → pairable
        assertTrue(device.pairable)
    }

    @Test
    fun `rpFl 0x20000 yields pairable false and no model`() {
        val rawTxt = mapOf("rpFl" to "0x20000")
        val lowercased = rawTxt.entries.associate { it.key.lowercase() to it.value }
        val device = JmdnsDiscovery.toDevice(
            name = "Shinya的Mac mini",
            host = "192.168.7.100",
            port = 49153,
            txt = lowercased,
        )
        // 0x20000 & 0x4000 == 0 → not pairable
        assertTrue(!device.pairable)
        assertNull(device.model)
    }
}
