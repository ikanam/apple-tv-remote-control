package dev.atvremote.protocol.session.rti

import dev.atvremote.protocol.goldentrace.GoldenTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KeyedArchiver` validated against the **real tvOS-26.5 RTIKeyedArchiver
 * blobs** captured in `keyed-archiver-tiD.json` / `text-set.json` (cc2aa10).
 *
 * pyatv-wins reconciliation (CLAUDE.md rule):
 *  - The port target is pyatv `companion/keyed_archiver.py`
 *    `read_archive_properties`. It is a **lazy path-follower**: walk `$top` by
 *    successive keys; whenever the current element is a `CF$UID`, dereference
 *    it once via `$objects[idx]` and continue. It does NOT collapse
 *    `NS.keys`/`NS.objects`/`NS.string`/`NS.uuidbytes`, and does NOT strip
 *    `$class`. Our port mirrors exactly that (the plan's draft eager
 *    NS.*-resolver / `$class`-stripper diverges from pyatv → not ported).
 *  - The plan's draft test path `documentState → docSt → contextBeforeInput`
 *    does NOT exist in the real capture (App Store search field, empty). The
 *    real graph is asserted below; verified path recorded in docs/PROTOCOL.md.
 */
class KeyedArchiverTest {

    private fun tiDBlob(): ByteArray {
        val gt = GoldenTrace.load("keyed-archiver-tiD.json")
        // _tiStart response is inDecoded()[0]; loader rewrote the hex: leaf to ByteArray.
        @Suppress("UNCHECKED_CAST")
        val c = gt.inDecoded()[0]["_c"] as Map<String, Any?>
        return c["_tiD"] as ByteArray
    }

    private fun textOpsBlob(stepInsert: Boolean): ByteArray {
        val gt = GoldenTrace.load("text-set.json")
        // text-set has two out _tiC steps: [0]=clear (textToAssert ""), [1]=insert "HelloWorld".
        @Suppress("UNCHECKED_CAST")
        val c = gt.outDecoded()[if (stepInsert) 1 else 0]["_c"] as Map<String, Any?>
        return c["_tiD"] as ByteArray
    }

    @Test fun decodesRealTiDDocumentStateAndSessionUuid() {
        val blob = tiDBlob()

        // $top of the real blob: documentState / documentTraits / sessionUUID.
        // documentState → obj[1] = { docSt, originatedFromSource, updateMask, $class }
        val docState = KeyedArchiver.readProperty(blob, "documentState")
        assertTrue(docState is Map<*, *>, "documentState resolves to a dict, was $docState")
        val ds = docState as Map<*, *>
        // pyatv does NOT strip $class — it must still be present (a CF$UID).
        assertTrue(ds.containsKey("\$class"), "resolved object keeps \$class (pyatv-faithful)")
        assertEquals(false, ds["originatedFromSource"])
        assertEquals(0L, (ds["updateMask"] as Number).toLong())

        // documentState → docSt → obj[2] = { $class } (TIDocumentState; empty field, no text).
        val docSt = KeyedArchiver.readProperty(blob, "documentState", "docSt")
        assertTrue(docSt is Map<*, *>, "docSt resolves to a dict, was $docSt")

        // sessionUUID → obj[43] = raw 16 UUID bytes (stored directly in $objects here,
        // NOT an NSUUID{NS.uuidbytes} wrapper — that wrapper appears in text-set).
        val sessionUuid = KeyedArchiver.readProperty(blob, "sessionUUID")
        assertNotNull(sessionUuid, "sessionUUID must resolve")
        assertTrue(sessionUuid is ByteArray, "sessionUUID is raw 16 bytes, was ${sessionUuid!!::class}")
        assertEquals(16, (sessionUuid as ByteArray).size)

        // documentTraits → obj[5] = RTIDocumentTraits; bId/app/prompt each a CF$UID
        // dereferenced one hop into a $objects string (real captured values).
        val bId = KeyedArchiver.readProperty(blob, "documentTraits", "bId")
        assertEquals("com.wuziqi.SenPlayer", bId) // obj[5].bId=UID(7) → obj[7]
        val app = KeyedArchiver.readProperty(blob, "documentTraits", "app")
        assertEquals("SenPlayer", app)            // obj[5].app=UID(8) → obj[8]
        val prompt = KeyedArchiver.readProperty(blob, "documentTraits", "prompt")
        assertEquals("搜索", prompt)               // obj[5].prompt=UID(9) → obj[9], UTF-16

        // Missing key → null (pyatv catches KeyError/IndexError and yields None).
        assertNull(KeyedArchiver.readProperty(blob, "documentState", "docSt", "contextBeforeInput"))
        assertNull(KeyedArchiver.readProperty(blob, "noSuchKey"))
    }

    @Test fun decodesTextOperationsBlobs() {
        // text-set #1 (clear): $top.textOperations → obj[1]
        //   { keyboardOutput, targetSessionUUID, textToAssert:"", $class }
        val clear = textOpsBlob(stepInsert = false)
        assertEquals("", KeyedArchiver.readProperty(clear, "textOperations", "textToAssert"))
        // targetSessionUUID → obj[5] = NSUUID { NS.uuidbytes:<16B>, $class }
        val uuidObj = KeyedArchiver.readProperty(clear, "textOperations", "targetSessionUUID")
        assertTrue(uuidObj is Map<*, *>, "targetSessionUUID resolves to NSUUID dict (pyatv keeps wrapper)")
        val ub = (uuidObj as Map<*, *>)["NS.uuidbytes"]
        assertTrue(ub is ByteArray && ub.size == 16, "NS.uuidbytes is 16 bytes")

        // text-set #2 (insert): textOperations → keyboardOutput → insertionText = "HelloWorld"
        val insert = textOpsBlob(stepInsert = true)
        assertEquals(
            "HelloWorld",
            KeyedArchiver.readProperty(insert, "textOperations", "keyboardOutput", "insertionText"),
        )
    }

    @Test fun readPropertiesResolvesMultiplePathsLikePyatv() {
        // Mirrors pyatv read_archive_properties(*paths) -> tuple, including None on miss.
        val blob = tiDBlob()
        val (app, missing, uuid) = KeyedArchiver.readProperties(
            blob,
            listOf("documentTraits", "app"),
            listOf("documentTraits", "nope"),
            listOf("sessionUUID"),
        )
        assertEquals("SenPlayer", app)
        assertNull(missing)
        assertTrue(uuid is ByteArray && uuid.size == 16)
    }
}
