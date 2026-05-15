package dev.atvremote.protocol.opack
import kotlin.test.Test
import kotlin.test.assertEquals

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

class OpackEncodeTest {
    @Test fun scalars() {
        assertEquals("04", hex(Opack.pack(null)))
        assertEquals("01", hex(Opack.pack(true)))
        assertEquals("02", hex(Opack.pack(false)))
        assertEquals("0a", hex(Opack.pack(2)))            // small int 2 -> 2+8
        assertEquals("2f", hex(Opack.pack(39)))           // small int max
        assertEquals("30ff", hex(Opack.pack(255)))        // 1-byte int
        assertEquals("3100010000".substring(0,6), hex(Opack.pack(256)).substring(0,6)) // 0x31 + 2B LE
        assertEquals("40", hex(Opack.pack("")))           // empty short string
        assertEquals("43616263", hex(Opack.pack("abc")))  // 0x43 + 'abc'
        assertEquals("70", hex(Opack.pack(ByteArray(0)))) // empty short data
    }
    @Test fun arrayAndDict() {
        assertEquals("d2010a", hex(Opack.pack(listOf(true, 2))))      // array len2, true, int2
        assertEquals("e14161010a".substring(0,2), hex(Opack.pack(mapOf("a" to listOf(true,2)))).substring(0,2))
    }
}
