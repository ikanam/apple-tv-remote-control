package dev.atvremote.app.swipe

import dev.atvremote.app.ui.remote.TouchpadGesture
import dev.atvremote.protocol.RemoteButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chaos / property sweep of the local TouchpadGesture pipeline over a wide
 * matrix of start point × speed × direction × distance.
 *
 * It does NOT hand-pick expected coordinates; it asserts the **invariants a
 * correct focus-navigation drag must satisfy** for every case, with the exact
 * failing spec in the message. The main remote UI maps drags to HID
 * DirectionalStep events, so a focus move cannot be reinterpreted by tvOS
 * touch inertia.
 */
class SwipeChaosTest {

    private val W = 700f
    private val H = 700f
    private val slop = 12f

    private data class Spec(
        val startFx: Float,   // start point as a fraction of W/H
        val startFy: Float,
        val dirX: Int,        // -1 / 0 / +1
        val dirY: Int,
        val distFrac: Float,  // travel as a fraction of W (may exceed 1 ⇒ off-pad)
        val steps: Int,       // samples (fewer + bigger = "fast")
        val dtMs: Long,       // ms between samples (smaller = "fast", throttled)
    ) {
        val label get() =
            "start=(${startFx},${startFy}) dir=($dirX,$dirY) dist=${distFrac}W " +
                "steps=$steps dt=${dtMs}ms"
    }

    private data class Result(
        val events: List<TouchEvent>,
        val downX: Float, val downY: Float,
        val upX: Float, val upY: Float,
    )

    private fun run(s: Spec): Result {
        val g = TouchpadGesture(W, H, slop, SwipeTuning.DEFAULT.dragStepFraction)
        val sx = s.startFx * W
        val sy = s.startFy * H
        val ex = sx + s.dirX * s.distFrac * W
        val ey = sy + s.dirY * s.distFrac * H
        val out = ArrayList<TouchEvent>()
        fun apply(o: TouchpadGesture.Outcome) { out += o.events }

        apply(g.onDown(sx, sy, 0L))
        var px = sx; var py = sy
        for (i in 1..s.steps) {
            val f = i.toFloat() / s.steps
            val x = sx + (ex - sx) * f
            val y = sy + (ey - sy) * f
            apply(g.onMove(x, y, x - px, y - py, i * s.dtMs))
            px = x; py = y
        }
        apply(g.onUp(ex, ey, s.steps * s.dtMs + s.dtMs))

        return Result(out, sx, sy, ex, ey)
    }

    private fun Spec.expectedButton(): RemoteButton =
        if (kotlin.math.abs(dirX) >= kotlin.math.abs(dirY)) {
            if (dirX >= 0) RemoteButton.Right else RemoteButton.Left
        } else {
            if (dirY >= 0) RemoteButton.Down else RemoteButton.Up
        }

    private fun specs(): List<Spec> {
        val starts = listOf(
            0.50f to 0.50f, // centre
            0.25f to 0.50f, 0.75f to 0.50f,
            0.50f to 0.25f, 0.50f to 0.75f,
            0.30f to 0.30f, 0.70f to 0.70f,
            0.85f to 0.50f, 0.50f to 0.85f,
            0.95f to 0.50f, 0.05f to 0.50f, // edge-start swipes must still step
        )
        val dirs = listOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, -1 to -1, 1 to -1, -1 to 1,
        )
        val dists = listOf(0.15f, 0.45f, 0.9f, 1.6f) // last ⇒ off-pad
        val speeds = listOf(
            20 to 16L, // slow / smooth
            6 to 4L,   // fast / throttled
            3 to 2L,   // very fast (few huge jumps)
        )
        val out = ArrayList<Spec>()
        for ((fx, fy) in starts)
            for ((dx, dy) in dirs)
                for (d in dists)
                    for ((st, dt) in speeds)
                        out += Spec(fx, fy, dx, dy, d, st, dt)
        return out
    }


    @Test fun chaosSweepEverySwipeMatchesExpectation() {
        val specs = specs()
        for (s in specs) {
            val r = run(s)
            val steps = r.events.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button }
            assertTrue(steps.isNotEmpty(), "[${s.label}] drag emitted no HID steps: ${r.events}")
            assertTrue(
                steps.all { it == s.expectedButton() },
                "[${s.label}] drag emitted wrong direction; expected ${s.expectedButton()}, got $steps",
            )
        }
    }

    // INV — translation invariance: the SAME displacement/speed from two
    // different start points must emit the identical DirectionalStep sequence.
    @Test fun sameGestureFromDifferentStartsStreamsIdentically() {
        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to -1)
        val dists = listOf(0.3f, 0.8f, 1.4f)
        val speeds = listOf(18 to 16L, 5 to 3L)
        for ((dx, dy) in dirs) for (d in dists) for ((st, dt) in speeds) {
            val a = run(Spec(0.40f, 0.40f, dx, dy, d, st, dt))
            val b = run(Spec(0.60f, 0.55f, dx, dy, d, st, dt))
            assertEquals(
                a.events.filterIsInstance<TouchEvent.DirectionalStep>(),
                b.events.filterIsInstance<TouchEvent.DirectionalStep>(),
                "dir=($dx,$dy) dist=${d}W steps=$st: DirectionalStep sequence " +
                    "differs by start point — NOT translation-invariant",
            )
        }
    }
}
