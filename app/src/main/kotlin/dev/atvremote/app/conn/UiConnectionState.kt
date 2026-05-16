package dev.atvremote.app.conn

import dev.atvremote.protocol.AppleTvDevice

/** UI-facing connection state (spec §7). Reconnecting is intentionally NOT an error. */
sealed interface UiConnectionState {
    data object Idle : UiConnectionState
    data class Connecting(val device: AppleTvDevice) : UiConnectionState
    data class Connected(val device: AppleTvDevice) : UiConnectionState
    data class Reconnecting(val device: AppleTvDevice) : UiConnectionState
    data class CredentialInvalid(val device: AppleTvDevice) : UiConnectionState
    data class Failed(val device: AppleTvDevice, val reason: String) : UiConnectionState
}

/** Thrown by the protocol layer (or detected by the manager) when stored creds are rejected. */
class CredentialInvalidException(message: String) : Exception(message)
