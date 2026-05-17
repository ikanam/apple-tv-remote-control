package dev.atvremote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure decision-logic unit test (no Robolectric needed) for S5's first-run
 * initial-routing rule (spec Screen 3): if there is a reconnectable last
 * device, start on REMOTE (auto-reconnect is in flight, the banner shows
 * progress); otherwise start on CONNECT (a fresh install has nothing to
 * connect to and must not land on a dead REMOTE).
 */
class AppNavRoutingTest {
    @Test fun reconnectableLastDeviceStartsOnRemote() {
        assertEquals(AppDestinations.REMOTE, initialDestination(hasReconnectableLast = true))
    }

    @Test fun noReconnectableLastDeviceStartsOnConnect() {
        assertEquals(AppDestinations.CONNECT, initialDestination(hasReconnectableLast = false))
    }
}
