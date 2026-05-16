package dev.atvremote.protocol.session

import dev.atvremote.protocol.KeyboardFocusState
import dev.atvremote.protocol.goldentrace.GoldenTrace
import dev.atvremote.protocol.session.rti.KeyedArchiver
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `KeyboardController` — faithful port of pyatv `CompanionAPI.text_input_command`
 * (`pyatv/protocols/companion/api.py:379-411`) + `_text_input_start`
 * (`api.py:371-375`) / `_text_input_stop` (`api.py:377-378`), reconciled to the
 * locked `Api.kt` 4-method keyboard surface
 * (`textGet`/`textSet`/`textClear`/`textAppend`) + the project's
 * `keyboardFocus` flow (event routing per pyatv `CompanionKeyboard`,
 * `__init__.py:478-523`). (Line ranges = pyatv master 2026-05-16.)
 *
 * **Three plan-draft inconsistencies reconciled (pyatv/captured-bytes win,
 * CLAUDE.md rule):**
 *
 *  1. Channel type — draft said `CommandChannel` (has no `events`); the real
 *     inbound event stream is on `SessionChannel` (`FakeProtocol : SessionChannel`).
 *     `KeyboardController` takes `SessionChannel`; `CompanionSessionImpl` keeps
 *     its LOCKED `CommandChannel` ctor and only `channel as SessionChannel`
 *     inside the keyboard `by lazy` (production `channel` is always
 *     `CompanionProtocol : SessionChannel`; `ButtonTest`/`SessionHandshakeTest`
 *     `CommandChannel`-only doubles never reach the keyboard members).
 *
 *  2. `RtiPayloads` API — draft `clear()`/`inputText(text)` don't exist. Real
 *     `clearText(sessionUuid: ByteArray)` / `inputText(sessionUuid, text)`
 *     require the 16-byte session UUID. Per pyatv `text_input_command`
 *     (api.py:379-411), it is read from the `_tiStart` *response* `_tiD` via
 *     `read_archive_properties(ti_data, ["sessionUUID"], …)` — i.e. the single
 *     `KeyedArchiver.readProperties(tiD, …)` parse-once call → the bare
 *     16-byte `$objects` leaf (Task-14 verified graph; `keyed-archiver-tiD.json`).
 *
 *  3. text-get path — draft `documentState→docSt→contextBeforeInput` does not
 *     exist in the real capture. pyatv `text_input_command` (api.py:379-411)
 *     uses exactly that path but wraps it
 *     `if current_text is None: current_text = ""`. We port pyatv's
 *     path verbatim AND its None→"" fallback; the real captured `_tiStart`
 *     response (empty App Store search field) legitimately yields `""`.
 *
 * pyatv has **no** separate text_get/set/clear/append — only
 * `text_input_command(text, clear_previous_input)`. The 4 locked Api methods
 * map onto that single primitive (each does the pyatv `_tiStop`→`_tiStart`
 * restart "so that we have up-to-date data", then 0/1/2 `_tiC` events):
 *  - textGet()    = text_input_command("",  clear=false) → restart only, return current_text
 *  - textClear()  = text_input_command("",  clear=true)  → restart + 1 clear `_tiC`
 *  - textAppend() = text_input_command(t,   clear=false) → restart + 1 input `_tiC`
 *  - textSet()    = text_input_command(t,   clear=true)  → restart + clear `_tiC` + input `_tiC`
 */
class KeyboardControllerTest {

    /** Real tvOS-26.5 `_tiStart` response capture (App Store search field, empty). */
    private fun realTiStartResponse(): Map<String, Any?> {
        val gt = GoldenTrace.load("keyed-archiver-tiD.json")
        @Suppress("UNCHECKED_CAST")
        return mapOf("_c" to (gt.inDecoded()[0]["_c"] as Map<String, Any?>))
    }

