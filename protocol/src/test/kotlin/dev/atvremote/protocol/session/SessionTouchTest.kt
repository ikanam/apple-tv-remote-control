package dev.atvremote.protocol.session

import dev.atvremote.protocol.TouchPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [CompanionSessionImpl.touch] delegating to [TouchTransport].
 *
 * pyatv-faithful model: _ns = nanoClock() - baseNs (session-relative).
 * The connect-time _touchStart instant (baseNs) is injected at construction;
 * the session touch method uses this base for all _hidT frames.
 */
class SessionTouchTest {
    @Test fun touchDelegatesToHidT() = runTest {
        val fake = FakeProtocol()
        val s = CompanionSessionImpl(fake)
        s.touch(2000, -1, TouchPhase.Press)
        val (name, c) = fake.sentEvents.last()
        assertEquals("_hidT", name)
        assertEquals(1000, c["_cx"])   // clamped high
        assertEquals(0, c["_cy"])      // clamped low
        assertEquals(TouchPhase.Press.value, c["_tPh"])
    }

    /**
     * When the session is constructed with an injected touchBaseNs and an injected nanoClock
     * (via the TouchTransport), _ns = clock - base (session-relative, not raw nanoTime).
     *
     * This mirrors pyatv hid_event: `_ns = time.time_ns() - self._base_timestamp` (api.py L303),
     * where _base_timestamp was set at _touch_start() time.
     */
    @Test fun touchNsIsSessionRelative() = runTest {
        val fake = FakeProtocol()
        val base = 1_000_000_000L   // simulated connect-time base
        val clock = 1_500_000_000L  // 500 ms later
        val s = CompanionSessionImpl(fake, touchBaseNs = base, nanoClock = { clock })
        s.touch(50, 50, TouchPhase.Hold)
        val (name, c) = fake.sentEvents.last()
        assertEquals("_hidT", name)
        assertEquals(500_000_000L, c["_ns"],
            "_ns must be session-relative (clock - touchBaseNs)")
    }
}
