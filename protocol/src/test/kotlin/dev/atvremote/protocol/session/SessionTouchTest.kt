package dev.atvremote.protocol.session

import dev.atvremote.protocol.TouchPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTouchTest {
    @Test fun touchDelegatesToHidT() = runTest {
        val fake = FakeProtocol()
        val s = CompanionSessionImpl(fake)
        s.touch(2000, -1, TouchPhase.Press)
        val (name, c) = fake.exchanges.last()
        assertEquals("_hidT", name)
        assertEquals(1000, c["_cx"])   // clamped high
        assertEquals(0, c["_cy"])      // clamped low
        assertEquals(TouchPhase.Press.value, c["_tPh"])
    }
}