    private fun realTiDBytes(): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val c = realTiStartResponse()["_c"] as Map<String, Any?>
        return c["_tiD"] as ByteArray
    }

    @Test fun textGetRestartsSessionAndReturnsCurrentTextFromRealTiD() = runTest {
        val fake = FakeProtocol()
        fake.onExchange = { name, _ ->
            if (name == "_tiStart") realTiStartResponse() else emptyMap()
        }
        val kb = KeyboardController(fake, backgroundScope)

        val got = kb.textGet()

        // pyatv restart: _tiStop then _tiStart exchanges (both _send_command).
        assertEquals(listOf("_tiStop", "_tiStart"), fake.exchanges.map { it.first })
        // No _tiC for a pure get (text="" and clear_previous_input=false).
        assertEquals(emptyList(), fake.sentEvents.map { it.first })
        // pyatv text path documentState→docSt→contextBeforeInput is absent in
        // the real capture (empty field) ⇒ pyatv None→"" fallback ⇒ "".
        assertEquals("", got)
    }

    @Test fun textSetRestartsThenClearsThenInputsViaTiC() = runTest {
        val fake = FakeProtocol()
        fake.onExchange = { name, _ ->
            if (name == "_tiStart") realTiStartResponse() else emptyMap()
        }
        val kb = KeyboardController(fake, backgroundScope)

        kb.textSet("Hi")

        // pyatv: _tiStop, _tiStart (exchanges), then clear + input _tiC events.
        assertEquals(listOf("_tiStop", "_tiStart"), fake.exchanges.map { it.first })
        assertEquals(listOf("_tiC", "_tiC"), fake.sentEvents.map { it.first })
        for ((_, content) in fake.sentEvents) {
            assertEquals(1, content["_tiV"])
            assertTrue(content["_tiD"] is ByteArray)
        }
        // First _tiC is the clear payload (textOperations.textToAssert == "");
        // second is the input payload (keyboardOutput.insertionText == "Hi"),
        // both carrying the 16-byte session UUID extracted from the real _tiD.
        val expectedUuid = KeyedArchiver.readProperty(realTiDBytes(), "sessionUUID") as ByteArray
        val clearD = fake.sentEvents[0].second["_tiD"] as ByteArray
        val inputD = fake.sentEvents[1].second["_tiD"] as ByteArray
        assertEquals("", KeyedArchiver.readProperty(clearD, "textOperations", "textToAssert"))
        assertEquals(
            "Hi",
            KeyedArchiver.readProperty(inputD, "textOperations", "keyboardOutput", "insertionText"),
        )
        val clearUuid = (KeyedArchiver.readProperty(clearD, "textOperations", "targetSessionUUID")
            as Map<*, *>)["NS.uuidbytes"] as ByteArray
        assertTrue(expectedUuid.contentEquals(clearUuid), "clear _tiD carries the real session UUID")
    }

    @Test fun textClearRestartsThenSendsOneClearTiC() = runTest {
        val fake = FakeProtocol()
        fake.onExchange = { name, _ -> if (name == "_tiStart") realTiStartResponse() else emptyMap() }
        KeyboardController(fake, backgroundScope).textClear()
        assertEquals(listOf("_tiStop", "_tiStart"), fake.exchanges.map { it.first })
        assertEquals(listOf("_tiC"), fake.sentEvents.map { it.first })
        val d = fake.sentEvents[0].second["_tiD"] as ByteArray
        // clear payload: textToAssert == "" present (RtiPayloads.clearText).
        assertEquals("", KeyedArchiver.readProperty(d, "textOperations", "textToAssert"))
    }

    @Test fun textAppendRestartsThenSendsOneInputTiC() = runTest {
        val fake = FakeProtocol()
        fake.onExchange = { name, _ -> if (name == "_tiStart") realTiStartResponse() else emptyMap() }
        KeyboardController(fake, backgroundScope).textAppend("more")
        assertEquals(listOf("_tiStop", "_tiStart"), fake.exchanges.map { it.first })
        assertEquals(listOf("_tiC"), fake.sentEvents.map { it.first })
        val d = fake.sentEvents[0].second["_tiD"] as ByteArray
        assertEquals(
            "more",
            KeyedArchiver.readProperty(d, "textOperations", "keyboardOutput", "insertionText"),
        )
    }

    @Test fun focusStateTracksTiDPresenceInEvents() = runTest {
        val fake = FakeProtocol()
        val kb = KeyboardController(fake, backgroundScope)
        assertEquals(KeyboardFocusState.Unfocused, kb.focus.value)

        // FakeProtocol.events is replay=1: a late collector sees only the LAST
        // emitted event. The controller's collector is launched in its init,
        // but runTest's StandardTestDispatcher schedules it lazily; yield()
        // lets it run and consume each emission before the next assertion.
        fake.emitEvent("_tiStarted", mapOf("_tiD" to byteArrayOf(1, 2, 3)))
        yield()
        assertEquals(KeyboardFocusState.Focused, kb.focus.value)

        fake.emitEvent("_tiStopped", emptyMap())
        yield()
        assertEquals(KeyboardFocusState.Unfocused, kb.focus.value)

        // A `_tiStart` event WITH `_tiD` re-focuses (pyatv dispatches `_tiStart`
        // with response `_c`; our wire-facts treat `_tiD` presence as focus).
        fake.emitEvent("_tiStart", mapOf("_tiD" to byteArrayOf(9)))
        yield()
        assertEquals(KeyboardFocusState.Focused, kb.focus.value)
    }

    @Test fun ignoresUnrelatedEventsForFocus() = runTest {
        val fake = FakeProtocol()
        val kb = KeyboardController(fake, backgroundScope)
        fake.emitEvent("SomethingElse", mapOf("_tiD" to byteArrayOf(1)))
        yield()
        assertEquals(KeyboardFocusState.Unfocused, kb.focus.value)
    }
}
