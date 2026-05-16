package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoveredDevice(val device: AppleTvDevice, val paired: Boolean)
data class DiscoveryUiState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val scanning: Boolean = true,
)

class DiscoveryViewModel(
    private val discovery: DeviceDiscovery,
    private val pairedDeviceIds: suspend () -> Set<String>,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val paired = pairedDeviceIds()
            discovery.devices().collect { list ->
                _state.value = DiscoveryUiState(
                    devices = list.map { DiscoveredDevice(it, it.id in paired) },
                    scanning = false,
                )
            }
        }
    }
}
