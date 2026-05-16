package dev.atvremote.protocol.session

import dev.atvremote.protocol.KeyboardFocusState
import dev.atvremote.protocol.connection.SessionChannel
import dev.atvremote.protocol.session.rti.KeyedArchiver
import dev.atvremote.protocol.session.rti.RtiPayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * RTI keyboard text-input controller + focus-state flow.
 *
 * **Faithful 1:1 port of pyatv `CompanionAPI.text_input_command`
 * (`pyatv/protocols/companion/api.py:379-411`, fn `text_input_command`) plus
 * `_text_input_start` (`api.py:371-375`) and `_text_input_stop`
 * (`api.py:377-378`)** — the authoritative reference (CLAUDE.md pyatv-wins
 * rule). (Line ranges are current pyatv master as of 2026-05-16; cite by
 * function name too in case upstream drifts.)
 *
 * ## pyatv `text_input_command(text, clear_previous_input)` (verbatim flow)
 * ```python
 * await self._text_input_stop()                      # _send_command("_tiStop", {})
 * response = await self._text_input_start()           # _send_command("_tiStart", {}) + dispatch
 * ti_data = response.get("_c", {}).get("_tiD")
 * if ti_data is None: return None
 * session_uuid, current_text = keyed_archiver.read_archive_properties(
 *     ti_data, ["sessionUUID"], ["documentState","docSt","contextBeforeInput"])
 * session_uuid = cast(bytes, session_uuid)
 * if current_text is None: current_text = ""
 * if clear_previous_input:
 *     await self._send_event("_tiC", {"_tiV":1, "_tiD": get_rti_clear_text_payload(session_uuid)})
 *     current_text = ""
 * if text:
 *     await self._send_event("_tiC", {"_tiV":1, "_tiD": get_rti_input_text_payload(session_uuid, text)})
 *     current_text += text
 * return current_text
 * ```
 * Note `_tiStart`/`_tiStop` are `_send_command` (request/response =
 * [SessionChannel.exchange]); `_tiC` is `_send_event` (fire-and-forget =
 * [SessionChannel.sendEvent]) — same split as `_hidT`/`_interest`.
 *
 * ## Locked-API mapping (pyatv has NO text_get/set/clear/append)
 * pyatv exposes only the single `text_input_command` primitive. The locked
 * `Api.kt` surface (`textGet`/`textSet`/`textClear`/`textAppend`) maps onto it
 * with no behavioural change — each performs pyatv's `_tiStop`→`_tiStart`
 * restart ("so that we have up-to-date data", `text_input_command`,
 * api.py:379-411) then 0/1/2 `_tiC` events:
 *  - [textGet]    = `text_input_command("",   clear=false)` → restart only; returns current text
 *  - [textClear]  = `text_input_command("",   clear=true)`  → restart + clear `_tiC`
 *  - [textAppend] = `text_input_command(text, clear=false)` → restart + input `_tiC`
 *  - [textSet]    = `text_input_command(text, clear=true)`  → restart + clear `_tiC` + input `_tiC`
 *
 * ## Reconciled plan-draft inconsistencies (pyatv / captured-bytes win)
 *  1. **Channel type**: needs the inbound `events` stream for focus → takes
 *     [SessionChannel] (not the draft's `CommandChannel`, which lacks `events`).
 *     `CompanionSessionImpl`'s LOCKED `CommandChannel` ctor is unchanged; it
 *     `channel as SessionChannel` only inside the keyboard `by lazy` (the real
 *     `channel` is always `CompanionProtocol : SessionChannel`).
 *  2. **`RtiPayloads`**: real signatures are
 *     `clearText(sessionUuid: ByteArray)` / `inputText(sessionUuid, text)`
 *     (require 16 bytes). The session UUID is the **bare 16-byte `$objects`
 *     leaf** read from the `_tiStart` response `_tiD` via the single
 *     `KeyedArchiver.readProperties(tiD, …)` parse-once call — exactly pyatv
 *     `read_archive_properties(ti_data, ["sessionUUID"], …)`
 *     (`text_input_command`, api.py:379-411; Task-14 verified
 *     `keyed-archiver-tiD.json` graph: `$top.sessionUUID → obj[43] = <16 raw
 *     bytes>`, NOT an `NSUUID{NS.uuidbytes}` wrapper).
 *  3. **text-get path**: pyatv reads current text at
 *     `["documentState","docSt","contextBeforeInput"]` (`text_input_command`,
 *     api.py:379-411). That key is **absent** in the real capture (empty
 *     field) → pyatv's `if current_text is None: current_text = ""` yields
 *     `""`. We port the exact path AND the None→`""` fallback.
 *
 * ## Focus flow (focus-*state* projection is the project addition)
 * The underlying event routing **does** follow pyatv: `_text_input_start`
 * dispatches `_tiStart` (`api.py:374`), and pyatv's `CompanionKeyboard`
 * (`pyatv/protocols/companion/__init__.py:478-523`) listens to
 * `_tiStarted`/`_tiStopped`/`_tiStart` (registered `__init__.py:487-491`) and
 * routes all three through `_handle_text_input` (`__init__.py:493-500`), which
 * derives `Focused` iff `"_tiD" in data`. Our collector mirrors that exact
 * dispatch/`_tiD`-presence rule. What pyatv has no direct counterpart for is
 * the **focus-state projection itself** — exposing it as a long-lived
 * `keyboardFocus` `StateFlow` is a Plan-2 owner-specified surface (Api.kt + the
 * plan's "pyatv wire facts" line; pyatv pushes through a `state_dispatcher`
 * instead): focus = `_tiD` **present** in a `_tiStarted`/`_tiStopped`/
 * `_tiStart` inbound event ⇒ [KeyboardFocusState.Focused], absent ⇒
 * [KeyboardFocusState.Unfocused]. Honest framing recorded in
 * `docs/PROTOCOL.md`; the wire identifiers/semantics follow pyatv's dispatch
 * and the owner-specified plan wire-facts.
 *
 * `:protocol` is pure Kotlin/JVM; additive `session/` controller mirroring the
 * existing `TouchTransport`/`HidCommands`/`EventSubscriptions` pattern. The
 * locked `Api.kt` surface is untouched.
 */
internal class KeyboardController(
    private val ch: SessionChannel,
    scope: CoroutineScope,
) {
    private val _focus = MutableStateFlow(KeyboardFocusState.Unfocused)
    val focus: StateFlow<KeyboardFocusState> = _focus.asStateFlow()

    private companion object {
        /** Inbound events whose `_tiD` presence drives keyboard focus. */
        val FOCUS_EVENTS = setOf("_tiStarted", "_tiStopped", "_tiStart")
    }

    init {
        scope.launch {
            ch.events.collect { (name, content) ->
                if (name in FOCUS_EVENTS) {
                    _focus.value =
                        if (content.containsKey("_tiD")) KeyboardFocusState.Focused
                        else KeyboardFocusState.Unfocused
                }
            }
        }
    }

    /**
     * Port of pyatv `text_input_command(text, clear_previous_input)`
     * (`text_input_command`, api.py:379-411). Restarts the RTI session
     * (`_tiStop`→`_tiStart`),
     * extracts the session UUID + current text from the `_tiStart` response
     * `_tiD`, then emits 0–2 `_tiC` events. Returns the resulting current text
     * (pyatv's `current_text`; `""` when the `_tiStart` response has no
     * `_tiD`, mirroring pyatv's early `return None` collapsed to the locked
     * non-null `String` API — an empty field is `""` either way).
     */
    private suspend fun textInputCommand(text: String, clearPreviousInput: Boolean): String {
        // pyatv: restart the text input session so we have up-to-date data.
        ch.exchange("_tiStop", emptyMap())
        val response = ch.exchange("_tiStart", emptyMap())

        @Suppress("UNCHECKED_CAST")
        val tiData = (response["_c"] as? Map<String, Any?>)?.get("_tiD") as? ByteArray
            ?: return "" // pyatv `if ti_data is None: return None` (→ "" for non-null API)

        // pyatv read_archive_properties(ti_data, ["sessionUUID"],
        //   ["documentState","docSt","contextBeforeInput"]) — ONE parse, both
        //   paths (pyatv makes the single variadic call; KeyedArchiver's 1:1
        //   port parses the bplist once, then maps each path — no double parse).
        val (uuidProp, textProp) = KeyedArchiver.readProperties(
            tiData,
            listOf("sessionUUID"),
            listOf("documentState", "docSt", "contextBeforeInput"),
        )
        // Deliberate safer-than-pyatv divergence (same discipline as the
        // documentState-None fallback below and KeyedArchiver.follow's
        // non-dict→null guard): pyatv does `session_uuid = cast(bytes, …)`,
        // which would raise a TypeError on a missing/non-bytes UUID; we
        // defensively return "" instead (no session UUID → cannot build the
        // _tiC payloads). Documented pyatv-wins exception, not parity.
        val sessionUuid = uuidProp as? ByteArray
            ?: return ""
        var currentText =
            (textProp as? String) ?: "" // pyatv `if current_text is None: current_text = ""`

        if (clearPreviousInput) {
            ch.sendEvent("_tiC", mapOf("_tiV" to 1, "_tiD" to RtiPayloads.clearText(sessionUuid)))
            currentText = ""
        }
        if (text.isNotEmpty()) {
            ch.sendEvent(
                "_tiC",
                mapOf("_tiV" to 1, "_tiD" to RtiPayloads.inputText(sessionUuid, text)),
            )
            currentText += text
        }
        return currentText
    }

    /** Restart the RTI session and return the field's current text (pyatv:
     *  `text_input_command("", clear_previous_input=false)`). */
    suspend fun textGet(): String = textInputCommand("", clearPreviousInput = false)

    /** Clear then set: pyatv `text_input_command(text, clear_previous_input=true)`. */
    suspend fun textSet(text: String) {
        textInputCommand(text, clearPreviousInput = true)
    }

    /** Clear only: pyatv `text_input_command("", clear_previous_input=true)`. */
    suspend fun textClear() {
        textInputCommand("", clearPreviousInput = true)
    }

    /** Append only: pyatv `text_input_command(text, clear_previous_input=false)`. */
    suspend fun textAppend(text: String) {
        textInputCommand(text, clearPreviousInput = false)
    }
}
