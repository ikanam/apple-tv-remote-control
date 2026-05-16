// app/src/test/kotlin/dev/atvremote/app/ui/AppNavTest.kt
package dev.atvremote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppNavTest {
    @Test fun navHasNoLauncherDestination() {
        val routes = AppDestinations.entries.map { it.route }
        assertFalse(routes.any { it.contains("launcher", ignoreCase = true) })
        assertEquals(
            listOf("hero", "devices", "pair", "keyboard", "tuning"),
            routes,
        )
    }
}
