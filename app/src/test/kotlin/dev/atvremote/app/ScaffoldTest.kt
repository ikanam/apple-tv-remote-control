package dev.atvremote.app

import dev.atvremote.protocol.RemoteButton
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaffoldTest {
    @Test fun appModuleSeesProtocolApi() {
        assertEquals(5, RemoteButton.Menu.hid)
        assertEquals(14, RemoteButton.PlayPause.hid)
    }
}
