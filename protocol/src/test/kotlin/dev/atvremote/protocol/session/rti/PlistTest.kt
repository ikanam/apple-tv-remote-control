// protocol/src/test/kotlin/dev/atvremote/protocol/session/rti/PlistTest.kt
package dev.atvremote.protocol.session.rti

import dev.atvremote.protocol.session.Plist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlistTest {
    @Test fun roundTripScalarsAndContainers() {
        val obj = mapOf(
            "s" to "hello",
            "n" to 42L,
            "b" to true,
            "d" to byteArrayOf(1, 2, 3),
            "arr" to listOf(1L, "x"),
            "uid" to Plist.Uid(5),
        )
        val bytes = Plist.write(obj)
        assertTrue(bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "bplist00")
        @Suppress("UNCHECKED_CAST")
        val back = Plist.read(bytes) as Map<String, Any?>
        assertEquals("hello", back["s"])
        assertEquals(42L, back["n"])
        assertEquals(true, back["b"])
        assertTrue((back["d"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf(1L, "x"), back["arr"])
        assertEquals(Plist.Uid(5), back["uid"])
    }

    @Test fun readsUtf16String() {
        val bytes = Plist.write(mapOf("k" to "café—ünì"))
        @Suppress("UNCHECKED_CAST")
        assertEquals("café—ünì", (Plist.read(bytes) as Map<String, Any?>)["k"])
    }

    @Test fun roundTripContainersOverEscapeThreshold() {
        val longStr = "x".repeat(40)                       // forces 0x6?/0x5? len-escape
        val bigList = (1L..20L).toList()                   // forces 0xA? len-escape
        val bigData = ByteArray(30) { it.toByte() }        // forces 0x4? len-escape
        val bigDict = (1..16).associate { "k$it" to it.toLong() }  // forces 0xD? len-escape
        val obj = mapOf("s" to longStr, "l" to bigList, "d" to bigData, "m" to bigDict)
        val bytes = Plist.write(obj)
        @Suppress("UNCHECKED_CAST")
        val back = Plist.read(bytes) as Map<String, Any?>
        assertEquals(longStr, back["s"])
        assertEquals(bigList, back["l"])
        assertTrue((back["d"] as ByteArray).contentEquals(bigData))
        assertEquals(bigDict, back["m"])
    }

    @Test fun roundTripDouble() {
        val bytes = Plist.write(mapOf("f" to 3.14))
        @Suppress("UNCHECKED_CAST")
        val back = Plist.read(bytes) as Map<String, Any?>
        assertEquals(3.14, back["f"] as Double, 1e-15)
    }

    @Test fun roundTripEmptyContainers() {
        val bytes = Plist.write(mapOf("e" to emptyList<Any?>(), "m" to emptyMap<String, Any?>(), "s" to ""))
        @Suppress("UNCHECKED_CAST")
        val back = Plist.read(bytes) as Map<String, Any?>
        assertEquals(emptyList<Any?>(), back["e"])
        assertEquals(emptyMap<String, Any?>(), back["m"])
        assertEquals("", back["s"])
    }
}
