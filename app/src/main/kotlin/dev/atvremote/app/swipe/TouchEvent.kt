package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton

/** What the app touchpad sends to the remote VM. Pure, no Android types. */
sealed interface TouchEvent {
    /** Discrete directional step used by focus navigation. */
    data class DirectionalStep(val button: RemoteButton) : TouchEvent

    /** Tap -> click(SingleTap). */
    data object Tap : TouchEvent

    /** Long-press -> click(Hold). */
    data object LongPress : TouchEvent
}
