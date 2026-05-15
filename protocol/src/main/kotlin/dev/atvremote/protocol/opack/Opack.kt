package dev.atvremote.protocol.opack

import java.io.ByteArrayOutputStream
import java.util.UUID

object Opack {
    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(); val ol = ObjectList(); packInto(value, out, ol); return out.toByteArray()
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun packInto(value: Any?, out: ByteArrayOutputStream, ol: ObjectList) {
        val packed = encode(value, ol)
        if (packed.size > 1) {
            val idx = ol.indexOf(packed)
            if (idx >= 0) { out.write(backref(idx)); return }
            ol.add(packed)
        }
        out.write(packed)
    }

    private fun backref(i: Int): ByteArray = when {
        i < 0x21 -> byteArrayOf((0xA0 + i).toByte())
        i <= 0xFF -> byteArrayOf(0xC1.toByte()) + le(i.toLong(), 1)
        i <= 0xFFFF -> byteArrayOf(0xC2.toByte()) + le(i.toLong(), 2)
        i <= 0xFFFFFFFFL.toInt() -> byteArrayOf(0xC3.toByte()) + le(i.toLong(), 4)
        else -> byteArrayOf(0xC4.toByte()) + le(i.toLong(), 8)
    }

    private fun encode(value: Any?, ol: ObjectList): ByteArray {
        val b = ByteArrayOutputStream()
        when (value) {
            null -> b.write(0x04)
            is Boolean -> b.write(if (value) 0x01 else 0x02)
            is Int -> b.write(encode(value.toLong(), ol))
            is Long -> {
                val v = value
                when {
                    v in 0..0x27 -> b.write((v + 8).toInt())
                    v <= 0xFF -> { b.write(0x30); b.write(le(v, 1)) }
                    v <= 0xFFFF -> { b.write(0x31); b.write(le(v, 2)) }
                    v <= 0xFFFFFFFFL -> { b.write(0x32); b.write(le(v, 4)) }
                    else -> { b.write(0x33); b.write(le(v, 8)) }
                }
            }
            is Double -> { b.write(0x36); b.write(java.lang.Double.doubleToLongBits(value).let { le(it, 8) }) }
            is String -> {
                val s = value.toByteArray(Charsets.UTF_8)
                if (s.size <= 0x20) b.write(0x40 + s.size) else {
                    when {
                        s.size <= 0xFF -> { b.write(0x61); b.write(le(s.size.toLong(), 1)) }
                        s.size <= 0xFFFF -> { b.write(0x62); b.write(le(s.size.toLong(), 2)) }
                        s.size <= 0xFFFFFF -> { b.write(0x63); b.write(le(s.size.toLong(), 3)) }
                        else -> { b.write(0x64); b.write(le(s.size.toLong(), 4)) }
                    }
                }
                b.write(s)
            }
            is ByteArray -> {
                if (value.size <= 0x20) b.write(0x70 + value.size) else {
                    when {
                        value.size <= 0xFF -> { b.write(0x91); b.write(le(value.size.toLong(), 1)) }
                        value.size <= 0xFFFF -> { b.write(0x92); b.write(le(value.size.toLong(), 2)) }
                        value.size <= 0xFFFFFFFFL.toInt() -> { b.write(0x93); b.write(le(value.size.toLong(), 4)) }
                        else -> { b.write(0x94); b.write(le(value.size.toLong(), 8)) }
                    }
                }
                b.write(value)
            }
            is UUID -> {
                b.write(0x05)
                val bb = java.nio.ByteBuffer.allocate(16)
                bb.putLong(value.mostSignificantBits)
                bb.putLong(value.leastSignificantBits)
                b.write(bb.array())
            }
            is List<*> -> {
                val n = value.size
                b.write(0xD0 + minOf(n, 0xF))
                val sub = ByteArrayOutputStream()
                value.forEach { packInto(it, sub, ol) }
                b.write(sub.toByteArray())
                if (n >= 0xF) b.write(0x03)
            }
            is Map<*, *> -> {
                val n = value.size
                b.write(0xE0 + minOf(n, 0xF))
                val sub = ByteArrayOutputStream()
                value.forEach { (k, v) -> packInto(k, sub, ol); packInto(v, sub, ol) }
                b.write(sub.toByteArray())
                if (n >= 0xF) b.write(0x03)
            }
            else -> error("OPACK: unsupported type ${value::class}")
        }
        return b.toByteArray()
    }

