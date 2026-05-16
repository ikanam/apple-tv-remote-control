package dev.atvremote.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Plan2ApiTest {
    @Test fun enumWireValuesAndOrdinals() {
        assertEquals(1, TouchPhase.Press.value)
        assertEquals(3, TouchPhase.Hold.value)
        assertEquals(4, TouchPhase.Release.value)
        assertEquals(5, TouchPhase.Click.value)
        assertEquals(0, InputAction.SingleTap.ordinal)
        assertEquals(1, InputAction.DoubleTap.ordinal)
        assertEquals(2, InputAction.Hold.ordinal)
        assertEquals(1, MediaCommand.Play.value)
        assertEquals(2, MediaCommand.Pause.value)
        assertEquals(3, MediaCommand.NextTrack.value)
        assertEquals(4, MediaCommand.PreviousTrack.value)
        val app = InstalledApp("com.netflix.Netflix", "Netflix")
        assertEquals("com.netflix.Netflix", app.bundleId)
        assertEquals("Netflix", app.name)
        assertTrue(PowerStatus.entries.containsAll(listOf(PowerStatus.On, PowerStatus.Off, PowerStatus.Unknown)))
        assertTrue(KeyboardFocusState.entries.containsAll(listOf(KeyboardFocusState.Focused, KeyboardFocusState.Unfocused)))
        assertTrue(ConnectionState.entries.containsAll(
            listOf(ConnectionState.Connected, ConnectionState.Reconnecting, ConnectionState.Disconnected)))
        assertFalse(CompanionUnavailableException::class.java.superclass == RuntimeException::class.java)
    }
}
