package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.launch

/**
 * Translates touchpad events + UI buttons into LOCKED CompanionSession calls.
 */
class RemoteViewModel(
    private val sessionProvider: () -> CompanionSession?,
    private val onTap: () -> Unit,
    private val onEdge: () -> Unit,
    private val onSelect: () -> Unit,
) : ViewModel() {

    fun onTouchEvent(event: TouchEvent) {
        when (event) {
            is TouchEvent.Tap -> {
                onTap()
                val s = sessionProvider() ?: return
                viewModelScope.launch { s.click(InputAction.SingleTap) }
            }
            is TouchEvent.LongPress -> {
                onSelect()
                val s = sessionProvider() ?: return
                viewModelScope.launch { s.click(InputAction.Hold) }
            }
            is TouchEvent.DirectionalStep -> {
                onEdge()
                pressButton(event.button)
            }
        }
    }

    fun pressButton(button: RemoteButton) {
        val s = sessionProvider() ?: return
        viewModelScope.launch {
            s.button(button, true)
            s.button(button, false)
        }
    }

    fun volumeUp() = pressButton(RemoteButton.VolumeUp)
    fun volumeDown() = pressButton(RemoteButton.VolumeDown)
    fun menu() = pressButton(RemoteButton.Menu)
    fun home() = pressButton(RemoteButton.Home)
    fun playPause() = pressButton(RemoteButton.PlayPause)

    fun media(command: MediaCommand) {
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.media(command) }
    }

    /** Power: tap → Wake (no powerStatus readback — FetchAttentionState is dead on tvOS 26.5). */
    fun wake() {
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.power(true) }
    }

    /** Power: long-press → Sleep. */
    fun sleep() {
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.power(false) }
    }
}
