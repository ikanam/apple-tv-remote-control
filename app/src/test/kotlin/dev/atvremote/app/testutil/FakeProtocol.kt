package dev.atvremote.app.testutil

import dev.atvremote.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSession : CompanionSession {
    val buttons = mutableListOf<Pair<RemoteButton, Boolean>>()
    val touches = mutableListOf<Triple<Int, Int, TouchPhase>>()
    val clicks = mutableListOf<InputAction>()
    val medias = mutableListOf<MediaCommand>()
    var text: String = ""
    var launched: String? = null
    var poweredOn: Boolean? = null
    var closed = false
    val connFlow = MutableStateFlow(ConnectionState.Connected)
    val focusFlow = MutableStateFlow(KeyboardFocusState.Unfocused)
    var apps = listOf(InstalledApp("com.netflix.Netflix", "Netflix"))

    override suspend fun button(button: RemoteButton, down: Boolean) { buttons += button to down }
    override suspend fun touch(x: Int, y: Int, phase: TouchPhase) { touches += Triple(x, y, phase) }
    override suspend fun click(action: InputAction) { clicks += action }
    override suspend fun textGet(): String = text
    override suspend fun textSet(value: String) { text = value }
    override suspend fun textClear() { text = "" }
    override suspend fun textAppend(value: String) { text += value }
    override val keyboardFocus: StateFlow<KeyboardFocusState> = focusFlow
    override suspend fun listApps(): List<InstalledApp> = apps
    override suspend fun launchApp(bundleId: String) { launched = bundleId }
    override suspend fun power(on: Boolean) { poweredOn = on }
    override suspend fun powerStatus(): PowerStatus =
        when (poweredOn) { true -> PowerStatus.On; false -> PowerStatus.Off; null -> PowerStatus.Unknown }
    override suspend fun media(command: MediaCommand) { medias += command }
    override val connectionState: StateFlow<ConnectionState> = connFlow
    override suspend fun close() { closed = true }
}

/** Alternative connect() seam for later ViewModel tasks (T8–T13); ConnectionManagerTest uses a SessionConnector lambda directly. */
/** Pluggable connect() behavior for ConnectionManager tests. */
class FakeRemote {
    var nextSession: () -> CompanionSession = { FakeSession() }
    var onConnect: (AppleTvDevice, HapCredentials) -> Unit = { _, _ -> }
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession {
        onConnect(device, credentials); return nextSession()
    }
}
