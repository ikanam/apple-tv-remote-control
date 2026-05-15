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
}
