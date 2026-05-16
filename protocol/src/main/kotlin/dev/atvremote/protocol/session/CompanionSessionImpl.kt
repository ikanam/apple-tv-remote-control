package dev.atvremote.protocol.session

import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.InstalledApp
import dev.atvremote.protocol.KeyboardFocusState
import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.PowerStatus
import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import dev.atvremote.protocol.connection.CommandChannel

/**
 * Concrete [CompanionSession] implementation backed by a [CommandChannel].
 *
 * Primary constructor accepts only a [CommandChannel] so that test doubles
 * (e.g. `RecordingProtocol2`) can be injected directly.
 *
 * For real usage, supply [onClose] to tear down the underlying
 * [dev.atvremote.protocol.connection.CompanionProtocol] and
 * [dev.atvremote.protocol.connection.CompanionConnection] when the session ends.
 *
 * NOTE: End-to-end correctness of the real connect path is deferred to Task 17
 * (CLI smoke test against a real Apple TV device).
 *
 * @param channel  the command channel used to send button and session events.
 * @param sid      the combined session id negotiated by `_sessionStart`
 *                 (`(remoteSid shl 32) or localSid`). Sent in `_sessionStop`;
 *                 real tvOS rejects `_sessionStop` with "No sessionID" without
 *                 it. Defaults to 0 for test doubles / no-handshake usage.
 * @param onClose  optional teardown hook; called once when [close] is invoked.
 */
class CompanionSessionImpl(
    private val channel: CommandChannel,
    private val sid: Long = 0L,
    private val onClose: suspend () -> Unit = {},
) : CompanionSession {

    /**
     * Sends a HID button event to the Apple TV.
     *
     * Maps to the `_hidC` command:
     *  - `_hBtS` (button state): 1 = down, 2 = up
     *  - `_hidC` (HID usage): [RemoteButton.hid]
     */
    override suspend fun button(button: RemoteButton, down: Boolean) {
        channel.exchange(
            "_hidC",
            mapOf(
                "_hBtS" to (if (down) 1 else 2),
                "_hidC" to button.hid,
            ),
        )
    }

    /**
     * Sends a best-effort `_sessionStop` (with the negotiated [sid], matching
     * pyatv `_session_stop`) then invokes the [onClose] teardown.
     */
    override suspend fun close() {
        runCatching {
            channel.exchange(
                "_sessionStop",
                mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to sid),
            )
        }
        onClose()
    }

    // ---- Plan-2 stubs (replaced with real bodies in later tasks) ----

    private val touchTransport by lazy { TouchTransport(channel) }
    private val hidCommands by lazy { HidCommands(channel) }
    private val appsController by lazy { AppsController(channel) }
    private val powerController by lazy { PowerController(channel) }

    override suspend fun touch(x: Int, y: Int, phase: TouchPhase) {
        touchTransport.touch(x, y, phase)
    }

    override suspend fun click(action: InputAction) { hidCommands.click(action) }
    override suspend fun textGet(): String = throw NotImplementedError()
    override suspend fun textSet(text: String): Unit = throw NotImplementedError()
    override suspend fun textClear(): Unit = throw NotImplementedError()
    override suspend fun textAppend(text: String): Unit = throw NotImplementedError()
    override val keyboardFocus = kotlinx.coroutines.flow.MutableStateFlow(KeyboardFocusState.Unfocused)
    override suspend fun listApps(): List<InstalledApp> = appsController.listApps()
    // `bundleId` may also be a URL/scheme — AppsController.launch picks _bundleID vs _urlS
    override suspend fun launchApp(bundleId: String) { appsController.launch(bundleId) }
    override suspend fun power(on: Boolean) { powerController.power(on) }
    override suspend fun powerStatus(): PowerStatus = powerController.status()
    override suspend fun media(command: MediaCommand): Unit = throw NotImplementedError()
    override val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.Connected)
}
