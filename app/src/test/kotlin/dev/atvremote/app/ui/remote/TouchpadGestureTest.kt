package dev.atvremote.app.ui.remote

import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.RemoteButton
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

    private fun gesture(
        tuning: SwipeTuning = SwipeTuning.DEFAULT,
    ) = TouchpadGesture(W, H, slop, tuning.dragStepFraction)

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

    @Test fun longHoldOnDirectionalZoneStaysAZonePressNotLongPress() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 20f, 0L)) // above centre ⇒ Up zone (NOT Select)
        apply(g.onMove(120f, 20f, 0f, 0f, 200L))
        apply(g.onMove(120f, 20f, 0f, 0f, 500L)) // > 450 ⇒ long-press threshold
        apply(g.onMove(120f, 20f, 0f, 0f, 700L))
        // Routing only converts long-hold to a touch event for the *centre*
        // (Select) zone (hunt #2). A long hold on a directional zone stays
        // exactly its zone press, no LongPress event.
        apply(g.onUp(120f, 20f, 900L))
        assertEquals(RemoteButton.Up, dir)
        assertTrue(
            all.none { it is TouchEvent.LongPress },
            "a directional-zone long hold must NOT emit LongPress ($all)",
        )
        assertTrue(all.isEmpty(), "a non-drag zone press delivers ZERO TouchEvents, got $all")
    }

    // hunt #2 regression guard: a long press on the centre OK with no finger
    // movement used to classify as "not a drag" ⇒ Outcome(direction=Select),
    // i.e. identical to a short tap. It must now deliver a LongPress touch
    // event (→ vm click(Hold)) and suppress the Select tap.
    @Test fun centerLongHoldFiresLongPressEventNotTapDirection() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))   // dead centre of the 240 box ⇒ Select
        apply(g.onUp(120f, 120f, 600L))   // held > longPressMs 450, no movement
        assertEquals(
            listOf<TouchEvent>(TouchEvent.LongPress), all,
            "centre long-hold must emit exactly one LongPress event: $all",
        )
        assertNull(dir, "centre long-hold must NOT also fire a Select zone press")
    }

    // hunt #2 (revised): the long-press must fire WHILE the finger is held,
    // not on release. onHoldTick is the timer the Touchpad loop drives for a
    // stationary finger; past longPressMs on the centre it must emit LongPress
    // immediately, exactly once, and the eventual up must then be inert (no
    // second LongPress, no Select tap) — and an indefinite hold still fired.
    @Test fun centerLongPressFiresDuringHoldThenUpIsInert() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))         // dead centre ⇒ Select
        apply(g.onHoldTick(200L))               // < 450 ⇒ nothing yet
        assertTrue(all.isEmpty(), "no long-press before longPressMs: $all")
        apply(g.onHoldTick(500L))               // > 450 ⇒ fire WHILE held
        assertEquals(listOf<TouchEvent>(TouchEvent.LongPress), all,
            "long-press must fire during the hold, once: $all")
        assertTrue(!g.canLongPress, "long-press is spent after firing")
        apply(g.onHoldTick(900L))               // still held — must NOT re-fire
        apply(g.onUp(120f, 120f, 1200L))        // release later — inert
        assertEquals(listOf<TouchEvent>(TouchEvent.LongPress), all,
            "no second LongPress and no tap on the eventual up: $all")
        assertNull(dir, "a fired long-press must not also fire a Select tap")
    }

    @Test fun centerLongPressWithSubSlopMotionFiresDuringMoveThenUpIsInert() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))         // dead centre ⇒ Select
        apply(g.onMove(122f, 120f, 2f, 0f, 200L))
        assertTrue(all.isEmpty(), "no long-press before longPressMs: $all")
        apply(g.onMove(124f, 120f, 2f, 0f, 500L)) // < slop, > longPressMs
        assertEquals(
            listOf<TouchEvent>(TouchEvent.LongPress), all,
            "sub-slop jitter must still fire the long-press during the hold: $all",
        )
        assertTrue(!g.canLongPress, "long-press is spent after firing")
        apply(g.onUp(124f, 120f, 900L))
        assertEquals(
            listOf<TouchEvent>(TouchEvent.LongPress), all,
            "release after a jittery long-press must be inert: $all",
        )
        assertNull(dir, "a fired long-press must not also fire a Select tap")
    }

    @Test fun directionalZoneHoldTickNeverFiresLongPress() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        // (120,40): r≈80 > innerR(79) ⇒ Up zone, but y=40 > edge band(21.6)
        // so it is NOT edge-consumed — this truly exercises onHoldTick's
        // "not the Select zone" branch.
        apply(g.onDown(120f, 40f, 0L))
        apply(g.onHoldTick(500L))               // > 450 but NOT the centre
        apply(g.onHoldTick(900L))
        apply(g.onUp(120f, 40f, 1200L))
        assertTrue(all.isEmpty(), "a directional long hold emits no LongPress: $all")
        assertEquals(RemoteButton.Up, dir, "it stays its zone press on up")
    }

    @Test fun centerShortTapStillFiresSelectNotLongPress() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))
        apply(g.onUp(120f, 120f, 80L)) // < longPressMs ⇒ ordinary tap
        assertEquals(RemoteButton.Select, dir)
        assertTrue(all.isEmpty(), "a short centre tap stays a Select zone press: $all")
    }

    // --- drag: deterministic HID direction steps, zero tap direction -----

    @Test fun dragBeyondSlopEmitsDirectionalStepAndNoTouchStream() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))     // centre of the 240 box
        // Rightward, well beyond slop (slop=20), over a few samples.
        apply(g.onMove(140f, 120f, 20f, 0f, 16L))
        apply(g.onMove(160f, 120f, 20f, 0f, 32L))
        apply(g.onMove(180f, 120f, 20f, 0f, 48L))
        apply(g.onUp(180f, 120f, 64L))
        assertNull(dir, "a drag must NOT fire a tap-zone direction")
        assertEquals(
            listOf(RemoteButton.Right),
            all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button },
            "rightward drag must emit a deterministic HID right step: $all",
        )
    }

    @Test fun longDragRepeatsDirectionalStepEveryShorterDistance() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        fun apply(o: TouchpadGesture.Outcome) { all += o.events }
        apply(g.onDown(120f, 120f, 0L))
        apply(g.onMove(150f, 120f, 30f, 0f, 16L))
        apply(g.onMove(205f, 120f, 55f, 0f, 32L))
        apply(g.onMove(235f, 120f, 30f, 0f, 48L))
        apply(g.onUp(235f, 120f, 64L))

        assertEquals(
            listOf(RemoteButton.Right, RemoteButton.Right, RemoteButton.Right),
            all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button },
            "a longer right drag should repeat HID steps at the shortened distance: $all",
        )
    }

    @Test fun dragStepFractionControlsRepeatDistance() {
        fun stepCount(tuning: SwipeTuning): Int {
            val g = gesture(tuning = tuning)
            val all = ArrayList<TouchEvent>()
            fun apply(o: TouchpadGesture.Outcome) { all += o.events }
            apply(g.onDown(120f, 120f, 0L))
            apply(g.onMove(235f, 120f, 115f, 0f, 16L))
            apply(g.onUp(235f, 120f, 32L))
            return all.filterIsInstance<TouchEvent.DirectionalStep>().size
        }

        assertEquals(
            3,
            stepCount(SwipeTuning.DEFAULT.copy(dragStepFraction = 0.18f)),
            "default threshold should repeat after short focus-drag intervals",
        )
        assertEquals(
            1,
            stepCount(SwipeTuning.DEFAULT.copy(dragStepFraction = 0.50f)),
            "larger threshold should require more travel per focus step",
        )
    }

    @Test fun rightEdgeStartLeftDragEmitsOnlyLeftSteps() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(230f, 120f, 0L)) // right edge zone

        apply(g.onMove(180f, 120f, -50f, 0f, 16L))
        apply(g.onMove(120f, 120f, -60f, 0f, 32L))
        apply(g.onUp(120f, 120f, 48L))

        assertNull(dir, "a drag from the edge must not fire a tap-zone direction")
        assertEquals(
            listOf(RemoteButton.Left, RemoteButton.Left, RemoteButton.Left),
            all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button },
            "left drag from right edge must only emit left HID steps: $all",
        )
    }

    @Test fun leftDragReleaseCoordinateRecoilDoesNotEmitRightStep() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))
        apply(g.onMove(90f, 120f, -30f, 0f, 16L))
        apply(g.onMove(60f, 120f, -30f, 0f, 32L))
        // Some real fast lifts report the final up coordinate after a small
        // rightward finger recoil. Focus drags must ignore that lift recoil
        // instead of turning it into an opposite HID step.
        apply(g.onUp(130f, 120f, 48L))

        assertNull(dir, "a drag must not fire a tap-zone direction")
        assertEquals(
            listOf(RemoteButton.Left),
            all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button },
            "left drag lift recoil must not synthesize a right step: $all",
        )
    }

    @Test fun rightDragReleaseCoordinateRecoilDoesNotEmitLeftStep() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))
        apply(g.onMove(150f, 120f, 30f, 0f, 16L))
        apply(g.onMove(180f, 120f, 30f, 0f, 32L))
        apply(g.onUp(110f, 120f, 48L))

        assertNull(dir, "a drag must not fire a tap-zone direction")
        assertEquals(
            listOf(RemoteButton.Right),
            all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button },
            "right drag lift recoil must not synthesize a left step: $all",
        )
    }

    @Test fun edgeZoneTapStillFiresDirectionOnUp() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(230f, 120f, 0L)) // right edge zone
        apply(g.onUp(230f, 120f, 80L))
        assertEquals(RemoteButton.Right, dir)
        assertTrue(all.isEmpty(), "tap-zone direction should not leak touch events: $all")
    }

    @Test fun cancelledEdgeZoneDragDoesNotEmitOppositeStepOnCancel() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(230f, 120f, 0L)) // right edge zone
        apply(g.onMove(180f, 120f, -50f, 0f, 16L))
        apply(g.onCancel(20L))
        assertNull(dir, "cancelled edge-start drag must not fire a tap-zone direction")
        assertEquals(
            listOf(RemoteButton.Left),
            all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button },
            "cancel after a left drag must not synthesize a right step: $all",
        )
    }

    @Test fun fastFlickAndSlowDragBothEmitSameDirection() {
        fun stepsOf(steps: List<Triple<Float, Float, Long>>): List<RemoteButton> {
            val g = gesture()
            val all = ArrayList<TouchEvent>()
            fun apply(o: TouchpadGesture.Outcome) { all += o.events }
            g.onDown(120f, 120f, 0L)
            var px = 120f
            for ((x, _, t) in steps) {
                apply(g.onMove(x, 120f, x - px, 0f, t))
                px = x
            }
            apply(g.onUp(steps.last().first, 120f, steps.last().third + 4L))
            return all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button }
        }
        // Fast (few big jumps) and slow (many small) rightward flicks must
        // both emit right HID steps — never the opposite (the user's bug),
        // regardless of speed.
        val fast = stepsOf(listOf(Triple(200f, 0f, 4L), Triple(300f, 0f, 8L)))
        val slow = (1..10).map { Triple(120f + it * 9f, 0f, it * 16L) }
            .let { stepsOf(it) }
        assertTrue(fast.isNotEmpty(), "fast flick should emit at least one step")
        assertTrue(slow.isNotEmpty(), "slow drag should emit at least one step")
        assertTrue(fast.all { it == RemoteButton.Right }, "fast flick right must only emit Right: $fast")
        assertTrue(slow.all { it == RemoteButton.Right }, "slow drag right must only emit Right: $slow")
    }

    @Test fun diagonalDragUsesDominantAxisForDirection() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        fun apply(o: TouchpadGesture.Outcome) { all += o.events }
        apply(g.onDown(120f, 120f, 0L))
        // Net: +110 right, -80 up. Directional navigation should use the
        // dominant axis and avoid touch-stream momentum semantics.
        apply(g.onMove(170f, 90f, 50f, -30f, 16L))
        apply(g.onMove(230f, 40f, 60f, -50f, 32L))
        apply(g.onUp(230f, 40f, 48L))
        val steps = all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button }
        assertTrue(steps.isNotEmpty(), "diagonal drag should emit a HID step: $all")
        assertTrue(steps.all { it == RemoteButton.Right }, "x-dominant diagonal should go Right: $steps")
    }

    @Test fun clearlyVerticalSwipeEmitsVerticalDirection() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        fun apply(o: TouchpadGesture.Outcome) { all += o.events }
        apply(g.onDown(120f, 120f, 0L))
        // Net: +20 right, +120 down.
        apply(g.onMove(130f, 180f, 10f, 60f, 16L))
        apply(g.onMove(140f, 240f, 10f, 60f, 32L))
        apply(g.onUp(140f, 240f, 48L))
        val steps = all.filterIsInstance<TouchEvent.DirectionalStep>().map { it.button }
        assertTrue(steps.isNotEmpty(), "downward drag should emit a HID step: $all")
        assertTrue(steps.all { it == RemoteButton.Down }, "downward drag must only emit Down: $steps")
    }

    // --- cancellation: drag terminates without extra output ---------------

    @Test fun cancelledDragTerminatesCleanlyWithoutTouchRelease() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 120f, 0L))
        apply(g.onMove(180f, 120f, 60f, 0f, 4L))
        apply(g.onMove(260f, 120f, 80f, 0f, 8L))
        apply(g.onCancel(12L)) // ancestor consumes the pointer mid-drag
        assertNull(dir, "a cancelled gesture must not fire a direction")
        assertTrue(
            all.filterIsInstance<TouchEvent.DirectionalStep>().isNotEmpty(),
            "a drag should emit directional steps before cancel: $all",
        )
        assertTrue(!g.inProgress, "gesture must be terminated after cancel")
        assertEquals(TouchpadGesture.Outcome.NONE, g.onUp(260f, 120f, 20L))
    }

    @Test fun cancelledTapEmitsNothingButClosesEngine() {
        val g = gesture()
        val all = ArrayList<TouchEvent>()
        var dir: RemoteButton? = null
        fun apply(o: TouchpadGesture.Outcome) { all += o.events; o.direction?.let { dir = it } }
        apply(g.onDown(120f, 20f, 0L)) // no movement ⇒ still a "tap"
        apply(g.onCancel(50L))
        assertNull(dir, "an aborted tap fires no zone press")
        assertTrue(all.isEmpty(), "an aborted tap shows the VM nothing: $all")
        assertTrue(!g.inProgress)
        assertEquals(TouchpadGesture.Outcome.NONE, g.onUp(120f, 20f, 60L))
    }

    @Test fun secondGestureAfterCancelStartsClean() {
        var g = gesture()
        g.onDown(120f, 120f, 0L)
        g.onMove(220f, 120f, 100f, 0f, 8L)
        g.onCancel(12L)
        // A fresh gesture (the loop builds a new TouchpadGesture per press).
        g = gesture()
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
