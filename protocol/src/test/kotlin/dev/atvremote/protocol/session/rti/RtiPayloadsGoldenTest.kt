package dev.atvremote.protocol.session.rti

import dev.atvremote.protocol.goldentrace.GoldenTrace
import dev.atvremote.protocol.session.Plist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RtiPayloads` golden-validated against the **real tvOS-26.5 RTIKeyedArchiver
 * `textOperations` blobs** captured in `text-set.json` (cc2aa10), the ultimate
 * authority (CLAUDE.md pyatv-wins / captured-bytes-win rule).
 *
 * Port target (read line-by-line — Step 0): pyatv
 * `pyatv/protocols/companion/plist_payloads/rti_text_operations.py`
 * (`get_rti_clear_text_payload(session_uuid)` /
 * `get_rti_input_text_payload(session_uuid, text)`). The plan referenced a
 * single `plist_payloads.py` building a `documentState → docSt →
 * contextBeforeInput` graph — that module/path does **NOT** exist. pyatv's
 * real `plist_payloads` is a package; the payload builder is
 * `rti_text_operations.py`, a pre-encoded `RTITextOperations` archive
 * (`textOperations → {keyboardOutput, targetSessionUUID, textToAssert}`),
 * exactly the graph `text-set.json` / `docs/PROTOCOL.md` record. The plan's
 * draft test (`GoldenTrace.loadRaw`/`getString`/`path`, the `documentState`
 * path) is discarded; this test asserts decoded-graph equality on the
 * meaningful fields against the captured blobs (raw-byte equality is NOT
 * required — archiver `$objects` ordering is implementation-defined; the
 * plan's own comment says decoded-graph equality is the conformance check).
 *
 * Fixture: `text-set.json` is `mode:"realDevice"`; loaded via
 * `GoldenTrace.load(...).outDecoded()` (loader rewrites the `hex:` `_tiD`
 * leaf to `ByteArray`). `outDecoded()[0]` = clear blob, `outDecoded()[1]` =
 * insert "HelloWorld" blob. The captured `targetSessionUUID` 16 bytes are
 * extracted from the capture and fed back into the builder so the comparison
 * is deterministic.
 */
class RtiPayloadsGoldenTest {

    private fun capturedTiD(insert: Boolean): ByteArray {
        val gt = GoldenTrace.load("text-set.json")
        @Suppress("UNCHECKED_CAST")
        val c = gt.outDecoded()[if (insert) 1 else 0]["_c"] as Map<String, Any?>
        return c["_tiD"] as ByteArray
    }

    /** The 16 raw UUID bytes the real capture put in NSUUID `NS.uuidbytes`. */
    private fun capturedSessionUuid(): ByteArray {
        val uuidObj = KeyedArchiver.readProperty(
            capturedTiD(insert = false), "textOperations", "targetSessionUUID",
        ) as Map<*, *>
        return uuidObj["NS.uuidbytes"] as ByteArray
    }

    @Test fun clearPayloadDecodesLikePyatvCapture() {
        val sid = capturedSessionUuid()
        val expected = capturedTiD(insert = false)
        val ours = RtiPayloads.clearText(sid)

        // bplist00 NSKeyedArchiver envelope.
        assertEquals("bplist00", ours.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertNotNull(Plist.read(ours)) // structurally well-formed bplist

        // textToAssert == "" (clear), no insertionText, NSUUID wrapper present.
        assertEquals(
            "" /* captured */,
            KeyedArchiver.readProperty(expected, "textOperations", "textToAssert"),
        )
        assertEquals(
            KeyedArchiver.readProperty(expected, "textOperations", "textToAssert"),
            KeyedArchiver.readProperty(ours, "textOperations", "textToAssert"),
        )
        assertNull(
            KeyedArchiver.readProperty(ours, "textOperations", "keyboardOutput", "insertionText"),
            "clear payload must NOT carry insertionText",
        )
        assertSessionUuidWrapperMatches(expected, ours, sid)
        assertClassChain(ours, "textOperations", expected = "RTITextOperations")
    }

    @Test fun inputPayloadDecodesLikePyatvCapture() {
        val sid = capturedSessionUuid()
        val expected = capturedTiD(insert = true)
        val ours = RtiPayloads.inputText(sid, "HelloWorld")

        assertEquals("bplist00", ours.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertNotNull(Plist.read(ours)) // structurally well-formed bplist

        // keyboardOutput.insertionText == captured "HelloWorld" (ASCII).
        assertEquals(
            "HelloWorld",
            KeyedArchiver.readProperty(expected, "textOperations", "keyboardOutput", "insertionText"),
        )
        assertEquals(
            KeyedArchiver.readProperty(expected, "textOperations", "keyboardOutput", "insertionText"),
            KeyedArchiver.readProperty(ours, "textOperations", "keyboardOutput", "insertionText"),
        )
        // input payload carries NO textToAssert (pyatv get_rti_input_text_payload).
        assertNull(
            KeyedArchiver.readProperty(ours, "textOperations", "textToAssert"),
            "input payload must NOT carry textToAssert",
        )
        assertSessionUuidWrapperMatches(expected, ours, sid)
        assertClassChain(ours, "textOperations", expected = "RTITextOperations")
    }

    @Test fun inputPayloadRoundTripsNonAsciiText() {
        // Faithful: pyatv writes `text` verbatim into $objects[3]. Round-trips
        // (UTF-16 path in Plist for non-ASCII).
        val sid = capturedSessionUuid()
        val ours = RtiPayloads.inputText(sid, "héllo 世界")
        assertEquals(
            "héllo 世界",
            KeyedArchiver.readProperty(ours, "textOperations", "keyboardOutput", "insertionText"),
        )
    }

    private fun assertSessionUuidWrapperMatches(expected: ByteArray, ours: ByteArray, sid: ByteArray) {
        val capUuid = KeyedArchiver.readProperty(expected, "textOperations", "targetSessionUUID")
        val ourUuid = KeyedArchiver.readProperty(ours, "textOperations", "targetSessionUUID")
        assertTrue(capUuid is Map<*, *> && ourUuid is Map<*, *>, "targetSessionUUID is an NSUUID dict (pyatv keeps wrapper)")
        val capBytes = (capUuid as Map<*, *>)["NS.uuidbytes"] as ByteArray
        val ourBytes = (ourUuid as Map<*, *>)["NS.uuidbytes"] as ByteArray
        assertTrue(capBytes.contentEquals(sid), "captured NS.uuidbytes == extracted session id")
        assertTrue(ourBytes.contentEquals(sid), "our NS.uuidbytes == fed-in session id")
        // NSUUID $class chain present (pyatv-faithful — KeyedArchiver does not
        // strip $class; the inner $class is itself a CF$UID, so resolve it via
        // the one-hop-per-key path resolver exactly like pyatv would).
        assertClassChain(ours, "textOperations", "targetSessionUUID", expected = "NSUUID")
    }

    private fun assertClassChain(blob: ByteArray, vararg path: String, expected: String) {
        @Suppress("UNCHECKED_CAST")
        val cls = KeyedArchiver.readProperty(blob, *path, "\$class") as Map<String, Any?>
        assertEquals(expected, cls["\$classname"])
        assertEquals(listOf(expected, "NSObject"), cls["\$classes"])
    }
}