    // ── Decoder ──────────────────────────────────────────────────────────────

    /**
     * Decodes one OPACK value from [data] and returns it paired with the
     * remaining (unconsumed) bytes.
     */
    fun unpack(data: ByteArray): Pair<Any?, ByteArray> {
        val ol = DecoderObjectList()
        val (value, consumed) = decode(data, 0, ol)
        return Pair(value, data.copyOfRange(consumed, data.size))
    }

    /**
     * Recursive decoder.  Returns (decoded-value, new-offset).
     * Appends to [ol] for every value whose packed slice is > 1 byte,
     * mirroring the encoder's ObjectList population order.
     */
    private fun decode(data: ByteArray, off: Int, ol: DecoderObjectList): Pair<Any?, Int> {
        val tag = data[off].toInt() and 0xFF
        val startOff = off

        val (value, newOff) = when {
            // null
            tag == 0x04 -> Pair(null, off + 1)
            // true / false
            tag == 0x01 -> Pair(true, off + 1)
            tag == 0x02 -> Pair(false, off + 1)
            // uuid
            tag == 0x05 -> {
                val bytes = data.copyOfRange(off + 1, off + 17)
                val bb = java.nio.ByteBuffer.wrap(bytes)
                val uuid = UUID(bb.long, bb.long)
                Pair(uuid, off + 17)
            }
            // small int 0x08..0x2F  (value = tag - 8, range 0..39)
            tag in 0x08..0x2F -> Pair((tag - 8).toLong(), off + 1)
            // int with length prefix 0x30(1B) 0x31(2B) 0x32(4B) 0x33(8B) LE
            tag == 0x30 -> Pair(leRead(data, off + 1, 1), off + 2)
            tag == 0x31 -> Pair(leRead(data, off + 1, 2), off + 3)
            tag == 0x32 -> Pair(leRead(data, off + 1, 4), off + 5)
            tag == 0x33 -> Pair(leRead(data, off + 1, 8), off + 9)
            // float64
            tag == 0x36 -> {
                val bits = leRead(data, off + 1, 8)
                Pair(java.lang.Double.longBitsToDouble(bits), off + 9)
            }
            // short string 0x40..0x60 (len = tag - 0x40)
            tag in 0x40..0x60 -> {
                val len = tag - 0x40
                Pair(String(data, off + 1, len, Charsets.UTF_8), off + 1 + len)
            }
            // string with length prefix 0x61(1B) 0x62(2B) 0x63(3B) 0x64(4B)
            tag == 0x61 -> { val len = leRead(data, off + 1, 1).toInt(); Pair(String(data, off + 2, len, Charsets.UTF_8), off + 2 + len) }
            tag == 0x62 -> { val len = leRead(data, off + 1, 2).toInt(); Pair(String(data, off + 3, len, Charsets.UTF_8), off + 3 + len) }
            tag == 0x63 -> { val len = leRead(data, off + 1, 3).toInt(); Pair(String(data, off + 4, len, Charsets.UTF_8), off + 4 + len) }
            tag == 0x64 -> { val len = leRead(data, off + 1, 4).toInt(); Pair(String(data, off + 5, len, Charsets.UTF_8), off + 5 + len) }
            // short data 0x70..0x90 (len = tag - 0x70)
            tag in 0x70..0x90 -> {
                val len = tag - 0x70
                Pair(data.copyOfRange(off + 1, off + 1 + len), off + 1 + len)
            }
            // data with length prefix 0x91(1B) 0x92(2B) 0x93(4B) 0x94(8B)
            tag == 0x91 -> { val len = leRead(data, off + 1, 1).toInt(); Pair(data.copyOfRange(off + 2, off + 2 + len), off + 2 + len) }
            tag == 0x92 -> { val len = leRead(data, off + 1, 2).toInt(); Pair(data.copyOfRange(off + 3, off + 3 + len), off + 3 + len) }
            tag == 0x93 -> { val len = leRead(data, off + 1, 4).toInt(); Pair(data.copyOfRange(off + 5, off + 5 + len), off + 5 + len) }
            tag == 0x94 -> { val len = leRead(data, off + 1, 8).toInt(); Pair(data.copyOfRange(off + 9, off + 9 + len), off + 9 + len) }
            // back-ref short: 0xA0..0xC0  (index = tag - 0xA0, range 0..32)
            tag in 0xA0..0xC0 -> {
                val idx = tag - 0xA0
                val (refVal, _) = decode(ol.get(idx), 0, ol)
                Pair(refVal, off + 1)
            }
            // back-ref long: 0xC1(1B) 0xC2(2B) 0xC3(4B) 0xC4(8B) LE index
            tag == 0xC1 -> { val idx = leRead(data, off + 1, 1).toInt(); val (v, _) = decode(ol.get(idx), 0, ol); Pair(v, off + 2) }
            tag == 0xC2 -> { val idx = leRead(data, off + 1, 2).toInt(); val (v, _) = decode(ol.get(idx), 0, ol); Pair(v, off + 3) }
            tag == 0xC3 -> { val idx = leRead(data, off + 1, 4).toInt(); val (v, _) = decode(ol.get(idx), 0, ol); Pair(v, off + 5) }
            tag == 0xC4 -> { val idx = leRead(data, off + 1, 8).toInt(); val (v, _) = decode(ol.get(idx), 0, ol); Pair(v, off + 9) }
            // array 0xD0..0xDF
            tag in 0xD0..0xDF -> {
                val nibble = tag and 0x0F
                val list = mutableListOf<Any?>()
                var cur = off + 1
                if (nibble == 0xF) {
                    // terminated by 0x03
                    while (true) {
                        val t = data[cur].toInt() and 0xFF
                        if (t == 0x03) { cur++; break }
                        val (v, next) = decode(data, cur, ol)
                        // ol population for element happens inside recursive decode
                        list.add(v); cur = next
                    }
                } else {
                    repeat(nibble) {
                        val (v, next) = decode(data, cur, ol)
                        list.add(v); cur = next
                    }
                }
                Pair(list as List<Any?>, cur)
            }
            // dict 0xE0..0xEF
            tag in 0xE0..0xEF -> {
                val nibble = tag and 0x0F
                val map = mutableMapOf<String, Any?>()
                var cur = off + 1
                if (nibble == 0xF) {
                    while (true) {
                        val t = data[cur].toInt() and 0xFF
                        if (t == 0x03) { cur++; break }
                        val (k, kNext) = decode(data, cur, ol)
                        cur = kNext
                        val (v, vNext) = decode(data, cur, ol)
                        cur = vNext
                        map[k as String] = v
                    }
                } else {
                    repeat(nibble) {
                        val (k, kNext) = decode(data, cur, ol)
                        cur = kNext
                        val (v, vNext) = decode(data, cur, ol)
                        cur = vNext
                        map[k as String] = v
                    }
                }
                Pair(map as Map<String, Any?>, cur)
            }
            else -> error("OPACK decode: unknown tag 0x%02X at offset %d".format(tag, off))
        }

        // Mirror the encoder's ObjectList.add logic:
        // The encoder calls packInto → encode → if packed.size > 1: check/add to ol.
        // Back-refs themselves are never added; they reference already-added entries.
        // Lists and dicts: their total packed slice IS added by the encoder (size > 1 when non-trivial).
        val sliceLen = newOff - startOff
        val isBackRef = tag in 0xA0..0xC4
        if (sliceLen > 1 && !isBackRef) {
            ol.add(data.copyOfRange(startOff, newOff))
        }

        return Pair(value, newOff)
    }

    /** Read [n] bytes at [off] as a little-endian unsigned long. */
    private fun leRead(data: ByteArray, off: Int, n: Int): Long {
        var result = 0L
        for (i in 0 until n) result = result or ((data[off + i].toLong() and 0xFF) shl (8 * i))
        return result
    }

    /** Decoder-side object list: stores packed byte-slices in document order. */
    private class DecoderObjectList {
        private val items = ArrayList<ByteArray>()
        fun add(b: ByteArray) { items.add(b) }
        fun get(idx: Int): ByteArray = items[idx]
    }
}
