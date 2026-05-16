package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.KeyboardFocusState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KeyboardUiState(val visible: Boolean = false, val text: String = "")

/**
 * Real-time keyboard sync (spec §5/§6): auto-shows when the ATV text field is
 * Focused, mirrors current text, and pushes edits through text*().
 */
class KeyboardViewModel(
    private val sessionProvider: () -> CompanionSession?,
) : ViewModel() {
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()

    init {
        val s = sessionProvider()
        if (s != null) {
            viewModelScope.launch {
                s.keyboardFocus.collect { focus ->
                    when (focus) {
                        KeyboardFocusState.Focused ->
                            _state.value = KeyboardUiState(visible = true, text = s.textGet())
                        KeyboardFocusState.Unfocused ->
                            _state.value = _state.value.copy(visible = false)
                    }
                }
            }
        }
    }

    fun setText(value: String) {
        _state.value = _state.value.copy(text = value)
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.textSet(value) }
    }

    fun append(value: String) {
        _state.value = _state.value.copy(text = _state.value.text + value)
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.textAppend(value) }
    }

    fun clear() {
        _state.value = _state.value.copy(text = "")
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.textClear() }
    }
}
