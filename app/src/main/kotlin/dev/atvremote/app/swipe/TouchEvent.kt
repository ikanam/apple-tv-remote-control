package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase

/** What the SwipeEngine decides the gesture should send to :protocol. Pure, no Android types. */
sealed interface TouchEvent {
    /** A point on the virtual trackpad in :protocol's 0..1000 coordinate space. */
    data class Move(val x: Int, val y: Int, val phase: TouchPhase) : TouchEvent

    /** Edge-zone press maps to a directional HID button step. */
    data class DirectionalStep(val button: RemoteButton) : TouchEvent

    /** Tap -> click(SingleTap). */
    data object Tap : TouchEvent

    /** Long-press -> click(Hold). */
    data object LongPress : TouchEvent
}
