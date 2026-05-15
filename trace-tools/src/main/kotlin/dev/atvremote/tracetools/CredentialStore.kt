package dev.atvremote.tracetools

import dev.atvremote.protocol.HapCredentials
import java.io.File

/**
 * Simple flat-file credential store for the CLI smoke test.
 *
 * Each line in [file] holds one entry: `<deviceId>=<HapCredentials.serialize()>`.
 * A line is split on the **first** `=` only, so device IDs that contain `@`
 * or `:` (the default `"$name@$host:$port"` format) are handled correctly.
 * Saving the same [deviceId] twice replaces its line rather than appending a
 * duplicate.
 *
 * **Security note:** this store uses a plain-text file on disk and is
 * intentionally designed for CLI / developer use only. Android production
 * code (Plan 3) must use the Android Keystore / EncryptedSharedPreferences
 * instead of this class.
 */
class CredentialStore(private val file: File) {

    /**
     * Persist [creds] for [deviceId], replacing any existing entry for the
     * same ID. Creates the file and any parent directories if they do not
     * already exist.
     */
    fun save(deviceId: String, creds: HapCredentials) {
        require('=' !in deviceId) { "deviceId must not contain '='" }
        val newLine = "$deviceId=${creds.serialize()}"

        val existing: List<String> = if (file.exists()) file.readLines() else emptyList()

        // Replace an existing entry for this deviceId, or append a new one.
        var replaced = false
        val updated = existing.map { line ->
            val eqIdx = line.indexOf('=')
            if (eqIdx >= 0 && line.substring(0, eqIdx) == deviceId) {
                replaced = true
                newLine
            } else {
                line
            }
        }.toMutableList()
        if (!replaced) updated.add(newLine)

        file.parentFile?.mkdirs()
        file.writeText(updated.joinToString("\n") + "\n")
    }

    /**
     * Return the [HapCredentials] stored for [deviceId], or `null` if the
     * file does not exist or no entry for [deviceId] is found.
     */
    fun load(deviceId: String): HapCredentials? {
        if (!file.exists()) return null
        for (line in file.readLines()) {
            val eqIdx = line.indexOf('=')
            if (eqIdx < 0) continue
            if (line.substring(0, eqIdx) == deviceId) {
                val value = line.substring(eqIdx + 1)
                return HapCredentials.parse(value)
            }
        }
        return null
    }
}
