package dev.atvremote.protocol.opack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    // ── D-1: decoder object-list must NOT add containers (pyatv _unpack:208,225) ──────────
    //
    // pyatv encoder emits back-ref \xa2 (index 2) for the second "bb" in [["aa"],"bb","cc","bb"].
    // Encoder object-list: [0]="aa"bytes, [1]=["aa"]bytes, [2]="bb"bytes, [3]="cc"bytes.
    // pyatv decoder (add_to_object_list=False for arrays): ["aa"](list not added), "bb"->ol[1],
    // "cc"->ol[2]. Back-ref \xa2 -> ol[2] = "cc".
    // Buggy decoder (adds containers): "aa"->ol[0], list->ol[1], "bb"->ol[2], "cc"->ol[3].
    // Back-ref \xa2 -> ol[2] = "bb" — a different (wrong-for-pyatv) result.
    // After fix (no container in decoder list): back-ref \xa2 -> ol[2] = "cc" matching pyatv.
    //
    // Hand-crafted pyatv-encoded bytes for [["aa"],"bb","cc","bb"]:
    //   d4 d1 42 61 61  42 62 62  42 63 63  a2
    //   ^^                                      outer list count=4
    //      ^^ ^^^^^^^^                          inner list count=1, "aa"
    //                   ^^^^^^^^                "bb"
    //                            ^^^^^^^^       "cc"
    //                                     ^^    back-ref index 2
    @Test fun d1_decoderMustNotAddContainersToObjectList() {
        // Bytes produced by pyatv encoder for [["aa"],"bb","cc","bb"]
        val bytes = byteArrayOf(
            0xD4.toByte(),               // outer list count=4
            0xD1.toByte(), 0x42, 0x61, 0x61,  // inner list ["aa"]
            0x42, 0x62, 0x62,            // "bb"
            0x42, 0x63, 0x63,            // "cc"
            0xA2.toByte()                // back-ref index 2
        )
        // pyatv decoder: ol = ["aa"(not added as list is not tracked),...]
        // Precisely: inner list processed → "aa"→ol[0], list NOT added.
        // "bb"→ol[1], "cc"→ol[2]. \xa2 → ol[2] = "cc".
        @Suppress("UNCHECKED_CAST")
        val result = Opack.unpack(bytes).first as List<Any?>
        assertEquals(4, result.size)
        assertEquals(listOf("aa"), result[0])
        assertEquals("bb", result[1])
        assertEquals("cc", result[2])
        // pyatv-conformant: back-ref 2 resolves to "cc" (not "bb")
        assertEquals("cc", result[3])
    }

    // ── D-2: tag 0x06 (absolute/"date") must decode as 8-byte LE Long (pyatv _unpack:156-158) ─
    //
    // pyatv: elif data[0] == 0x06: value, remaining = int.from_bytes(data[1:9], 'little'), data[9:]
    // No add_to_object_list=False → stays True (added to object list like a normal scalar).
    // Our current decoder: else -> error("OPACK decode: unknown tag 0x06 ...") → crash.
    @Test fun d2_tag0x06DecodesAs8ByteLELong() {
        // tag 0x06 + 8 LE bytes encoding value 0x0102030405060708
        val value = 0x0102030405060708L
        val bytes = byteArrayOf(0x06) + ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }
        val (decoded, remaining) = Opack.unpack(bytes)
        assertEquals(value, decoded)
        assertEquals(0, remaining.size)
    }

    @Test fun d2_tag0x06DecodesValueOne() {
        // Minimal: 0x06 + [0x01, 0x00 x7] = 1L
        val bytes = byteArrayOf(0x06, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val (decoded, remaining) = Opack.unpack(bytes)
        assertEquals(1L, decoded)
        assertEquals(0, remaining.size)
    }

    // ── D-3: non-string dict keys must not throw ClassCastException (pyatv _unpack:215,220) ─
    //
    // pyatv: output[key] = value with no cast — any decoded value is a valid key.
    // Our code: map[k as String] → ClassCastException if key is e.g. an int.
    // Fix: use k.toString() as map key (string shape preserved; no API change).
    @Test fun d3_nonStringDictKeyDoesNotThrow() {
        // Dict with one entry: int key 5 (tag 0x0D = 0x08+5), string value "hello"
        // 0xE1 = dict count=1; 0x0D = small int 5; 0x45+'hello' = "hello"
        val bytes = byteArrayOf(
            0xE1.toByte(),               // dict count=1
            0x0D,                        // key: small int 5 (tag 0x08+5)
            0x45, 0x68, 0x65, 0x6C, 0x6C, 0x6F  // value: "hello" (len 5)
        )
        @Suppress("UNCHECKED_CAST")
        val result = Opack.unpack(bytes).first as Map<String, Any?>
        // key is int 5, toString() = "5"
        assertEquals("hello", result["5"])
    }

    @Test fun d3_stringDictKeyStillWorks() {
        // Normal string-keyed dict is unchanged by the fix
        val m = mapOf("foo" to "bar", "n" to 42)
        @Suppress("UNCHECKED_CAST")
        val result = Opack.unpack(Opack.pack(m)).first as Map<String, Any?>
        assertEquals("bar", result["foo"])
        assertEquals(42L, result["n"])
    }

    @Test fun d3_boolDictKeyDoesNotThrow() {
        // Dict with bool key true (tag 0x01), value "yes"
        // 0xE1 = dict count=1; 0x01 = true; 0x43+'yes'
        val bytes = byteArrayOf(
            0xE1.toByte(),
            0x01,                        // key: true
            0x43, 0x79, 0x65, 0x73      // value: "yes"
        )
        @Suppress("UNCHECKED_CAST")
        val result = Opack.unpack(bytes).first as Map<String, Any?>
        assertEquals("yes", result["true"])
    }
}
