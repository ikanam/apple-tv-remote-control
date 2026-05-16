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
 * @param channel       the command channel used to send button and session events.
 * @param sid           the combined session id negotiated by `_sessionStart`
 *                      (`(remoteSid shl 32) or localSid`). Sent in `_sessionStop`;
 *                      real tvOS rejects `_sessionStop` with "No sessionID" without
 *                      it. Defaults to 0 for test doubles / no-handshake usage.
 * @param touchBaseNs   the connect-time `_touchStart` instant (from
 *                      [SessionHandshake.touchBaseNs]). Used as the `_ns` base in
 *                      every `_hidT` frame so touch timestamps are session-relative,
 *                      matching pyatv `hid_event` (api.py L303:
 *                      `_ns = time.time_ns() - self._base_timestamp`).
 *                      Defaults to 0 for test doubles that do not inject a base.
 * @param nanoClock     monotonic clock source for touch `_ns` computation; injected
 *                      for deterministic testing. Defaults to [System.nanoTime].
 * @param onClose       optional teardown hook; called once when [close] is invoked.
 */
class CompanionSessionImpl(
    private val channel: CommandChannel,
    private val sid: Long = 0L,
    private val touchBaseNs: Long = 0L,
    private val nanoClock: () -> Long = { System.nanoTime() },
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

    private val touchTransport by lazy { TouchTransport(channel, baseNs = touchBaseNs, nanoClock = nanoClock) }
    private val hidCommands by lazy { HidCommands(channel) }
    private val appsController by lazy { AppsController(channel) }
    private val powerController by lazy { PowerController(channel) }
    private val mediaController by lazy { MediaController(channel) }

    /**
     * Event subscription manager (pyatv `_interest`).
     * Tracks the active subscription set so Task 18's reconnect supervisor can
     * call [EventSubscriptions.restore] to re-register all subscriptions after
     * a reconnect without the caller needing to know the set.
     *
     * After connect, the caller should subscribe to the events it needs:
     *   subscriptions.subscribe("SystemStatus")
     *   subscriptions.subscribe("TVSystemStatus")
     * The handshake's `_iMC` subscribe is handled directly in [SessionHandshake]
     * (step 5) and kept there to preserve the Task-17-validated wire sequence.
     */
    internal val subscriptions by lazy { EventSubscriptions(channel) }

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
    override suspend fun media(command: MediaCommand) { mediaController.media(command) }
    /**
     * Always `Connected` for a standalone impl. The live connection lifecycle is
     * owned by the wrapping [dev.atvremote.protocol.connection.ResilientSession]
     * (RemoteConnect.connect always wraps the impl). Do not read this directly
     * expecting reconnection state — observe the ResilientSession's flow.
     * (T19 — deferred — is the zero-stubs/flows wiring gate.)
     */
    override val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.Connected)
}
