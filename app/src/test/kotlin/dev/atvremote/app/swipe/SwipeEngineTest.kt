package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeEngineTest {
    private val tuning = SwipeTuning.DEFAULT
    private fun engine(now: Long = 0L) =
        SwipeEngine(tuning = tuning, widthPx = 1000f, heightPx = 1000f)

    @Test fun pressEmitsPressPhaseAtClampedCoords() {
        val e = engine()
        val out = e.onDown(x = 500f, y = 500f, nowMs = 0L)
        assertEquals(listOf<TouchEvent>(TouchEvent.Move(500, 500, TouchPhase.Press)), out)
    }

    @Test fun dragEmitsHoldAtAbsoluteFingerPosition() {
        val e = engine() // 1000px pad ⇒ absolute map is identity (clamped)
        e.onDown(500f, 500f, 0L)
        val out = e.onMove(520f, 500f, nowMs = 16L)
        val mv = out.filterIsInstance<TouchEvent.Move>().single()
        assertEquals(TouchPhase.Hold, mv.phase)
        // Absolute-linear: the finger is at 520 ⇒ cx=520 (NOT gain-amplified).
        assertEquals(520, mv.x)
        assertEquals(500, mv.y)
    }

    // Regression guard for the on-device root cause (hunt #15): the old
    // gain(2.4)+recenter(500)+clamp model pinned cx=1000 for any real swipe
    // (a controlled +440px drag sent x=1000 on 100% of frames). Absolute-
    // linear must track the finger proportionally and NOT saturate.
    @Test fun partialSwipeMapsProportionallyNotSaturated() {
        val e = engine() // 1000px pad
        e.onDown(300f, 500f, 0L)
        val mv = e.onMove(700f, 500f, 16L).filterIsInstance<TouchEvent.Move>().single()
        assertEquals(700, mv.x, "must be the absolute finger pos, not clamped 1000")
        assertEquals(500, mv.y)
    }

    @Test fun coordsClampedTo0_1000() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val out = e.onMove(5000f, -5000f, 16L)
        val mv = out.filterIsInstance<TouchEvent.Move>().single()
        assertEquals(1000, mv.x)
        assertEquals(0, mv.y)
    }

    @Test fun moveThrottledToMaxEventsPerSecond() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val a = e.onMove(510f, 500f, nowMs = 4L)
        val b = e.onMove(520f, 500f, nowMs = 7L)
        assertEquals(1, a.filterIsInstance<TouchEvent.Move>().size)
        assertEquals(0, b.filterIsInstance<TouchEvent.Move>().size)
        val c = e.onMove(530f, 500f, nowMs = 13L)
        assertEquals(1, c.filterIsInstance<TouchEvent.Move>().size)
    }

    @Test fun upWithinSlopIsTap() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        e.onMove(503f, 501f, 16L)
        val out = e.onUp(503f, 501f, nowMs = 120L)
        assertTrue(out.contains(TouchEvent.Tap))
        assertTrue(out.last() is TouchEvent.Move &&
            (out.last() as TouchEvent.Move).phase == TouchPhase.Release)
    }

    @Test fun stationaryLongPressClassifiedBeforeUp() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val held = e.onTick(nowMs = 500L)
        assertTrue(held.contains(TouchEvent.LongPress))
        val out = e.onUp(500f, 500f, nowMs = 520L)
        assertTrue(!out.contains(TouchEvent.Tap))
    }

    @Test fun pressInRightEdgeZoneIsDirectionalStep() {
        val e = engine()
        val out = e.onDown(x = 980f, y = 500f, nowMs = 0L)
        assertEquals(
            TouchEvent.DirectionalStep(RemoteButton.Right),
            out.first { it is TouchEvent.DirectionalStep }
        )
    }

    @Test fun edgeZonesMapToCorrectButtons() {
        assertEquals(RemoteButton.Left,
            engine().onDown(20f, 500f, 0L).first { it is TouchEvent.DirectionalStep }
                .let { (it as TouchEvent.DirectionalStep).button })
        assertEquals(RemoteButton.Up,
            engine().onDown(500f, 20f, 0L).first { it is TouchEvent.DirectionalStep }
                .let { (it as TouchEvent.DirectionalStep).button })
        assertEquals(RemoteButton.Down,
            engine().onDown(500f, 980f, 0L).first { it is TouchEvent.DirectionalStep }
                .let { (it as TouchEvent.DirectionalStep).button })
    }

    // No client-side inertia: tvOS computes its own momentum from the
    // absolute (cx,cy,_ns) stream, exactly like a physical Siri remote and
    // pyatv's swipe(). The flick ends at the terminal Release; onInertiaFrame
    // is inert.
    @Test fun noClientInertiaTvOwnsMomentum() {
        val e = engine()
        e.onDown(100f, 500f, 0L)
        e.onMove(300f, 500f, 8L)
        e.onMove(500f, 500f, 16L)
        val released = e.onUp(500f, 500f, 24L)
        val last = released.last()
        assertTrue(last is TouchEvent.Move && last.phase == TouchPhase.Release)
        assertEquals(500, (last as TouchEvent.Move).x) // absolute final pos
        assertTrue(!e.inertiaActive)
        assertTrue(e.onInertiaFrame(nowMs = 32L).isEmpty())
        assertTrue(e.onInertiaFrame(nowMs = 40L).isEmpty())
    }
}
