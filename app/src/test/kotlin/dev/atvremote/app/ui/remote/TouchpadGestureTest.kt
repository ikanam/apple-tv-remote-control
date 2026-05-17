package dev.atvremote.app.ui.remote

import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the dual-path **no-double-fire** + **clean-termination** contract on
 * the pure [TouchpadGesture] reducer (the T2 review's C2/C3/I1/I2).
 *
 * The reducer is what [Touchpad]'s `awaitPointerEventScope` loop delegates to
 * after it latches the tracked pointer id (C2) and supplies monotonic
 * `uptimeMillis` (I2). Robolectric's `performTouchInput` can replay a single
 * pointer down/move/up but cannot faithfully synthesize a *second finger* or an
 * *ancestor-cancelled* gesture; per the task's stated fallback the C2/C3/I1
 * decisions are pinned here at the reducer instead, with the Compose wiring
 * (C1/I2 + tap-vs-drag) covered by [TouchpadZoneTest]'s `performTouchInput`
 * cases. The center of the 240×240 box is (120,120).
 */
class TouchpadGestureTest {

    private val W = 240f
    private val H = 240f
    // Use a generous slop so a deliberate "drag" test crosses it unambiguously
    // and a "tap" stays well under it.
    private val slop = 20f

    private fun engine() = SwipeEngine(SwipeTuning.DEFAULT, W, H)
    private fun gesture(e: SwipeEngine = engine()) = TouchpadGesture(e, W, H, slop)

    private fun List<TouchEvent>.releases() =
        filterIsInstance<TouchEvent.Move>().filter { it.phase == TouchPhase.Release }

    // --- I1: a discrete tap fires ONE direction and ZERO touch events ------

    @Test fun pureTapInZoneFiresOneDirectionAndNoTouchEvents() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        // Down near the top edge (above center) ⇒ Up zone, no movement.
        fun apply(o: TouchpadGesture.Outcome) {
            all += o.events
            o.direction?.let { dir = it }
        }
        apply(g.onDown(120f, 20f, 0L))
        apply(g.onUp(120f, 20f, 100L)) // < longPressMs 450, no travel ⇒ tap
        assertEquals(RemoteButton.Up, dir, "tap must fire exactly the zone dir")
        assertTrue(all.isEmpty(), "a tap must deliver ZERO TouchEvents, got $all")
    }

    @Test fun centerTapIsSelect() {
        val g = gesture()
        var dir: RemoteButton? = null
        g.onDown(120f, 120f, 0L)
        g.onUp(120f, 120f, 80L).direction?.let { dir = it }
        assertEquals(RemoteButton.Select, dir)
    }

    // --- I1: a SLOW in-zone press must NOT emit LongPress AND a zone -------

    @Test fun longInZonePressNoMoveFiresOnlyZoneNoLongPress() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 20f, 0L))
        // Several stationary "ticks" past longPressMs (450): the engine WOULD
        // emit a LongPress, but it must be buffered & dropped on the tap up.
        apply(g.onMove(120f, 20f, 0f, 0f, 200L))
        apply(g.onMove(120f, 20f, 0f, 0f, 500L)) // > 450 ⇒ engine LongPress
        apply(g.onMove(120f, 20f, 0f, 0f, 700L))
        // maxTravel 0 ≤ tapSlop but elapsed > longPressMs ⇒ engine.onUp is NOT
        // a Tap; classification here is "not a drag" ⇒ still the zone path.
        apply(g.onUp(120f, 20f, 900L))
        assertEquals(RemoteButton.Up, dir)
        assertTrue(
            all.none { it is TouchEvent.LongPress },
            "LongPress must NOT also reach onTouchEvent for an in-zone press ($all)",
        )
        assertTrue(all.isEmpty(), "a non-drag press delivers ZERO TouchEvents, got $all")
    }

    // --- drag: SwipeEngine stream, zero direction, terminal Release -------

    @Test fun dragDeliversEngineStreamAndNoDirection() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))
        // Move well beyond slop (slop=20, push 60px) over a few samples.
        apply(g.onMove(140f, 120f, 20f, 0f, 16L))
        apply(g.onMove(160f, 120f, 20f, 0f, 32L))
        apply(g.onMove(180f, 120f, 20f, 0f, 48L))
        apply(g.onUp(180f, 120f, 64L))
        assertNull(dir, "a drag must NOT fire a tap-zone direction")
        // The buffered Press + Hold frames + a terminal Release reached us.
        assertTrue(
            all.any { it is TouchEvent.Move && it.phase == TouchPhase.Press },
            "drag must flush the buffered Move(Press): $all",
        )
        assertEquals(
            1, all.releases().size,
            "exactly one terminal Move(Release) must close a drag: $all",
        )
        assertTrue(
            all.indexOfLast { it is TouchEvent.Move && it.phase == TouchPhase.Release } == all.size - 1 ||
                all.drop(all.indexOfFirst { it is TouchEvent.Move && it.phase == TouchPhase.Release } + 1)
                    .all { it is TouchEvent.Move && it.phase == TouchPhase.Hold },
            "Release must terminate the gesture (only inertia Holds may follow): $all",
        )
    }

    // --- C3: cancellation always terminates the engine cleanly -----------

    @Test fun cancelledDragSynthesizesTerminalReleaseNoInertiaNoDirection() {
        val e = engine()
        val g = gesture(e)
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))
        // A fast flick that WOULD start inertia on a normal up.
        apply(g.onMove(180f, 120f, 60f, 0f, 4L))
        apply(g.onMove(260f, 120f, 80f, 0f, 8L))
        // Ancestor consumes the pointer mid-drag ⇒ cancel.
        apply(g.onCancel(12L))
        assertNull(dir, "a cancelled gesture must not fire a direction")
        assertEquals(
            1, all.releases().size,
            "a cancelled drag must emit exactly one terminal Release: $all",
        )
        // The contract is that the reducer emits NO inertia glide after a
        // cancel: the terminal Release must be the LAST event the VM sees
        // (the engine's own inertiaActive flag is irrelevant — the reducer
        // never calls onInertiaFrame on the cancel path, so no Hold follows).
        assertTrue(
            all.last().let { it is TouchEvent.Move && it.phase == TouchPhase.Release },
            "a cancel must end at the Release with no inertia frames after: $all",
        )
        assertTrue(!g.inProgress, "gesture must be terminated after cancel")
    }

    @Test fun cancelledTapEmitsNothingButClosesEngine() {
        val e = engine()
        val g = gesture(e)
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 20f, 0L)) // no movement ⇒ still a "tap"
        apply(g.onCancel(50L))
        assertNull(dir, "an aborted tap fires no zone press")
        assertTrue(all.isEmpty(), "an aborted tap shows the VM nothing: $all")
        assertTrue(!g.inProgress)
        // Engine is closed: a subsequent onUp is a no-op (down==false) — proves
        // onCancel drove engine.onUp exactly once (no hung virtual finger).
        assertTrue(e.onUp(120f, 20f, 60L).isEmpty(), "engine must already be closed")
    }

    @Test fun secondGestureAfterCancelStartsClean() {
        val e = engine()
        var g = gesture(e)
        g.onDown(120f, 120f, 0L)
        g.onMove(220f, 120f, 100f, 0f, 8L)
        g.onCancel(12L)
        // A fresh gesture (the loop builds a new TouchpadGesture per press).
        g = gesture(e)
        var dir: RemoteButton? = null
        g.onDown(120f, 20f, 100L)
        g.onUp(120f, 20f, 160L).direction?.let { dir = it }
        assertEquals(RemoteButton.Up, dir, "post-cancel gesture must classify fresh")
    }

    // --- C2 framing: reducer ignores calls when not in progress ----------

    @Test fun outOfBandCallsAreNoOps() {
        val g = gesture()
        assertEquals(TouchpadGesture.Outcome.NONE, g.onMove(1f, 1f, 1f, 1f, 1L))
        assertEquals(TouchpadGesture.Outcome.NONE, g.onUp(1f, 1f, 1L))
        assertEquals(TouchpadGesture.Outcome.NONE, g.onCancel(1L))
    }
}
