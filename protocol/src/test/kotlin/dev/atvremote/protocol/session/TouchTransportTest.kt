package dev.atvremote.protocol.session

import dev.atvremote.protocol.TouchPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * pyatv-faithful touch model tests.
 *
 * pyatv reference (api.py, master 2026-05-16):
 *   _touch_start (L447): sets _base_timestamp = time.time_ns() (L449), then sends _touchStart.
 *                         Called once in connect() (L154) — not per gesture.
 *   hid_event    (L294): _ns = time.time_ns() - self._base_timestamp  (L303, session-relative).
 *   swipe        (L311): loop of hid_event calls only — NO _touchStart/_touchStop per gesture.
 *   _touch_stop  (L456): sent once at disconnect() (L122) — not per gesture.
 *
 * The connect-time _touchStart sets the base; TouchTransport receives that base at construction
 * (injected baseNs). start()/stop() are removed — session lifecycle is handled elsewhere.
 */
class TouchTransportTest {
    /** Session-relative _ns: injected clock minus injected base. */
    @Test fun hidTNsIsSessionRelative() = runTest {
        val fake = FakeProtocol()
        val base = 1_000_000_000L    // connect-time base (injected)
        val now  = 1_016_000_000L    // 16 ms later
        val t = TouchTransport(fake, baseNs = base) { now }
        t.touch(10, 20, TouchPhase.Press)
        val (name, c) = fake.sentEvents.last()
        assertEquals("_hidT", name)
        assertEquals(16_000_000L, c["_ns"])  // now - base = 16 ms in ns
    }

    /** x,y clamped to [0,1000]; _tFg=1; _tPh = phase.value. */
    @Test fun singleTouchClampsAndSendsHidT() = runTest {
        val fake = FakeProtocol()
        val t = TouchTransport(fake, baseNs = 0L) { 0L }
        t.touch(-50, 5000, TouchPhase.Press)
        val (name, c) = fake.sentEvents.last()
        assertEquals("_hidT", name)
        assertEquals(0, c["_cx"])              // -50 clamped to 0
        assertEquals(1000, c["_cy"])           // 5000 clamped to 1000
        assertEquals(1, c["_tFg"])
        assertEquals(TouchPhase.Press.value, c["_tPh"])
        assertEquals(0L, c["_ns"])             // 0 - 0 = 0
    }

    /**
     * Regression: `_hidT` field ORDER must EXACTLY match the real-device
     * golden (`goldentrace/touch-swipe.json`, pyatv 0.17.0 ↔ real 客厅 /
     * tvOS 26.5) and pyatv api.py hid_event: `_ns, _tFg, _cx, _tPh, _cy`.
     * Companion is OPACK (insertion-ordered) and real tvOS's _hidT decoder is
     * order-sensitive for the _cx/_tPh/_cy triple; the prior `_cx,_cy,_tPh`
     * mis-associated coordinate vs phase → erratic, start-position-dependent
     * swipe direction. The golden comparator compares decoded maps by key
     * (order-insensitive), so it never caught this — this test pins the order.
     */
    @Test fun hidTKeyOrderMatchesRealDeviceGoldenAndPyatv() = runTest {
        val fake = FakeProtocol()
        val t = TouchTransport(fake, baseNs = 0L) { 0L }
        t.touch(100, 500, TouchPhase.Hold)
        val (_, c) = fake.sentEvents.last()
        assertEquals(
            listOf("_ns", "_tFg", "_cx", "_tPh", "_cy"),
            c.keys.toList(),
            "OPACK _hidT key order must match pyatv/real-device exactly " +
                "(_tPh between _cx and _cy)",
        )
    }

    /**
     * swipe() emits ONLY _hidT (sendEvent) frames — Press, then Hold*, then Release.
     * NO _touchStart/_touchStop per gesture (pyatv swipe() is purely hid_event calls).
     */
    @Test fun swipeEmitsPressHoldsReleaseNoStartStop() = runTest {
        val fake = FakeProtocol()
        var now = 0L
        val t = TouchTransport(fake, baseNs = 0L) { now }
        t.swipe(0, 0, 100, 0, steps = 4) { now += 16_000_000L }
        // Only _hidT frames — no _touchStart or _touchStop in exchanges
        assertFalse(fake.exchanges.any { it.first == "_touchStart" },
            "swipe() must NOT send _touchStart per gesture")
        assertFalse(fake.exchanges.any { it.first == "_touchStop" },
            "swipe() must NOT send _touchStop per gesture")
        // All calls are _hidT sendEvent
        val phases = fake.sentEvents.map { it.second["_tPh"] }
        assertEquals(4, phases.size, "Expected 4 steps (steps=4)")
        assertEquals(TouchPhase.Press.value, phases.first())
        assertEquals(TouchPhase.Release.value, phases.last())
        assertTrue(phases.drop(1).dropLast(1).all { it == TouchPhase.Hold.value },
            "Middle phases must all be Hold")
    }

    /** swipe() interpolates x across steps; _ns is session-relative in each frame. */
    @Test fun swipeInterpolatesXAndNsIsSessionRelative() = runTest {
        val fake = FakeProtocol()
        val base = 5_000_000L
        var now = base
        val t = TouchTransport(fake, baseNs = base) { now }
        t.swipe(0, 0, 100, 0, steps = 4) { now += 16_000_000L }
        val xs  = fake.sentEvents.map { it.second["_cx"] as Int }
        val nss = fake.sentEvents.map { it.second["_ns"] as Long }
        assertEquals(0, xs.first()); assertEquals(100, xs.last())
        assertTrue(xs.zipWithNext().all { (a, b) -> b >= a }, "x must be monotonically non-decreasing")
        // _ns = clock_at_call - base; first call is at now=base → _ns=0
        assertEquals(0L, nss.first(), "_ns for the first step must be 0 (clock == base)")
        assertTrue(nss.zipWithNext().all { (a, b) -> b > a }, "_ns must increase step by step")
    }

    /** swipe() with steps=2 yields exactly Press + Release (no Hold). */
    @Test fun swipeWithTwoStepsYieldsPressAndRelease() = runTest {
        val fake = FakeProtocol()
        val t = TouchTransport(fake, baseNs = 0L) { 0L }
        t.swipe(10, 20, 30, 40, steps = 2) { }
        val phases = fake.sentEvents.map { it.second["_tPh"] }
        assertEquals(listOf(TouchPhase.Press.value, TouchPhase.Release.value), phases)
    }
}
