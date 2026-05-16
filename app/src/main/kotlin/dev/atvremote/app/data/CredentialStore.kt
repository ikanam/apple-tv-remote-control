package dev.atvremote.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.credDataStore by preferencesDataStore(name = "atv_credentials")

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
}
