package dev.atvremote.protocol.session

import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.TouchPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TDD tests for [HidCommands.click].
 *
 * pyatv reference: pyatv/protocols/companion/api.py, lines 356-375
 * (commit: master as of 2026-05-16).
 *
 * IMPORTANT — pyatv-wins corrections vs the original plan description:
 *  - DoubleTap: ClickTouch is inside the loop, so each tap is
 *    press + 20ms + release + ClickTouch. DoubleTap yields 2 ClickTouches.
 *  - Hold: pyatv DOES send a trailing ClickTouch (plan incorrectly said
 *    "no trailing Click touch").
 *
 * HID values confirmed from pyatv:
 *   Select=6, Sleep=12, Wake=13 (api.py lines 43/49/50)
 *   press = {_hBtS:1, _hidC:cmd}, release = {_hBtS:2, _hidC:cmd}
 *   TouchPhase.Click.value = 5 (Api.kt, sourced from pyatv const.py line 466)
 */
class HidClickTest {
    private fun seq(ex: List<Pair<String, Map<String, Any?>>>) =
        ex.map { it.first to (it.second["_hBtS"] ?: it.second["_tPh"]) }

    @Test fun singleTapIsPressReleaseThenClickTouch() = runTest {
        val fake = FakeProtocol()
        val h = HidCommands(fake) {}   // no-op delay
        h.click(InputAction.SingleTap)
        // pyatv api.py line 362-369: count=1, loop: press + 20ms + release + hid_event(Click)
        assertEquals(
            listOf("_hidC" to 1, "_hidC" to 2, "_hidT" to TouchPhase.Click.value),
            seq(fake.exchanges),
        )
        assertEquals(6, fake.exchanges[0].second["_hidC"]) // HidCommand.Select
        assertEquals(6, fake.exchanges[1].second["_hidC"])
    }

    @Test fun doubleTapPressReleasePlusClickTouchTwice() = runTest {
        val fake = FakeProtocol()
        val h = HidCommands(fake) {}
        h.click(InputAction.DoubleTap)
        // pyatv api.py line 362-369: count=2, loop runs twice:
        //   tap1: press + release + ClickTouch
        //   tap2: press + release + ClickTouch
        // (ClickTouch is INSIDE the loop — two ClickTouches total)
        assertEquals(
            listOf(
                "_hidC" to 1, "_hidC" to 2, "_hidT" to TouchPhase.Click.value,
                "_hidC" to 1, "_hidC" to 2, "_hidT" to TouchPhase.Click.value,
            ),
            seq(fake.exchanges),
        )
    }

    @Test fun holdIsPressLongDelayReleaseThenClickTouch() = runTest {
        val fake = FakeProtocol()
        var slept = 0L
        val h = HidCommands(fake) { ms -> slept += ms }
        h.click(InputAction.Hold)
        // pyatv api.py lines 371-375: press + sleep(1) + release + hid_event(Click)
        // (plan incorrectly said "no trailing Click touch"; pyatv wins)
        assertEquals(
            listOf("_hidC" to 1, "_hidC" to 2, "_hidT" to TouchPhase.Click.value),
            seq(fake.exchanges),
        )
        assertEquals(1000L, slept) // ~1s hold
    }
}
