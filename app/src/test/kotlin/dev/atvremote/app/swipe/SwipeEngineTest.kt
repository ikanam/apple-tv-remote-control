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

    @Test fun dragEmitsHoldPhaseScaledByGain() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val out = e.onMove(520f, 500f, nowMs = 16L) // +20px * gain 2.4 ≈ +48 units
        val mv = out.filterIsInstance<TouchEvent.Move>().single()
        assertEquals(TouchPhase.Hold, mv.phase)
        assertTrue(mv.x in 540..560, "x was ${mv.x}")
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

    @Test fun inertiaGeneratesDecayingMovesAfterFlick() {
        val e = engine()
        e.onDown(100f, 500f, 0L)
        e.onMove(300f, 500f, 8L)
        e.onMove(500f, 500f, 16L)
        val released = e.onUp(500f, 500f, 24L)
        assertTrue(released.last() is TouchEvent.Move &&
            (released.last() as TouchEvent.Move).phase == TouchPhase.Release)
        val f1 = e.onInertiaFrame(nowMs = 32L)
        val f2 = e.onInertiaFrame(nowMs = 40L)
        assertTrue(f1.isNotEmpty())
        var ticks = 0
        while (e.inertiaActive && ticks < 200) { e.onInertiaFrame(nowMs = 48L + ticks * 8L); ticks++ }
        assertTrue(!e.inertiaActive)
    }
}
