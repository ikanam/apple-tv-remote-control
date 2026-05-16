package dev.atvremote.protocol.session

import dev.atvremote.protocol.MediaCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaControllerTest {
    @Test fun eachCommandSendsMccWithItsValue() = runTest {
        for (mc in MediaCommand.entries) {
            val fake = FakeProtocol()
            MediaController(fake).media(mc)
            assertEquals("_mcc", fake.exchanges.last().first)
            assertEquals(mc.value, fake.exchanges.last().second["_mcc"])
        }
        // explicit value lock
        val f = FakeProtocol()
        MediaController(f).media(MediaCommand.PreviousTrack)
        assertEquals(4, f.exchanges.last().second["_mcc"])
    }
}
