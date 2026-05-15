package dev.atvremote.protocol.session

import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.RemoteButton
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
}
