package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeTypesTest {
    @Test fun defaultsAreSane() {
        val t = SwipeTuning.DEFAULT
        assertEquals(120, t.maxEventsPerSecond)
        assertEquals(0.18f, t.edgeZoneFraction)
        assertEquals(true, t.gain > 0f && t.inertiaDecay in 0f..1f)
    }

    @Test fun touchEventCarriesProtocolPhase() {
        val e = TouchEvent.Move(x = 500, y = 1000, phase = TouchPhase.Hold)
        assertEquals(500, e.x)
        assertEquals(1000, e.y)
        assertEquals(TouchPhase.Hold, e.phase)

        val d = TouchEvent.DirectionalStep(RemoteButton.Right)
        assertEquals(RemoteButton.Right, d.button)
    }
}
