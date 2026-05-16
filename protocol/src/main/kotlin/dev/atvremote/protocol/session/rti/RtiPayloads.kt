package dev.atvremote.protocol.session.rti

import dev.atvremote.protocol.session.Plist

/**
 * Builders for the `_tiC` `_tiD` RTI text-operation payloads (clear / input).
 *
 * **Faithful 1:1 port of pyatv
 * `pyatv/protocols/companion/plist_payloads/rti_text_operations.py`**
 * (`get_rti_clear_text_payload(session_uuid)` /
 * `get_rti_input_text_payload(session_uuid, text)`), the authoritative
 * reference (CLAUDE.md pyatv-wins rule). The plan referenced a single
 * `plist_payloads.py` building a `documentState → docSt →
 * contextBeforeInput` graph — that module and that path do **NOT** exist in
 * pyatv. The real `plist_payloads` is a *package*; the builder is
 * `rti_text_operations.py`, a **pre-encoded `RTITextOperations`
 * NSKeyedArchiver archive** (`$archiver = "RTIKeyedArchiver"`,
 * `$version = 100000`), `textOperations → { keyboardOutput,
 * targetSessionUUID, textToAssert }`. That is exactly the graph the real
 * tvOS-26.5 capture `text-set.json` carries and that `docs/PROTOCOL.md`
 * records (Task 14 verified). The plan's draft `RtiPayloads.kt`
 * (`documentState`/`docSt` shape, random-UUID default) is **discarded** — it
 * disagrees with both pyatv and the captured bytes; pyatv/captured-bytes win.
 *
 * pyatv hand-builds the `$objects` array with explicit `plistlib.UID`
 * indices (`sort_keys=False`, `FMT_BINARY`). We mirror the **exact same**
 * `$objects` index layout and `$top`/`$archiver`/`$version` envelope using
 * [Plist.Uid] references, so the decoded object graph
 * ([KeyedArchiver]-resolved) is identical to pyatv's. Raw bytes need not be
 * identical — bplist `$objects`/string ordering is implementation-defined and
 * tvOS resolves by `$top`/UID, exactly like our [KeyedArchiver]; the
 * conformance check is decoded-graph equality vs the real capture
 * (`RtiPayloadsGoldenTest`).
 *
 * `sessionUuid` is the **raw 16 UUID bytes** (pyatv's `session_uuid: bytes`),
 * obtained by the caller from the `_tiStart` response `_tiD` via
 * [KeyedArchiver] (`sessionUUID` path → bare 16-byte `$objects` data leaf in
 * the documentState blob). Here it is re-wrapped as an NSUUID
 * `{ NS.uuidbytes, $class }` — pyatv keeps that wrapper, it does NOT unwrap.
 *
 * pyatv `api.text_input_command` mapping (`companion/api.py`):
 *  - `text_set`    = clear ([clearText]) then input ([inputText])
 *  - `text_clear`  = clear ([clearText]) only
 *  - `text_append` = input ([inputText]) only
 * (the clear/append/set orchestration belongs to Task 16 `KeyboardController`;
 * this object only builds the two `_tiD` blobs, exactly pyatv's split.)
 *
 * `:protocol` is pure Kotlin/JVM; additive `session/rti/` helper — the locked
 * `Api.kt` surface is untouched.
 */
object RtiPayloads {

    private const val ARCHIVER = "RTIKeyedArchiver"
    private const val VERSION = 100_000L

    private fun classDict(name: String): Map<String, Any?> =
        mapOf("\$classname" to name, "\$classes" to listOf(name, "NSObject"))

    /**
     * Port of pyatv `get_rti_clear_text_payload(session_uuid)`.
     *
     * `$objects` (verbatim pyatv index layout):
     * ```
     * [0] "$null"
     * [1] { $class:UID(7), targetSessionUUID:UID(5),
     *       keyboardOutput:UID(2), textToAssert:UID(4) }   # RTITextOperations
     * [2] { $class:UID(3) }                                # TIKeyboardOutput (no insertion)
     * [3] { $classname:"TIKeyboardOutput", $classes:[...] }
     * [4] ""                                               # textToAssert value
     * [5] { NS.uuidbytes:<session_uuid>, $class:UID(6) }   # NSUUID
     * [6] { $classname:"NSUUID", $classes:[...] }
     * [7] { $classname:"RTITextOperations", $classes:[...] }
     * ```
     */
    fun clearText(sessionUuid: ByteArray): ByteArray {
        require(sessionUuid.size == 16) { "sessionUuid must be the 16-byte NSUUID; was ${sessionUuid.size}" }
        return Plist.write(
            mapOf(
                "\$version" to VERSION,
                "\$archiver" to ARCHIVER,
                "\$top" to mapOf("textOperations" to Plist.Uid(1)),
                "\$objects" to listOf(
                    "\$null",
                    mapOf(
                        "\$class" to Plist.Uid(7),
                        "targetSessionUUID" to Plist.Uid(5),
                        "keyboardOutput" to Plist.Uid(2),
                        "textToAssert" to Plist.Uid(4),
                    ),
                    mapOf("\$class" to Plist.Uid(3)),
                    classDict("TIKeyboardOutput"),
                    "",
                    mapOf("NS.uuidbytes" to sessionUuid, "\$class" to Plist.Uid(6)),
                    classDict("NSUUID"),
                    classDict("RTITextOperations"),
                ),
            ),
        )
    }

    /**
     * Port of pyatv `get_rti_input_text_payload(session_uuid, text)`.
     *
     * `$objects` (verbatim pyatv index layout):
     * ```
     * [0] "$null"
     * [1] { keyboardOutput:UID(2), $class:UID(7),
     *       targetSessionUUID:UID(5) }                     # RTITextOperations (NO textToAssert)
     * [2] { insertionText:UID(3), $class:UID(4) }          # TIKeyboardOutput (with insertion)
     * [3] <text>                                           # insertionText value
     * [4] { $classname:"TIKeyboardOutput", $classes:[...] }
     * [5] { NS.uuidbytes:<session_uuid>, $class:UID(6) }   # NSUUID
     * [6] { $classname:"NSUUID", $classes:[...] }
     * [7] { $classname:"RTITextOperations", $classes:[...] }
     * ```
     */
    fun inputText(sessionUuid: ByteArray, text: String): ByteArray {
        require(sessionUuid.size == 16) { "sessionUuid must be the 16-byte NSUUID; was ${sessionUuid.size}" }
        return Plist.write(
            mapOf(
                "\$version" to VERSION,
                "\$archiver" to ARCHIVER,
                "\$top" to mapOf("textOperations" to Plist.Uid(1)),
                "\$objects" to listOf(
                    "\$null",
                    mapOf(
                        "keyboardOutput" to Plist.Uid(2),
                        "\$class" to Plist.Uid(7),
                        "targetSessionUUID" to Plist.Uid(5),
                    ),
                    mapOf(
                        "insertionText" to Plist.Uid(3),
                        "\$class" to Plist.Uid(4),
                    ),
                    text,
                    classDict("TIKeyboardOutput"),
                    mapOf("NS.uuidbytes" to sessionUuid, "\$class" to Plist.Uid(6)),
                    classDict("NSUUID"),
                    classDict("RTITextOperations"),
                ),
            ),
        )
    }
}
