package dev.atvremote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure decision-logic unit test (no Robolectric needed) for S5's first-run
 * initial-routing rule (spec §2): if there is a reconnectable last device,
 * start on HERO (auto-reconnect is in flight, the banner shows progress);
 * otherwise start on DEVICES (a fresh install has nothing to connect to and
 * must not land on a dead HERO).
 */
class AppNavRoutingTest {
    @Test fun reconnectableLastDeviceStartsOnHero() {
        assertEquals(AppDestinations.HERO, initialDestination(hasReconnectableLast = true))
    }

    @Test fun noReconnectableLastDeviceStartsOnDevices() {
        assertEquals(AppDestinations.DEVICES, initialDestination(hasReconnectableLast = false))
    }
}
