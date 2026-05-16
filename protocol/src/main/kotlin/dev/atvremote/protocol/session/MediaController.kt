package dev.atvremote.protocol.session

import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.connection.CommandChannel

/**
 * Media transport over Companion (pyatv).
 * pyatv ref: pyatv/protocols/companion/api.py MediaControlCommand (L59–74)
 *            pyatv/protocols/companion/api.py mediacontrol_command (L378–382)
 *   return await self._send_command("_mcc", {"_mcc": command.value, **(args or {})})
 *
 * Enum values: Play=1, Pause=2, NextTrack=3, PreviousTrack=4 — equal to MediaCommand.value.
 * Volume control (uses args) is out of scope for v1; use CompanionSession.button() instead.
 */
internal class MediaController(private val ch: CommandChannel) {
    suspend fun media(command: MediaCommand) {
        ch.exchange("_mcc", mapOf("_mcc" to command.value))
    }
}
