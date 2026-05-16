package dev.atvremote.app.conn

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.atvremote.app.data.AndroidKeyStoreTestProvider
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.Job
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
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

    // Reconciliation: ConnectionService.connectionManager resolves the SINGLETON
    // (application as AtvRemoteApp).graph.connectionManager, built in AppGraph with
    // the real default SessionConnector (AppleTvRemote.connect = real networking).
    // No production seam injects a fake connector through the singleton graph; adding
    // a test Application/AppGraph subclass would be heavyweight production-test-coupling.
    // connect()-delegation is already fully covered by ConnectionManagerTest (6 tests
    // with injected fake connectors). These two tests assert the genuinely NEW behavior
    // introduced by S3: the serviceScope lifecycle (T6 long-lived-scope contract) and
    // the non-blocking scheduling contract of launchConnect.

    @Test fun serviceScopeCancelledOnDestroy() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildService(
            ConnectionService::class.java, Intent(ctx, ConnectionService::class.java)
        ).create()
        val svc = controller.get()

        // serviceScope must be alive after creation
        val job = svc.serviceScope.coroutineContext[Job]!!
        assertTrue(job.isActive, "serviceScope must be active after create()")

        // onDestroy() must cancel the scope (and call super)
        controller.destroy()
        assertFalse(job.isActive, "serviceScope must be cancelled after destroy()")
    }

    @Test fun launchConnectIsNonBlockingAndSchedulesOnServiceScope() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildService(
            ConnectionService::class.java, Intent(ctx, ConnectionService::class.java)
        ).create()
        val svc = controller.get()
        val intent = Intent(ctx, ConnectionService::class.java)
        val local = svc.onBind(intent) as ConnectionService.LocalBinder

        val device = AppleTvDevice("x", "X", "0.0.0.0", 0, null, false)
        val creds = HapCredentials(ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1))

        // launchConnect must return without throwing on the calling thread (non-blocking:
        // it only calls serviceScope.launch{...}, never suspends or runs connect() inline)
        local.launchConnect(device, creds)

        // serviceScope must still be alive immediately after (scope not destroyed by launch)
        val job = svc.serviceScope.coroutineContext[Job]!!
        assertTrue(job.isActive, "serviceScope must remain active after launchConnect")

        // destroy() cancels the serviceScope, which cancels the scheduled child coroutine
        // before it runs AppleTvRemote.connect (no real networking occurs in unit tests)
        controller.destroy()
        assertFalse(job.isActive, "serviceScope cancelled on destroy after launchConnect")
    }
}
