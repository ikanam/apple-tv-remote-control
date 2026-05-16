package dev.atvremote.protocol.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventSubscriptionsTest {
    @Test fun subscribeSendsRegEventsAndTracks() = runTest {
        val fake = FakeProtocol()
        val subs = EventSubscriptions(fake)
        subs.subscribe("SystemStatus")
        subs.subscribe("TVSystemStatus")
        assertEquals("_interest", fake.sentEvents.first().first)
        assertEquals(listOf("SystemStatus"), fake.sentEvents.first().second["_regEvents"])
        assertEquals(setOf("SystemStatus", "TVSystemStatus"), subs.active())
    }

    @Test fun unsubscribeSendsDeregAndUntracks() = runTest {
        val fake = FakeProtocol()
        val subs = EventSubscriptions(fake)
        subs.subscribe("SystemStatus")
        subs.unsubscribe("SystemStatus")
        assertEquals("_interest", fake.sentEvents.last().first)
        assertEquals(listOf("SystemStatus"), fake.sentEvents.last().second["_deregEvents"])
        assertEquals(emptySet(), subs.active())
    }

    @Test fun restoreReSubscribesAllActive() = runTest {
        val fake = FakeProtocol()
        val subs = EventSubscriptions(fake)
        subs.subscribe("SystemStatus"); subs.subscribe("_iMC")
        fake.sentEvents.clear()
        subs.restore()
        val regd = fake.sentEvents
            .flatMap { (it.second["_regEvents"] as List<*>) }.toSet()
        assertEquals(setOf("SystemStatus", "_iMC"), regd)
    }

    @Test fun doubleSubscribeSendsInterestOnce() = runTest {
        val fake = FakeProtocol()
        val subs = EventSubscriptions(fake)
        subs.subscribe("SystemStatus")
        subs.subscribe("SystemStatus")
        assertEquals(1, fake.sentEvents.size)
        assertEquals(setOf("SystemStatus"), subs.active())
    }

    @Test fun unsubscribeWhenNotSubscribedIsNoOp() = runTest {
        val fake = FakeProtocol()
        val subs = EventSubscriptions(fake)
        subs.unsubscribe("SystemStatus")
        assertEquals(0, fake.sentEvents.size)
        assertEquals(emptySet(), subs.active())
    }
}
