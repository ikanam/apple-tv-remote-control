package dev.atvremote.protocol.opack
import kotlin.test.Test
import kotlin.test.assertEquals
class OpackDecodeTest {
    private fun rt(v: Any?) = Opack.unpack(Opack.pack(v)).first
    @Test fun roundTrips() {
        assertEquals(null, rt(null)); assertEquals(true, rt(true)); assertEquals(2L, rt(2))
        assertEquals(255L, rt(255)); assertEquals(70000L, rt(70000)); assertEquals("hello", rt("hello"))
        assertEquals(listOf(1L, "x", true), rt(listOf(1, "x", true)))
        assertEquals(mapOf("_i" to "_systemInfo", "_t" to 2L), rt(mapOf("_i" to "_systemInfo", "_t" to 2)))
    }
    @Test fun decodesFloat32Tag0x35() {
        // Real tvOS emits OPACK float32 (tag 0x35) — pyatv: struct.unpack("<f").
        // Our encoder only emits double (0x36), so this is a known-answer decode:
        // 1.5f = IEEE-754 0x3FC00000, little-endian bytes 00 00 C0 3F.
        val bytes = byteArrayOf(0x35, 0x00, 0x00, 0xC0.toByte(), 0x3F)
        assertEquals(1.5, Opack.unpack(bytes).first)
    }

    @Test fun backRefsWithMultipleDistinctRepeats() {
        // distinct long strings (>1 packed byte) repeated in interleaved order
        val v = listOf("alpha-key", "beta-key", "alpha-key", "gamma-key", "beta-key", "gamma-key")
        assertEquals(v.map { it as Any? }, Opack.unpack(Opack.pack(v)).first)
        // nested map with repeated keys like real protocol frames
        val m = mapOf("_i" to "_systemInfo", "_t" to 2,
            "_c" to mapOf("_i" to "child", "_t" to 3, "_x" to 7))
        val expected = mapOf<String,Any?>("_i" to "_systemInfo", "_t" to 2L,
            "_c" to mapOf<String,Any?>("_i" to "child", "_t" to 3L, "_x" to 7L))
        assertEquals(expected, Opack.unpack(Opack.pack(m)).first)
    }
}
