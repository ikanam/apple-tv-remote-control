package dev.atvremote.protocol.session

import dev.atvremote.protocol.TouchPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTransportTest {
    @Test fun singleTouchClampsAndSendsHidT() = runTest {
        val fake = FakeProtocol()
        val t = TouchTransport(fake) { 0L }   // injected clock = 0ns
        t.touch(-50, 5000, TouchPhase.Press)
        val (name, c) = fake.sentEvents.last()
        assertEquals("_hidT", name)
        assertEquals(0, c["_cx"])              // -50 clamped to 0
        assertEquals(1000, c["_cy"])           // 5000 clamped to 1000
        assertEquals(1, c["_tFg"])
        assertEquals(TouchPhase.Press.value, c["_tPh"])
        assertEquals(0L, c["_ns"])
    }

    @Test fun touchStartResetsBaseTimestamp() = runTest {
        val fake = FakeProtocol()
        var now = 5_000_000L
        val t = TouchTransport(fake) { now }
        t.start()
        assertEquals("_touchStart", fake.exchanges.last().first)
        assertEquals(1000.0, fake.exchanges.last().second["_width"])
        assertEquals(1000.0, fake.exchanges.last().second["_height"])
        assertEquals(0, fake.exchanges.last().second["_tFl"])
        now = 5_016_000L
        t.touch(10, 20, TouchPhase.Hold)
        assertEquals(16_000L, fake.sentEvents.last().second["_ns"]) // ns since start
    }

    @Test fun stopSendsTouchStop() = runTest {
        val fake = FakeProtocol()
        val t = TouchTransport(fake) { 0L }
        t.stop()
        assertEquals("_touchStop", fake.exchanges.last().first)
        assertEquals(1, fake.exchanges.last().second["_i"])
    }

    @Test fun swipeEmitsPressHoldsRelease() = runTest {
        val fake = FakeProtocol()
        var now = 0L
        val t = TouchTransport(fake) { now }
        t.swipe(0, 0, 100, 0, steps = 4) { now += 16_000_000L } // sleep advances clock
        // _hidT frames now arrive via sendEvent; use the ordered calls log which
        // captures both exchange() (_touchStart/_touchStop) and sendEvent() (_hidT)
        val phases = fake.calls.filter { it.first == "_hidT" }.map { it.second["_tPh"] }
        assertEquals(TouchPhase.Press.value, phases.first())
        assertEquals(TouchPhase.Release.value, phases.last())
        assertTrue(phases.drop(1).dropLast(1).all { it == TouchPhase.Hold.value })
        // start frame present before the first _hidT in the ordered log
        assertEquals("_touchStart", fake.calls.first().first)
        // x interpolated 0..100 across the steps
        val xs = fake.calls.filter { it.first == "_hidT" }.map { it.second["_cx"] as Int }
        assertEquals(0, xs.first()); assertEquals(100, xs.last())
        assertTrue(xs.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("_touchStop", fake.calls.last().first)
    }
}
