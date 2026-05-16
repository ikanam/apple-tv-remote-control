package dev.atvremote.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.atvremote.protocol.AppleTvDevice
import kotlinx.coroutines.flow.first
import java.net.URLDecoder
import java.net.URLEncoder

private val Context.credDataStore by preferencesDataStore(name = "atv_credentials")

private val LAST_DEVICE_KEY = stringPreferencesKey("last_device")

/**
 * Persists HapCredentials.serialize() blobs sealed by Keystore, keyed by AppleTvDevice.id.
 * Spec §3/§6: app wraps long-term credentials before storing.
 */
class CredentialStore(
    private val context: Context,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {
    private fun keyFor(deviceId: String) = stringPreferencesKey("cred_$deviceId")

    suspend fun save(deviceId: String, serializedCredentials: String) {
        val sealed = cipher.seal(serializedCredentials)
        context.credDataStore.edit { it[keyFor(deviceId)] = sealed }
    }

    suspend fun load(deviceId: String): String? {
        val sealed = context.credDataStore.data.first()[keyFor(deviceId)] ?: return null
        return runCatching { cipher.unseal(sealed) }.getOrNull()
    }

    suspend fun clear(deviceId: String) {
        context.credDataStore.edit { it.remove(keyFor(deviceId)) }
    }

    suspend fun allDeviceIds(): List<String> =
        context.credDataStore.data.first().asMap().keys
            .map { it.name }
            .filter { it.startsWith("cred_") }
            .map { it.removePrefix("cred_") }

    /** Test/diagnostic accessor: returns the stored (sealed) string verbatim. */
    suspend fun rawStored(deviceId: String): String? =
        context.credDataStore.data.first()[keyFor(deviceId)]

    // ── S1: last-device persistence ──────────────────────────────────────────

    /**
     * Serialize [device] with per-field URL-encoding joined by pipes (collision-safe:
     * all string field characters are percent-encoded so the pipe delimiter cannot
     * appear inside any encoded field).  The nullable [AppleTvDevice.model] field uses
     * a tagged prefix: "N" for null, "V" + urlEncoded(model) for a non-null value —
     * this guarantees null vs empty-string are always distinct regardless of model
     * content.  The serialized payload is then sealed by [cipher] before writing.
     */
    suspend fun saveLastDevice(device: AppleTvDevice) {
        val enc: (String) -> String = { URLEncoder.encode(it, "UTF-8") }
        val deviceModel = device.model
        val modelEncoded = if (deviceModel == null) "N" else "V${enc(deviceModel)}"
        val payload = listOf(
            enc(device.id),
            enc(device.name),
            enc(device.host),
            device.port.toString(),
            modelEncoded,
            device.pairable.toString(),
        ).joinToString("|")
        val sealed = cipher.seal(payload)
        context.credDataStore.edit { it[LAST_DEVICE_KEY] = sealed }
    }

    /**
     * Returns the last-saved [AppleTvDevice], or null if absent, unsealing/parse fails,
     * or the key has never been written (mirrors [load]'s [runCatching] on unseal).
     */
    suspend fun lastDevice(): AppleTvDevice? {
        val sealed = context.credDataStore.data.first()[LAST_DEVICE_KEY] ?: return null
        return runCatching {
            val payload = cipher.unseal(sealed)
            val parts = payload.split("|")
            require(parts.size == 6)
            val dec: (String) -> String = { URLDecoder.decode(it, "UTF-8") }
            val id = dec(parts[0])
            val name = dec(parts[1])
            val host = dec(parts[2])
            val port = parts[3].toInt()
            val modelField = parts[4]
            val model = when {
                modelField == "N" -> null
                modelField.startsWith("V") -> dec(modelField.substring(1))
                else -> error("Unexpected model encoding: $modelField")
            }
            val pairable = parts[5].toBoolean()
            AppleTvDevice(id, name, host, port, model, pairable)
        }.getOrNull()
    }

    /** Test/diagnostic accessor: returns the stored (sealed) last-device string verbatim. */
    suspend fun rawStoredLastDevice(): String? =
        context.credDataStore.data.first()[LAST_DEVICE_KEY]
}
