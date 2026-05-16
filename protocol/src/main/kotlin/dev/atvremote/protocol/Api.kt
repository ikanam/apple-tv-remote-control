package dev.atvremote.protocol

import dev.atvremote.protocol.discovery.JmdnsDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class AppleTvDevice(
    val id: String, val name: String, val host: String, val port: Int,
    val model: String?, val pairable: Boolean,
)

data class HapCredentials(
    val clientId: ByteArray, val clientLtsk: ByteArray, val clientLtpk: ByteArray,
    val atvId: ByteArray, val atvLtpk: ByteArray,
) {
    fun serialize(): String {
        val parts = listOf(clientId, clientLtsk, clientLtpk, atvId, atvLtpk)
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            out.write(byteArrayOf(
                (p.size ushr 24).toByte(), (p.size ushr 16).toByte(),
                (p.size ushr 8).toByte(), p.size.toByte()))
            out.write(p)
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }
    companion object {
        fun parse(s: String): HapCredentials {
            val b = java.util.Base64.getDecoder().decode(s)
            var i = 0
            fun next(): ByteArray {
                val n = ((b[i].toInt() and 0xFF) shl 24) or ((b[i+1].toInt() and 0xFF) shl 16) or
                        ((b[i+2].toInt() and 0xFF) shl 8) or (b[i+3].toInt() and 0xFF)
                i += 4; val r = b.copyOfRange(i, i + n); i += n; return r
            }
            return HapCredentials(next(), next(), next(), next(), next())
        }
    }
}

sealed interface PairingState {
    data object AwaitingPin : PairingState
    data class Completed(val credentials: HapCredentials) : PairingState
    data class Failed(val reason: String) : PairingState
}

enum class RemoteButton(val hid: Int) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7),
    VolumeUp(8), VolumeDown(9), PlayPause(14)
}

interface DeviceDiscovery { fun devices(): Flow<List<AppleTvDevice>> }

interface CompanionSession {
    suspend fun button(button: RemoteButton, down: Boolean)
    suspend fun close()

    /** Single touch event. x/y are clamped to 0..1000 (TOUCHPAD is 1000x1000). */
    suspend fun touch(x: Int, y: Int, phase: TouchPhase)
    /** Click using the Select HID button + a Click-phase touch (see InputAction). */
    suspend fun click(action: InputAction)
    /** Read the text currently in the focused field on the TV. */
    suspend fun textGet(): String
    /** Replace the focused field's text with [text]. */
    suspend fun textSet(text: String)
    /** Clear the focused field's text. */
    suspend fun textClear()
    /** Append [text] to the focused field. */
    suspend fun textAppend(text: String)
    /** Hot StateFlow of keyboard focus, driven by _tiStarted/_tiStopped/_tiStart events. */
    val keyboardFocus: kotlinx.coroutines.flow.StateFlow<KeyboardFocusState>
    /** List installed launchable apps. */
    suspend fun listApps(): List<InstalledApp>
    /** Launch an app by bundle id (or URL/scheme). */
    suspend fun launchApp(bundleId: String)
    /** Wake (true) or sleep (false) the Apple TV via HID. */
    suspend fun power(on: Boolean)
    /** Query power state via FetchAttentionState. */
    suspend fun powerStatus(): PowerStatus
    /** Play/Pause/NextTrack/PreviousTrack via the media control command. */
    suspend fun media(command: MediaCommand)
    /** Hot StateFlow of connection lifecycle (resilient session). */
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>
}

interface PairingHandle {
    val state: StateFlow<PairingState>
    suspend fun submitPin(pin: String)
    fun cancel()
}

object AppleTvRemote {
    fun discovery(): DeviceDiscovery = JmdnsDiscovery()
    fun pair(device: AppleTvDevice): PairingHandle = RemoteConnect.pair(device)
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession =
        RemoteConnect.connect(device, credentials)
}

/** Touch phase wire values (pyatv companion _hidT _tPh). */
enum class TouchPhase(val value: Int) { Press(1), Hold(3), Release(4), Click(5) }

/** Logical click gesture. Ordinals are LOCKED: SingleTap=0, DoubleTap=1, Hold=2. */
enum class InputAction { SingleTap, DoubleTap, Hold }

/** Whether the Apple TV currently has a text field focused. */
enum class KeyboardFocusState { Focused, Unfocused }

/** An app installed on the Apple TV. */
data class InstalledApp(val bundleId: String, val name: String)

/** Power state derived from FetchAttentionState SystemStatus. */
enum class PowerStatus { On, Off, Unknown }

/** Media transport commands (pyatv MediaControlCommand subset used by v1). */
enum class MediaCommand(val value: Int) { Play(1), Pause(2), NextTrack(3), PreviousTrack(4) }

/** Live connection lifecycle, exposed by CompanionSession.connectionState. */
enum class ConnectionState { Connected, Reconnecting, Disconnected }

/**
 * Thrown by keyboard/app/media/power calls that cannot complete because the
 * session is currently Reconnecting or Disconnected.
 */
class CompanionUnavailableException(message: String) : Exception(message)
