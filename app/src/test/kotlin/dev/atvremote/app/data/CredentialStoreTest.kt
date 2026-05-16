package dev.atvremote.app.data

import androidx.test.core.app.ApplicationProvider
import dev.atvremote.protocol.AppleTvDevice
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CredentialStoreTest {
    // Test-env reconciliation only (production KeystoreCipher/CredentialStore
    // are the plan's verbatim source): Robolectric 4.13 ships no AndroidKeyStore
    // JCA provider, so install a software stand-in and reset its keys per
    // method (Robolectric already gives each method a fresh app filesystem ->
    // fresh DataStore). Adds NO change to the 4 verbatim @Test bodies.
    @BeforeTest fun installAndroidKeyStore() {
        AndroidKeyStoreTestProvider.install()
        AndroidKeyStoreTestProvider.reset()
    }

    @AfterTest fun uninstallAndroidKeyStore() { AndroidKeyStoreTestProvider.uninstall() }

    private fun store() =
        CredentialStore(ApplicationProvider.getApplicationContext())

    @Test fun sealRoundTripPerDevice() = runTest {
        val s = store()
        s.save("dev-A", "BLOB-A-serialized")
        s.save("dev-B", "BLOB-B-serialized")
        assertEquals("BLOB-A-serialized", s.load("dev-A"))
        assertEquals("BLOB-B-serialized", s.load("dev-B"))
    }

    @Test fun ciphertextIsNotPlaintext() = runTest {
        val s = store()
        s.save("dev-A", "SECRET")
        assertEquals(false, s.rawStored("dev-A")?.contains("SECRET"))
    }

    @Test fun clearRemovesOnlyThatDevice() = runTest {
        val s = store()
        s.save("dev-A", "A"); s.save("dev-B", "B")
        s.clear("dev-A")
        assertNull(s.load("dev-A"))
        assertEquals("B", s.load("dev-B"))
    }

    @Test fun missingDeviceReturnsNull() = runTest {
        assertNull(store().load("nope"))
    }

    // ── S1: lastDevice / saveLastDevice ──────────────────────────────────────

    @Test fun lastDeviceRoundTrips() = runTest {
        val device = AppleTvDevice("dev-A", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
        val s = store()
        s.saveLastDevice(device)
        assertEquals(device, s.lastDevice())
    }

    @Test fun lastDeviceRoundTripsNullModelAndTrickyName() = runTest {
        val device = AppleTvDevice(
            "dev-B",
            "Bed|room, \"主\" 卧",
            "10.0.0.6",
            49153,
            null,
            false,
        )
        val s = store()
        s.saveLastDevice(device)
        val result = s.lastDevice()
        assertEquals(device, result)
        // Specifically confirm null model is NOT treated as empty string
        assertNull(result?.model)
    }

    @Test fun lastDeviceCiphertextIsNotPlaintext() = runTest {
        val device = AppleTvDevice("dev-A", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
        val s = store()
        s.saveLastDevice(device)
        val raw = s.rawStoredLastDevice()
        assertFalse(raw?.contains("10.0.0.5") == true, "host should not appear in plaintext")
        assertFalse(raw?.contains("Living Room") == true, "name should not appear in plaintext")
    }

    @Test fun lastDeviceMissingReturnsNull() = runTest {
        assertNull(store().lastDevice())
    }
}
