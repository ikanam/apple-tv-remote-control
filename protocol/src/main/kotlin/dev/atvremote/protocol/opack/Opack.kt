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
}
