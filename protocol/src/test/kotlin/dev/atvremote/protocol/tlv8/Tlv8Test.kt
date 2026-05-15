package dev.atvremote.protocol.tlv8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class Tlv8Test {
    @Test fun writeReadSingle() {
        val enc = Tlv8.write(mapOf(Tlv8.SeqNo to byteArrayOf(1), Tlv8.Method to byteArrayOf(0)))
        val dec = Tlv8.read(enc)
        assertTrue(dec[Tlv8.SeqNo]!!.contentEquals(byteArrayOf(1)))
        assertTrue(dec[Tlv8.Method]!!.contentEquals(byteArrayOf(0)))
    }
    @Test fun fragmentsOver255() {
        val big = ByteArray(600) { it.toByte() }
        val dec = Tlv8.read(Tlv8.write(mapOf(Tlv8.PublicKey to big)))
        assertEquals(600, dec[Tlv8.PublicKey]!!.size)
        assertTrue(dec[Tlv8.PublicKey]!!.contentEquals(big))
    }
}
