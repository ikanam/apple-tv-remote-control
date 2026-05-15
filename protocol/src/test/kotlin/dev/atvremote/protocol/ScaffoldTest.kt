package dev.atvremote.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

class ScaffoldTest {
    @Test fun moduleCompilesAndApiTypesExist() {
        val d = AppleTvDevice("id", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
        assertTrue(d.pairable)
        assertTrue(RemoteButton.Menu.hid == 5)
    }
}
