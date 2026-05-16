package dev.atvremote.protocol.session

import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.TouchPhase
import dev.atvremote.protocol.connection.CommandChannel

/**
 * HID command helpers (pyatv companion HidCommand).
 *
 * Wire values confirmed from pyatv/protocols/companion/api.py lines 35-53:
 *   Select=6, Sleep=12, Wake=13.
 *
 * Press  = _hidC {_hBtS:1, _hidC:cmd}
 * Release = _hidC {_hBtS:2, _hidC:cmd}
 *
 * click() sequencing (pyatv api.py lines 356-375, pyatv-wins over plan description):
 *   SingleTap: press + ~20ms + release + ClickTouch  (loop runs 1×)
 *   DoubleTap: (press + ~20ms + release + ClickTouch) × 2  — ClickTouch is INSIDE the loop
 *   Hold:      press + ~1000ms + release + ClickTouch
 *
 * Note: the original plan description said Hold has "no trailing Click touch" — that is
 * incorrect. pyatv sends hid_event(Click) after Hold too (api.py lines 373-375). pyatv wins.
 */
internal class HidCommands(
    private val ch: CommandChannel,
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

    /** Sends a _hidT click-touch event (x=1000, y=1000, phase=Click). */
    private suspend fun clickTouch() {
        ch.exchange(
            "_hidT",
            mapOf(
                "_ns" to 0L, "_tFg" to 1, "_cx" to 1000, "_cy" to 1000,
                "_tPh" to TouchPhase.Click.value,
            ),
        )
    }

    /**
     * Sends the appropriate HID sequence for [action] (pyatv api.py lines 356-375).
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
