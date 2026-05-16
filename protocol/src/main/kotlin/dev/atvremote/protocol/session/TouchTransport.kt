package dev.atvremote.protocol.session

import dev.atvremote.protocol.TouchPhase
import dev.atvremote.protocol.connection.CommandChannel

/**
 * Companion touch event transport (pyatv companion HID touch).
 *
 * Wire (source-verified against pyatv/protocols/companion/api.py):
 *   _touchStart content { "_height":1000.0, "_tFl":0, "_width":1000.0 }  (resets base ts)
 *               → `_touch_start` (L447) → `_send_command` (L450) → exchange (_t=2, awaits reply)
 *   _hidT       content { "_ns":<ns since start>, "_tFg":1, "_cx":x, "_cy":y, "_tPh":phase }
 *               → `hid_event` (L294) → `_send_event` (L300) → sendEvent (_t=1, fire-and-forget)
 *   _touchStop  content { "_i":1 }
 *               → `_touch_stop` (L456) → `_send_command` (L458) → exchange (_t=2, awaits reply)
 * x,y clamped to [0,1000]; ~16ms step interval for swipes.
 *
 * Note: pyatv's swipe() is time-duration-based (while current_time < end_time).
 * This implementation uses a fixed step count for testability with an injected
 * clock, preserving the same wire frame pattern (Press → Hold* → Release).
 */
internal class TouchTransport(
    private val ch: CommandChannel,
    private val nanoClock: () -> Long = { System.nanoTime() },
) {
    private var baseNs: Long = 0L

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 1000) 1000 else v

    suspend fun start() {
        baseNs = nanoClock()
        ch.exchange("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0))
    }

    suspend fun stop() {
        ch.exchange("_touchStop", mapOf("_i" to 1))
    }

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

    /** Interpolated swipe: Press at start, Hold for the middle samples, Release at end. */
    suspend fun swipe(
        x0: Int, y0: Int, x1: Int, y1: Int,
        steps: Int = 10,
        stepDelay: suspend () -> Unit = { kotlinx.coroutines.delay(16) },
    ) {
        require(steps >= 2) { "swipe needs >=2 steps" }
        start()
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
        stop()
    }
}
