package dev.atvremote.protocol.connection

import dev.atvremote.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResilientSessionTest {
    /** Fake underlying CompanionSession that can flip its connectionState. */
    private class Fake : CompanionSession {
        val st = MutableStateFlow(ConnectionState.Connected)
        var buttons = 0; var touches = 0; var medias = 0; var clicks = 0
        override suspend fun button(button: RemoteButton, down: Boolean) { buttons++ }
        override suspend fun close() {}
        override suspend fun touch(x: Int, y: Int, phase: TouchPhase) { touches++ }
        override suspend fun click(action: InputAction) { clicks++ }
        override suspend fun textGet() = "x"
        override suspend fun textSet(text: String) {}
        override suspend fun textClear() {}
        override suspend fun textAppend(text: String) {}
        override val keyboardFocus = MutableStateFlow(KeyboardFocusState.Unfocused)
        override suspend fun listApps() = emptyList<InstalledApp>()
        override suspend fun launchApp(bundleId: String) {}
        override suspend fun power(on: Boolean) {}
        override suspend fun powerStatus() = PowerStatus.Unknown
        override suspend fun media(command: MediaCommand) { medias++ }
        override val connectionState: StateFlow<ConnectionState> get() = st
    }

    @Test fun touchDroppedAndKeyboardFailsWhileReconnecting() = runTest {
        val fake = Fake()
        val rs = ResilientSession(fake)
        rs.setState(ConnectionState.Reconnecting)           // supervisor drives RS state (NOT the delegate's)
        rs.touch(1, 1, TouchPhase.Press)                    // dropped silently
        assertEquals(0, fake.touches)
        assertEquals(ConnectionState.Reconnecting, rs.connectionState.value)
        assertFailsWith<CompanionUnavailableException> { rs.textSet("x") }
        assertFailsWith<CompanionUnavailableException> { rs.listApps() }
        assertFailsWith<CompanionUnavailableException> { rs.media(MediaCommand.Play) }
        assertFailsWith<CompanionUnavailableException> { rs.powerStatus() }
    }

    @Test fun buttonAndClickDroppedWhileReconnectingNotReplayed() = runTest {
        val fake = Fake()
        val rs = ResilientSession(fake)
        rs.setState(ConnectionState.Reconnecting)           // supervisor drives RS state
        rs.button(RemoteButton.Menu, true)                  // dropped (parity with touch)
        rs.click(InputAction.SingleTap)                     // dropped (parity with touch)
        assertEquals(0, fake.buttons)
        assertEquals(0, fake.clicks)
        rs.onReconnected()                                  // only flips RS state→Connected; no replay
        assertEquals(ConnectionState.Connected, rs.connectionState.value)
        assertEquals(0, fake.buttons)                       // NOT replayed
        assertEquals(0, fake.clicks)                        // NOT replayed
        rs.button(RemoteButton.Menu, true)                  // passthrough resumes once Connected
        assertEquals(1, fake.buttons)
    }

    @Test fun passthroughWhenConnected() = runTest {
        val fake = Fake()
        val rs = ResilientSession(fake)
        rs.touch(1, 1, TouchPhase.Press)
        rs.button(RemoteButton.Menu, true)
        assertEquals(1, fake.touches)
        assertEquals(1, fake.buttons)
        assertTrue(rs.connectionState.value == ConnectionState.Connected)
    }

    @Test fun closeCancelsSupervisor() = runTest {
        val fake = Fake()
        val rs = ResilientSession(fake)
        var cancelled = false
        rs.cancelSupervisor = { cancelled = true }
        rs.close()
        assertTrue(cancelled)
    }
}
