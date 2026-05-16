package dev.atvremote.protocol.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppsControllerTest {
    @Test fun listAppsParsesBundleNameMap() = runTest {
        val fake = FakeProtocol()
        fake.onExchange = { name, _ ->
            assertEquals("FetchLaunchableApplicationsEvent", name)
            mapOf("_c" to mapOf(
                "com.netflix.Netflix" to "Netflix",
                "com.apple.TVSettings" to "Settings"))
        }
        val apps = AppsController(fake).listApps()
        assertEquals(
            setOf("Netflix", "Settings"),
            apps.map { it.name }.toSet())
        assertEquals(
            "com.netflix.Netflix",
            apps.first { it.name == "Netflix" }.bundleId)
    }

    @Test fun launchByBundleIdUsesBundleIDKey() = runTest {
        val fake = FakeProtocol()
        AppsController(fake).launch("com.apple.TVSettings")
        assertEquals("_launchApp", fake.exchanges.last().first)
        assertEquals(mapOf("_bundleID" to "com.apple.TVSettings"), fake.exchanges.last().second)
    }

    @Test fun launchByUrlUsesUrlSKey() = runTest {
        val fake = FakeProtocol()
        AppsController(fake).launch("https://www.youtube.com/watch?v=x")
        assertEquals(mapOf("_urlS" to "https://www.youtube.com/watch?v=x"),
            fake.exchanges.last().second)
        val f2 = FakeProtocol()
        AppsController(f2).launch("youtube://watch")
        assertEquals(mapOf("_urlS" to "youtube://watch"), f2.exchanges.last().second)
    }

    @Test fun listAppsReturnsEmptyOnMissingC() = runTest {
        val fake = FakeProtocol() // default onExchange returns emptyMap() -> no "_c"
        assertEquals(emptyList<dev.atvremote.protocol.InstalledApp>(), AppsController(fake).listApps())
    }
}
