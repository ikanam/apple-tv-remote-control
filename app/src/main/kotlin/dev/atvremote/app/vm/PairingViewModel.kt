package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.PairingHandle
import dev.atvremote.protocol.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Connecting : PairingUiState
    data object AwaitingPin : PairingUiState
    data object Completed : PairingUiState
    data class Failed(val reason: String) : PairingUiState
}

class PairingViewModel(
    private val deviceId: String,
    private val handle: PairingHandle,
    private val persist: suspend (deviceId: String, serializedBlob: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Connecting)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            handle.state.collect { ps ->
                _state.value = when (ps) {
                    is PairingState.AwaitingPin -> PairingUiState.AwaitingPin
                    is PairingState.Completed -> {
                        persist(deviceId, ps.credentials.serialize())
                        PairingUiState.Completed
                    }
                    is PairingState.Failed -> PairingUiState.Failed(ps.reason)
                }
            }
        }
    }

    fun submitPin(pin: String) {
        viewModelScope.launch { handle.submitPin(pin) }
    }

    fun cancel() {
        handle.cancel()
    }
}
