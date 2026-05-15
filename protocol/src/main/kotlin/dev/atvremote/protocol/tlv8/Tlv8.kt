package dev.atvremote.protocol.tlv8
import java.io.ByteArrayOutputStream
object Tlv8 {
    const val Method=0x00; const val Identifier=0x01; const val Salt=0x02; const val PublicKey=0x03
    const val Proof=0x04; const val EncryptedData=0x05; const val SeqNo=0x06; const val Error=0x07
    const val Signature=0x0A; const val Permissions=0x0B; const val Name=0x11; const val Flags=0x13
    fun write(items: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var off = 0
            if (value.isEmpty()) { out.write(tag); out.write(0) }
            while (off < value.size) {
                val len = minOf(255, value.size - off)
                out.write(tag); out.write(len); out.write(value, off, len); off += len
            }
        }
        return out.toByteArray()
    }
    fun read(data: ByteArray): Map<Int, ByteArray> {
        val map = LinkedHashMap<Int, ByteArrayOutputStream>(); var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; val len = data[i+1].toInt() and 0xFF
            map.getOrPut(tag) { ByteArrayOutputStream() }.write(data, i+2, len); i += 2 + len
        }
        return map.mapValues { it.value.toByteArray() }
    }
}
