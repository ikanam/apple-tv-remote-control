package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeTypesTest {
    @Test fun defaultsAreSane() {
        val t = SwipeTuning.DEFAULT
        assertEquals(0.18f, t.dragStepFraction)
    }

    @Test fun directionalStepCarriesButton() {
        val d = TouchEvent.DirectionalStep(RemoteButton.Right)
        assertEquals(RemoteButton.Right, d.button)
    }
}
