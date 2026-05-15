package dev.atvremote.protocol

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
}

interface PairingHandle {
    val state: StateFlow<PairingState>
    suspend fun submitPin(pin: String)
    fun cancel()
}

object AppleTvRemote {
    fun discovery(): DeviceDiscovery = throw NotImplementedError("Task 15")
    fun pair(device: AppleTvDevice): PairingHandle = throw NotImplementedError("Task 16")
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession =
        RemoteConnect.connect(device, credentials)
}
