// protocol/src/main/kotlin/dev/atvremote/protocol/session/Plist.kt
package dev.atvremote.protocol.session

import java.io.ByteArrayOutputStream

/**
 * Minimal Apple binary property list (bplist00) reader/writer.
 * Public format (CFBinaryPlist). Supports: bool, int (1/2/4/8B), real(8B), ASCII & UTF-16BE
 * strings, data, UID, array, dict. Sufficient for NSKeyedArchiver blobs used by RTI.
 *
 * Known limitations (Task-14/15 watch-items):
 *  - UID is written/read as a single byte only (0x80 + 1 byte). UIDs/refs >255 (large
 *    NSKeyedArchiver `$objects` graphs) are NOT supported — revisit in Task 14/15 if a real
 *    `_tiD` blob's `$objects` exceeds 255 entries (needs 0x8n multi-byte UID + ref-size handling).
 *  - The reader (0x20) treats all reals as 8-byte doubles. A 4-byte float (marker 0x22) would be
 *    misread — revisit in Task 15 if a real blob carries Float (0x22).
 */
object Plist {
    data class Uid(val value: Long)

    // ---- writer ----
    fun write(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        fun collect(v: Any?) {
            objects.add(v)
            when (v) {
                is List<*> -> v.forEach { collect(it) }
                is Map<*, *> -> { v.keys.forEach { collect(it.toString()) }; v.values.forEach { collect(it) } }
            }
        }
        collect(root)
        val refSize = if (objects.size < 256) 1 else if (objects.size < 65536) 2 else 4
        val index = IdentityIndex(objects)
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = LongArray(objects.size)
        fun ref(v: Any?, out: ByteArrayOutputStream) {
            val i = index.indexOf(v).toLong()
            for (b in refSize - 1 downTo 0) out.write(((i shr (8 * b)) and 0xFF).toInt())
        }
        fun writeLen(marker: Int, len: Int, out: ByteArrayOutputStream) {
            if (len < 0xF) out.write(marker or len)
            else { out.write(marker or 0xF); writeInt(len.toLong(), out) }
        }
        for (idx in objects.indices) {
            offsets[idx] = body.size().toLong()
            when (val v = objects[idx]) {
                null -> body.write(0x00)
                is Boolean -> body.write(if (v) 0x09 else 0x08)
                is Uid -> { body.write(0x80); body.write(v.value.toInt()) }
                is Int, is Long -> { body.write(0x13); val n = (v as Number).toLong()
                    for (b in 7 downTo 0) body.write(((n shr (8 * b)) and 0xFF).toInt()) }
                is Double -> { body.write(0x23)
                    val bits = java.lang.Double.doubleToLongBits(v)
                    for (b in 7 downTo 0) body.write(((bits shr (8 * b)) and 0xFF).toInt()) }
                is ByteArray -> { writeLen(0x40, v.size, body); body.write(v) }
                is String -> {
                    if (v.all { it.code < 0x80 }) { writeLen(0x50, v.length, body); body.write(v.toByteArray(Charsets.US_ASCII)) }
                    else { writeLen(0x60, v.length, body); body.write(v.toByteArray(Charsets.UTF_16BE)) }
                }
                is List<*> -> { writeLen(0xA0, v.size, body); v.forEach { ref(it, body) } }
                is Map<*, *> -> {
                    writeLen(0xD0, v.size, body)
                    v.keys.forEach { ref(it.toString(), body) }
                    v.values.forEach { ref(it, body) }
                }
                else -> error("Plist: unsupported ${v!!::class}")
            }
        }
        val offTableStart = body.size().toLong()
        val offSize = if (offTableStart < 256) 1 else if (offTableStart < 65536) 2 else 4
        for (o in offsets) for (b in offSize - 1 downTo 0) body.write(((o shr (8 * b)) and 0xFF).toInt())
        val trailer = ByteArray(32)
        trailer[6] = offSize.toByte(); trailer[7] = refSize.toByte()
        putLong(trailer, 8, objects.size.toLong())
        putLong(trailer, 16, 0L)                 // root index
        putLong(trailer, 24, offTableStart)
        body.write(trailer)
        return body.toByteArray()
    }

    private fun writeInt(n: Long, out: ByteArrayOutputStream) {
        // 0x10 = 1-byte int (bplist00: 0x1n holds 2^n bytes). Used by writeLen's
        // escape for container/string/data lengths 15..255. Lengths >255 need a
        // wider marker (0x11/0x12) — deferred to Task 15 if a real blob requires it.
        out.write(0x10); out.write((n and 0xFF).toInt())
    }
    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0..7) b[off + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
    }
    private class IdentityIndex(val list: List<Any?>) {
        fun indexOf(v: Any?): Int {
            for (i in list.indices) if (list[i] === v ||
                (list[i] is String && v is String && list[i] == v)) return i
            return list.indexOf(v)
        }
    }

    // ---- reader ----
    fun read(bytes: ByteArray): Any? {
        val offSize = bytes[bytes.size - 32 + 6].toInt() and 0xFF
        val refSize = bytes[bytes.size - 32 + 7].toInt() and 0xFF
        val numObjects = readBE(bytes, bytes.size - 32 + 8, 8).toInt()
        val rootIndex = readBE(bytes, bytes.size - 32 + 16, 8).toInt()
        val offTableStart = readBE(bytes, bytes.size - 32 + 24, 8).toInt()
        val offsets = IntArray(numObjects) {
            readBE(bytes, offTableStart + it * offSize, offSize).toInt()
        }
        fun parse(idx: Int): Any? {
            var p = offsets[idx]
            val marker = bytes[p].toInt() and 0xFF
            val hi = marker and 0xF0; val lo = marker and 0x0F
            fun len(): Int {
                if (lo != 0xF) return lo
                p++
                val intMarker = bytes[p].toInt() and 0xFF
                val n = 1 shl (intMarker and 0x0F)
                val l = readBE(bytes, p + 1, n).toInt(); p += n; return l
            }
            return when (hi) {
                0x00 -> if (marker == 0x09) true else if (marker == 0x08) false else null
                0x10 -> readBE(bytes, p + 1, 1 shl lo)
                0x20 -> java.lang.Double.longBitsToDouble(readBE(bytes, p + 1, 1 shl lo))
                0x40 -> { val n = len(); bytes.copyOfRange(p + 1, p + 1 + n) }
                0x50 -> { val n = len(); String(bytes, p + 1, n, Charsets.US_ASCII) }
                0x60 -> { val n = len(); String(bytes, p + 1, n * 2, Charsets.UTF_16BE) }
                0x80 -> Uid((bytes[p + 1].toLong() and 0xFF))
                0xA0 -> { val n = len(); val start = p + 1
                    (0 until n).map { parse(readBE(bytes, start + it * refSize, refSize).toInt()) } }
                0xD0 -> { val n = len(); val start = p + 1
                    val keys = (0 until n).map { parse(readBE(bytes, start + it * refSize, refSize).toInt()) }
                    val vals = (0 until n).map { parse(readBE(bytes, start + (n + it) * refSize, refSize).toInt()) }
                    keys.indices.associate { keys[it].toString() to vals[it] } }
                else -> error("Plist: bad marker 0x%02x".format(marker))
            }
        }
        return parse(rootIndex)
    }

    private fun readBE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xFF); return v
    }
}
