package dev.atvremote.app.conn

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the "install OK, crashes on launch" bug.
 *
 * Root cause: `ConnectionService` declares the `connectedDevice` foreground
 * service type and `MainActivity` `startForegroundService`s it, but the
 * manifest held NO permission Android 14 accepts as a prerequisite for that
 * FGS type — so on an API-34 device `startForeground(...,
 * FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` threw `SecurityException` and
 * crashed the app on launch (Robolectric does not enforce the API-34 FGS-type
 * permission check, which is why the original 48 green tests missed it).
 *
 * This asserts the merged manifest declares at least one connectedDevice-
 * qualifying permission. It FAILS on the unfixed manifest and PASSES on the
 * fixed one. (Manifest content is SDK-independent here — the @Config sdk only
 * provides a Robolectric runtime to read `PackageInfo`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionServiceManifestTest {

    /** Subset of the Android-14 `connectedDevice` FGS-type prerequisite
     *  permissions that is appropriate for a LAN/mDNS Companion remote. */
    private val connectedDeviceQualifiers = setOf(
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    )

    @Test fun manifestDeclaresAConnectedDeviceFgsQualifyingPermission() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requested = ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        // The connectedDevice FGS type + startForegroundService path is unusable
        // on Android 14 without one of these; its absence is the launch-crash.
        assertTrue(
            requested.any { it in connectedDeviceQualifiers },
            "AndroidManifest must declare a connectedDevice-qualifying " +
                "permission ($connectedDeviceQualifiers) so the " +
                "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE startForeground does " +
                "not throw SecurityException on Android 14. Declared = $requested",
        )
    }
}
