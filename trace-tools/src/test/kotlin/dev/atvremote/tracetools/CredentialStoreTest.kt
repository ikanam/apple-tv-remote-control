package dev.atvremote.tracetools

import dev.atvremote.protocol.HapCredentials
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device-free unit tests for [CredentialStore].
 *
 * All tests run against a temp directory; no network is required.
 */
class CredentialStoreTest {

    // Known byte arrays for a deterministic HapCredentials.
    private val clientId  = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val clientLtsk = ByteArray(32) { it.toByte() }
    private val clientLtpk = ByteArray(32) { (it + 32).toByte() }
    private val atvId     = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())
    private val atvLtpk   = ByteArray(32) { (it + 64).toByte() }

    private fun knownCreds() = HapCredentials(
        clientId  = clientId,
        clientLtsk = clientLtsk,
        clientLtpk = clientLtpk,
        atvId     = atvId,
        atvLtpk   = atvLtpk,
    )

    private fun tempFile(): java.io.File {
        val dir = Files.createTempDirectory("credential-store-test").toFile()
        return java.io.File(dir, "credentials")
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `save and load round-trips all five fields`() {
        val file  = tempFile()
        val store = CredentialStore(file)
        val creds = knownCreds()

        store.save("dev@1.2.3.4:49152", creds)
        val loaded = store.load("dev@1.2.3.4:49152")
            ?: error("load returned null")

        assertTrue(loaded.clientId.contentEquals(creds.clientId),   "clientId mismatch")
        assertTrue(loaded.clientLtsk.contentEquals(creds.clientLtsk), "clientLtsk mismatch")
        assertTrue(loaded.clientLtpk.contentEquals(creds.clientLtpk), "clientLtpk mismatch")
        assertTrue(loaded.atvId.contentEquals(creds.atvId),         "atvId mismatch")
        assertTrue(loaded.atvLtpk.contentEquals(creds.atvLtpk),     "atvLtpk mismatch")
    }

    // ── no-duplicate on re-save ───────────────────────────────────────────────

    @Test
    fun `re-saving same deviceId replaces its line, does not duplicate`() {
        val file  = tempFile()
        val store = CredentialStore(file)

        val credsA = knownCreds()
        // second set with distinct byte values
        val credsB = HapCredentials(
            clientId   = byteArrayOf(0x10, 0x20),
            clientLtsk = ByteArray(32) { (it + 100).toByte() },
            clientLtpk = ByteArray(32) { (it + 132).toByte() },
            atvId      = byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
            atvLtpk    = ByteArray(32) { (it + 164).toByte() },
        )

        store.save("mydevice", credsA)
        store.save("mydevice", credsB)  // must REPLACE, not append

        // File must contain exactly one line whose key is "mydevice"
        val lines = file.readLines().filter { it.startsWith("mydevice=") }
        assertEquals(1, lines.size, "expected exactly one line for 'mydevice', found ${lines.size}")

        // The loaded value must be credsB (the latest save)
        val loaded = store.load("mydevice") ?: error("load returned null")
        assertTrue(loaded.clientId.contentEquals(credsB.clientId), "expected credsB.clientId after re-save")
        assertTrue(loaded.atvLtpk.contentEquals(credsB.atvLtpk),  "expected credsB.atvLtpk after re-save")
    }

    // ── absent key ────────────────────────────────────────────────────────────

    @Test
    fun `load returns null for absent deviceId`() {
        val file  = tempFile()
        val store = CredentialStore(file)

        store.save("present-device", knownCreds())
        assertNull(store.load("absent-device"), "expected null for unknown deviceId")
    }

    // ── missing file ─────────────────────────────────────────────────────────

    @Test
    fun `load returns null when file does not exist`() {
        val dir   = Files.createTempDirectory("credential-store-nofile").toFile()
        val file  = java.io.File(dir, "nonexistent/credentials")
        val store = CredentialStore(file)

        assertNull(store.load("any-device"), "expected null when file does not exist")
    }

    // ── parent directory creation ─────────────────────────────────────────────

    @Test
    fun `save creates parent directories if they do not exist`() {
        val dir   = Files.createTempDirectory("credential-store-mkdir").toFile()
        val file  = java.io.File(dir, "nested/deep/credentials")
        val store = CredentialStore(file)

        store.save("dev1", knownCreds())
        assertTrue(file.exists(), "file should have been created along with parent dirs")
        assertNull(store.load("dev2"), "only saved key should be present")
        val loaded = store.load("dev1") ?: error("load returned null")
        assertTrue(loaded.clientId.contentEquals(clientId))
    }

    // ── multiple devices coexist ──────────────────────────────────────────────

    @Test
    fun `multiple distinct deviceIds coexist in the same file`() {
        val file  = tempFile()
        val store = CredentialStore(file)

        val credsA = knownCreds()
        val credsB = HapCredentials(
            clientId   = byteArrayOf(0xFF.toByte()),
            clientLtsk = ByteArray(32) { 0x55.toByte() },
            clientLtpk = ByteArray(32) { 0x66.toByte() },
            atvId      = byteArrayOf(0x77.toByte()),
            atvLtpk    = ByteArray(32) { 0x88.toByte() },
        )

        store.save("device-alpha@10.0.0.1:49152", credsA)
        store.save("device-beta@10.0.0.2:49152",  credsB)

        val loadedA = store.load("device-alpha@10.0.0.1:49152") ?: error("null A")
        val loadedB = store.load("device-beta@10.0.0.2:49152")  ?: error("null B")

        assertTrue(loadedA.clientId.contentEquals(credsA.clientId), "A.clientId")
        assertTrue(loadedB.clientId.contentEquals(credsB.clientId), "B.clientId")
        assertNull(store.load("device-gamma@10.0.0.3:49152"),        "absent C should be null")
    }
}
