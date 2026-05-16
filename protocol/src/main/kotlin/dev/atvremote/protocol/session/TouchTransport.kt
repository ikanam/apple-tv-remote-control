package dev.atvremote.protocol.session

import dev.atvremote.protocol.TouchPhase
import dev.atvremote.protocol.connection.CommandChannel

/**
 * Companion touch event transport (pyatv companion HID touch).
 *
 * pyatv reference (api.py, master 2026-05-16):
 *
 *   _touch_start (L447–454): sets `self._base_timestamp = time.time_ns()` (L449), then sends
 *                             the `_touchStart` command. Called ONCE in connect() (L154).
 *                             NOT called per gesture.
 *
 *   hid_event    (L294–309): `_ns = time.time_ns() - self._base_timestamp` (L303) — session-relative.
 *                             → `_send_event` (L300) → sendEvent (_t=1, fire-and-forget).
 *
 *   swipe        (L311–345): loop of hid_event() calls only — NO _touchStart/_touchStop per gesture.
 *
 *   _touch_stop  (L456–458): sent once at disconnect() (L122) — handled by session close, not here.
 *
 * [baseNs] is the connect-time `_touchStart` instant (injected from [SessionHandshake.touchBaseNs]),
 * not an internal mutable that start() resets. start()/stop() have been removed — they sent
 * per-gesture _touchStart/_touchStop which pyatv never does during a session.
 *
 * Wire (unchanged):
 *   _hidT content { "_ns":<ns since touchStart>, "_tFg":1, "_cx":x, "_cy":y, "_tPh":phase }
 *         → `hid_event` (L294) → `_send_event` (L300) → sendEvent (_t=1, fire-and-forget)
 *   x,y clamped to [0,1000]; ~16ms step interval for swipes.
 *
 * Note: pyatv's swipe() is time-duration-based (while current_time < end_time).
 * This implementation uses a fixed step count for testability with an injected
 * clock, preserving the same wire frame pattern (Press → Hold* → Release).
 */
internal class TouchTransport(
    private val ch: CommandChannel,
    private val baseNs: Long = 0L,
    private val nanoClock: () -> Long = { System.nanoTime() },
) {
    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 1000) 1000 else v

    suspend fun touch(x: Int, y: Int, phase: TouchPhase) {
        val ns = nanoClock() - baseNs
        ch.sendEvent(
            "_hidT",
            mapOf(
                "_ns" to ns,
                "_tFg" to 1,
                "_cx" to clamp(x),
                "_cy" to clamp(y),
                "_tPh" to phase.value,
            ),
        )
    }

    /** Interpolated swipe: Press at start, Hold for the middle samples, Release at end.
     *  Mirrors pyatv swipe() (api.py L311): purely hid_event calls — no _touchStart/_touchStop. */
    suspend fun swipe(
        x0: Int, y0: Int, x1: Int, y1: Int,
        steps: Int = 10,
        stepDelay: suspend () -> Unit = { kotlinx.coroutines.delay(16) },
    ) {
        require(steps >= 2) { "swipe needs >=2 steps" }
        for (i in 0 until steps) {
            val frac = i.toDouble() / (steps - 1)
            val x = Math.round(x0 + (x1 - x0) * frac).toInt()
            val y = Math.round(y0 + (y1 - y0) * frac).toInt()
            val phase = when (i) {
                0 -> TouchPhase.Press
                steps - 1 -> TouchPhase.Release
                else -> TouchPhase.Hold
            }
            touch(x, y, phase)
            if (i != steps - 1) stepDelay()
        }
    }
}
