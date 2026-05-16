package dev.atvremote.protocol.session

import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.TouchPhase
import dev.atvremote.protocol.connection.CommandChannel

/**
 * HID command helpers (pyatv companion HidCommand).
 *
 * Wire values confirmed from pyatv/protocols/companion/api.py `HidCommand` enum (L35):
 *   Select=6 (L43), Sleep=12 (L49), Wake=13 (L50).
 *
 * Press  = _hidC {_hBtS:1, _hidC:cmd}   → `hid_command` (L288) → `_send_command` (L290) → exchange (_t=2)
 * Release = _hidC {_hBtS:2, _hidC:cmd}  → `hid_command` (L288) → `_send_command` (L290) → exchange (_t=2)
 *
 * click() sequencing (`click` api.py L356, body L356–376, pyatv-wins over plan description):
 *   SingleTap: press + ~20ms + release + ClickTouch  (loop runs 1×)
 *   DoubleTap: (press + ~20ms + release + ClickTouch) × 2  — ClickTouch is INSIDE the loop
 *   Hold:      press + ~1000ms + release + ClickTouch
 *
 * ClickTouch = _hidT { _ns:<live ns>, _tFg:1, _cx:1000, _cy:1000, _tPh:5 }
 *   → `hid_event` (L294) → `_send_event` (L300) → sendEvent (_t=1, fire-and-forget, pyatv-wins)
 *   _ns is a live monotonic ns from [nanoClock]; pyatv bases it on the connect-time
 *   _touchStart base_timestamp — value origin differs but it is a live increasing
 *   timestamp, not constant 0; not gesture-velocity-critical for a discrete Click.
 *
 * Note: the original plan description said Hold has "no trailing Click touch" — that is
 * incorrect. pyatv sends hid_event(Click) after Hold too (`click` L356 Hold branch L370–376). pyatv wins.
 * Plan erratum: Plan-2 Task 7 code block used ch.exchange("_hidT") and _ns:0L; superseded
 * by pyatv-wins — _hidT is sendEvent, _ns is live.
 */
internal class HidCommands(
    private val ch: CommandChannel,
    private val nanoClock: () -> Long = { System.nanoTime() },
    // sleepMs is last so test call sites like HidCommands(fake) {} or HidCommands(fake) { ms -> … }
    // bind the trailing lambda to this parameter; reordering would silently break those call sites.
    private val sleepMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    companion object {
        const val SELECT = 6
        const val SLEEP = 12
        const val WAKE = 13
    }

    suspend fun press(cmd: Int) =
        ch.exchange("_hidC", mapOf("_hBtS" to 1, "_hidC" to cmd)).let {}

    suspend fun release(cmd: Int) =
        ch.exchange("_hidC", mapOf("_hBtS" to 2, "_hidC" to cmd)).let {}

    /** Sends a _hidT click-touch event (x=1000, y=1000, phase=Click).
     *  Fire-and-forget via sendEvent (_t=1); pyatv `hid_event` (L294) → `_send_event` (L300).
     *  _ns is a live monotonic value from nanoClock() (not a constant 0). */
    private suspend fun clickTouch() {
        ch.sendEvent(
            "_hidT",
            mapOf(
                "_ns" to nanoClock(), "_tFg" to 1, "_cx" to 1000, "_cy" to 1000,
                "_tPh" to TouchPhase.Click.value,
            ),
        )
    }

    /**
     * Sends the appropriate HID sequence for [action] (pyatv `click` api.py L356, body L356–376).
     *
     * SingleTap / DoubleTap: for each tap — press + ~20ms + release + ClickTouch.
     * Hold: press + ~1000ms + release + ClickTouch.
     */
    suspend fun click(action: InputAction) {
        when (action) {
            InputAction.SingleTap, InputAction.DoubleTap -> {
                val count = if (action == InputAction.SingleTap) 1 else 2
                repeat(count) {
                    press(SELECT); sleepMs(20); release(SELECT); clickTouch()
                }
            }
            InputAction.Hold -> {
                press(SELECT); sleepMs(1000); release(SELECT); clickTouch()
            }
        }
    }
}
