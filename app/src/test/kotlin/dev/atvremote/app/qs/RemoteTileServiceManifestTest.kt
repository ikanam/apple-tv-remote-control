package dev.atvremote.app.qs

import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import dev.atvremote.app.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteTileServiceManifestTest {

    @Test fun manifestRegistersQuickSettingsTileService() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(TileService.ACTION_QS_TILE).setPackage(ctx.packageName)
        val services = ctx.packageManager.queryIntentServices(
            intent,
            PackageManager.GET_META_DATA,
        )

        val tile = services
            .map { it.serviceInfo }
            .singleOrNull { it.name == RemoteTileService::class.java.name }

        assertNotNull(tile, "RemoteTileService must handle ACTION_QS_TILE")
        assertEquals(
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            tile.permission,
            "Quick Settings tiles must be protected by the system bind permission",
        )
        assertEquals(R.drawable.ic_qs_remote, tile.icon)
        assertEquals(
            R.string.quick_settings_tile_label,
            tile.labelRes,
            "Tile should expose a short label in the add-tile picker",
        )
        assertFalse(
            tile.metaData.getBoolean("android.service.quicksettings.TOGGLEABLE_TILE", true),
            "This tile launches the app; it should not render as an on/off toggle.",
        )
    }
}
