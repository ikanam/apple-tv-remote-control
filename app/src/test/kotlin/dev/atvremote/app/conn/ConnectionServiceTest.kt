package dev.atvremote.app.conn

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.atvremote.app.data.AndroidKeyStoreTestProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionServiceTest {
    // Test-env reconciliation only (identical pattern to CredentialStoreTest):
    // accessing connectionManager triggers AppGraph.credentialStore (lazy) →
    // KeystoreCipher → AndroidKeyStore, which is absent under Robolectric 4.13.
    // Install the software stand-in so the production code path runs unchanged.
    @BeforeTest fun installKeyStore() {
        AndroidKeyStoreTestProvider.install()
        AndroidKeyStoreTestProvider.reset()
    }
    @AfterTest fun uninstallKeyStore() { AndroidKeyStoreTestProvider.uninstall() }

    @Test fun bindReturnsManagerBinder() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildService(
            ConnectionService::class.java, Intent(ctx, ConnectionService::class.java)
        ).create()
        val service = controller.get()
        val binder = service.onBind(Intent(ctx, ConnectionService::class.java))
        val local = binder as ConnectionService.LocalBinder
        assertSame(service.connectionManager, local.manager())
        assertTrue(true)
    }
}
