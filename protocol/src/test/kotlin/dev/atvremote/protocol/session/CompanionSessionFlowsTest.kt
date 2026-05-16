package dev.atvremote.protocol.session

import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.KeyboardFocusState
import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.PowerStatus
import dev.atvremote.protocol.TouchPhase
import dev.atvremote.protocol.goldentrace.GoldenTrace
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan-2 Task 19 — zero-stubs / flows-wiring gate.
 *
 * Asserts the assembled [CompanionSessionImpl] (1) has NO remaining Task-1
 * `NotImplementedError` stub on ANY Plan-2 member and (2) exposes both
 * `StateFlow`s correctly. EVERY Plan-2 `CompanionSession` member is exercised
 * end-to-end here so a regressed stub on any of them fails THIS gate (not only
 * a sibling unit suite):
 *  - touch / click / media / power  — delegate to their merged controllers;
 *  - powerStatus / listApps         — exchange + response-decode;
 *  - textGet / textSet / textClear / textAppend — the 4 keyboard members,
 *    driven through the real merged [KeyboardController] against the committed
 *    real tvOS-26.5 `_tiStart` capture (so a future keyboard `NotImplementedError`
 *    regression — or a broken `_tiStart`/`_tiD` decode — fails HERE, not just
 *    `KeyboardControllerTest`);
 *  - keyboardFocus / connectionState — both `StateFlow`s.
 * Keyboard coverage is NOT delegated elsewhere — it is asserted in this gate.
 *
 * pyatv-wins reconciliation of the plan DRAFT (CLAUDE.md discipline — the
 * synthetic draft is not authority; the merged pyatv-faithful controllers
 * are):
 *
 *  - `powerStatus()` draft used `mapOf("_c" to 0x03)` (bare int). The merged
 *    [PowerController.status] (PowerController.kt:40-48, pyatv
 *    `fetch_attention_state` api.py:437-445) reads `resp["_c"]` as a **Map**
 *    and pulls `"state"` from it; a bare int `_c` casts to `null` → `raw=-1`
 *    → `PowerStatus.Unknown`, NOT `On`. Reconciled to the real shape
 *    `{"_c": {"state": 0x03}}` (0x03 = Awake → On per
 *    `_system_status_to_power_state`).
 *  - `listApps()` uses `{"_c": {}}`: [AppsController.listApps]
 *    (AppsController.kt:28-32) does `resp["_c"] as? Map<*,*> ?: emptyList()`
 *    then maps `c.entries` — an empty `_c` map yields an empty app list
 *    (a genuine "device returned no launchable apps" response, not an
 *    error/absent-`_c` shortcut). Verified against the real merged controller.
 *  - keyboard members go through the merged [KeyboardController], a 1:1 port
 *    of pyatv `text_input_command`: each does the `_tiStop`→`_tiStart` restart
 *    (both `exchange`) then 0–2 `_tiC` `sendEvent`s, reading the 16-byte
 *    `sessionUUID` from the `_tiStart` *response* `_tiD`. The stub returns the
 *    REAL committed `keyed-archiver-tiD.json` capture (Task-14 verified graph)
 *    for `_tiStart` and an empty map for `_tiStop` — no fabricated blob; same
 *    `GoldenTrace.load(...).inDecoded()` helper shape as `KeyboardControllerTest`.
 *    That empty App-Store-search-field capture legitimately yields `textGet()`
 *    == `""` (pyatv `if current_text is None: current_text = ""`).
 *
 * The `FakeProtocol.onExchange` lambda is shared/mutable; the stub is set
 * **immediately before** the call group whose response it must shape, so
 * earlier fire-and-forget / reply-ignoring calls (`touch` = `_hidT`
 * sendEvent; `click`/`power`/`media` = `exchange` whose reply is discarded)
 * run safely under the default `{ _, _ -> emptyMap() }` and never block
 * awaiting a reply tvOS never sends (per the pyatv-wins `_hidT`-is-sendEvent
 * audit). The keyboard group is run BEFORE `onExchange` is overwritten with
 * the FetchAttentionState/listApps stubs so the `_tiStart` stub is the active
 * one for the four keyboard calls.
 */
class CompanionSessionFlowsTest {

    /** Real tvOS-26.5 `_tiStart` response capture (App Store search field,
     *  empty) — same committed golden + helper shape as `KeyboardControllerTest`. */
    private fun realTiStartResponse(): Map<String, Any?> {
        val gt = GoldenTrace.load("keyed-archiver-tiD.json")
        @Suppress("UNCHECKED_CAST")
        return mapOf("_c" to (gt.inDecoded()[0]["_c"] as Map<String, Any?>))
    }

    @Test
    fun noStubsAndFlowsExposed() = runTest {
        val fake = FakeProtocol()
        val s = CompanionSessionImpl(fake)

        // _hidT is fire-and-forget sendEvent (pyatv-wins: NOT an exchange);
        // these complete without awaiting any reply.
        s.touch(0, 0, TouchPhase.Press)
        // click = _hidC exchange ×2 (reply discarded) + _hidT sendEvent;
        // sleepMs(20) delay auto-advanced by runTest virtual time.
        s.click(InputAction.SingleTap)
        // media = _mcc exchange (reply discarded).
        s.media(MediaCommand.Play)
        // power(true) = _hidC exchange (reply discarded).
        s.power(true)

        // ---- Keyboard group: all 4 members exercised end-to-end ----
        // Stub set IMMEDIATELY before the keyboard calls (shared-mutable
        // onExchange discipline): real captured `_tiStart` _tiD, empty `_tiStop`.
        fake.onExchange = { name, _ ->
            if (name == "_tiStart") realTiStartResponse() else emptyMap()
        }
        // textGet: _tiStop→_tiStart restart only (no _tiC); the empty-field
        // real capture yields "" (pyatv None→"" fallback). Proves textGet +
        // the _tiStart/_tiD decode are stub-free end-to-end.
        assertEquals("", s.textGet())
        // textClear/textAppend/textSet each restart then emit 1/1/2 `_tiC`
        // sendEvents; assert they complete without throwing AND produced the
        // expected `_tiC` sentEvents (mirrors KeyboardControllerTest, concise —
        // this is a gate, not a re-test of T16).
        fake.sentEvents.clear()
        s.textClear()
        assertEquals(listOf("_tiC"), fake.sentEvents.map { it.first })
        fake.sentEvents.clear()
        s.textAppend("hi")
        assertEquals(listOf("_tiC"), fake.sentEvents.map { it.first })
        fake.sentEvents.clear()
        s.textSet("yo")
        assertEquals(listOf("_tiC", "_tiC"), fake.sentEvents.map { it.first })

        // Reconciled to the REAL merged PowerController response shape
        // (_c is a Map keyed by "state"; 0x03 = Awake → On). The plan's
        // stale bare-int `mapOf("_c" to 0x03)` would yield Unknown.
        fake.onExchange = { _, _ -> mapOf("_c" to mapOf("state" to 0x03)) }
        assertEquals(PowerStatus.On, s.powerStatus())

        // Empty `_c` map = device returned zero launchable apps (real merged
        // AppsController maps c.entries → empty list).
        fake.onExchange = { _, _ -> mapOf("_c" to emptyMap<String, Any?>()) }
        assertTrue(s.listApps().isEmpty())

        // Both StateFlows exposed; no _tiStarted emitted → focus Unfocused;
        // a standalone impl always reports Connected (real reconnection state
        // is owned by the ResilientSession wrapper).
        assertEquals(KeyboardFocusState.Unfocused, s.keyboardFocus.value)
        assertEquals(ConnectionState.Connected, s.connectionState.value)
    }
}
