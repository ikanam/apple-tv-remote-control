package dev.atvremote.protocol.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeProtocolSelfTest {
    @Test fun recordsExchangeAndEvents() = runTest {
        val fake = FakeProtocol()
        fake.onExchange = { name, _ -> mapOf("_c" to mapOf("echo" to name)) }
        val r = fake.exchange("_hidC", mapOf("_hBtS" to 1))
        assertEquals("_hidC", fake.exchanges.last().first)
        assertEquals("_hidC", (r["_c"] as Map<*, *>)["echo"])
        fake.sendEvent("_interest", mapOf("_regEvents" to listOf("x")))
        assertEquals("_interest", fake.sentEvents.last().first)
        val collected = mutableListOf<Pair<String, Map<String, Any?>>>()
        val j = launch { collected.add(fake.events.first()) }
        fake.emitEvent("_tiStarted", mapOf("_tiD" to byteArrayOf(1)))
        j.join()
        assertEquals("_tiStarted", collected.first().first)
    }
}
