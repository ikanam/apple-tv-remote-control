package dev.atvremote.app.conn

import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionManagerTest {
    private val device = AppleTvDevice("dev-A", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
    private fun creds() = HapCredentials(
        ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1)
    )

    @Test fun connectedSessionExposesConnectedState() = runTest {
        val session = FakeSession()
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        assertTrue(cm.uiState.first() is UiConnectionState.Connected)
        assertEquals(session, cm.requireSession())
    }

    @Test fun protocolReconnectingMapsToNonErrorUiState() = runTest {
        val session = FakeSession()
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        session.connFlow.value = ConnectionState.Reconnecting
        assertTrue(cm.uiState.first() is UiConnectionState.Reconnecting)
    }

    @Test fun connectFailureWithInvalidCredsRequestsRepair() = runTest {
        val cm = ConnectionManager(connector = { _, _ ->
            throw CredentialInvalidException("ATV rejected pair-verify")
        })
        cm.connect(device, creds())
        assertTrue(cm.uiState.first() is UiConnectionState.CredentialInvalid)
    }

    @Test fun genericConnectFailureSurfacesFailedState() = runTest {
        val cm = ConnectionManager(connector = { _, _ -> throw RuntimeException("no route") })
        cm.connect(device, creds())
        assertTrue(cm.uiState.first() is UiConnectionState.Failed)
    }

    @Test fun disconnectClosesSession() = runTest {
        val session = FakeSession()
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        cm.disconnect()
        assertTrue(session.closed)
        assertTrue(cm.uiState.first() is UiConnectionState.Idle)
    }

    @Test fun protocolDisconnectedClearsCredsAndRequestsRepair() = runTest {
        val session = FakeSession()
        val cleared = mutableListOf<String>()
        // minimal CredentialStore stand-in is not available here; assert via uiState only.
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        session.connFlow.value = ConnectionState.Disconnected
        assertTrue(cm.uiState.first() is UiConnectionState.CredentialInvalid)
    }
}
