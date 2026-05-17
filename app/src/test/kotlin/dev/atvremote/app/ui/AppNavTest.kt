// app/src/test/kotlin/dev/atvremote/app/ui/AppNavTest.kt
package dev.atvremote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the Claude-Design reskin's restructured destination model (spec Screen
 * 3): exactly `[REMOTE, CONNECT, TUNING]` with routes `remote`/`connect`/
 * `tuning`. The old `hero`/`devices`/`pair`/`keyboard` (and any `launcher`)
 * pins are superseded — the keyboard is a RemoteScreen overlay, pairing is a
 * ConnectScreen overlay, and the device switcher is a CONNECT mode.
 */
class AppNavTest {
    @Test fun navIsExactlyRemoteConnectTuning() {
        val routes = AppDestinations.entries.map { it.route }
        assertEquals(
            listOf("remote", "connect", "tuning"),
            routes,
        )
    }

    @Test fun navHasNoSupersededDestinations() {
        val routes = AppDestinations.entries.map { it.route }
        listOf("launcher", "hero", "devices", "pair", "keyboard").forEach { gone ->
            assertFalse(
                routes.any { it.equals(gone, ignoreCase = true) },
                "route '$gone' must not exist after the reskin restructure",
            )
        }
    }
}
