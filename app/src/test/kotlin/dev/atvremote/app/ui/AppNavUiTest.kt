package dev.atvremote.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.atvremote.app.conn.MulticastLockHolder
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.RemoteViewModel
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Reconciliation E: Compose UI test runs JVM-side under Robolectric (no
// emulator here). Mirrors RemoteScreenUiTest / ConnectScreenUiTest (the
// reference templates); runs via :app:testDebugUnitTest, NOT
// connectedDebugAndroidTest. Pins the Claude-Design nav restructure (spec
// Screen 3): REMOTE / CONNECT / TUNING, multicast lock on CONNECT, the Remote
// device-switcher chip → CONNECT in switcher-overlay mode.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppNavUiTest {
    @get:Rule val rule = createComposeRule()

    private val device = AppleTvDevice("id1", "Living Room", "10.0.0.5", 49153, "AppleTV14,1", true)

    private fun discoveryVm(devices: List<AppleTvDevice> = listOf(device)) =
        DiscoveryViewModel(
            discovery = object : DeviceDiscovery {
                override fun devices(): Flow<List<AppleTvDevice>> = flowOf(devices)
            },
            pairedDeviceIds = { emptySet() },
        )

    private fun remoteVm() = RemoteViewModel(
        sessionProvider = { FakeSession() },
        isConnected = { true },
        onTap = {}, onEdge = {}, onSelect = {},
    )

    private fun keyboardVm() = KeyboardViewModel { null }

    private fun lock() = MulticastLockHolder(ApplicationProvider.getApplicationContext())

    @Test fun firstRunLandsOnConnectNotRemote() {
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(),
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Idle,
                deviceName = "Apple TV",
                connectedDeviceId = null,
                pairingDeviceName = null,
                initialDevices = true, // no reconnectable last → CONNECT
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                requestedDestination = null,
                multicastLock = lock(),
                haptics = null,
                keyboardProbe = { "" },
            )
        }
        // First-run ConnectScreen hero copy proves we landed on CONNECT (not a
        // dead REMOTE). The first-run mode shows the STEP-01 hero (currentId/
        // onClose null).
        rule.onNodeWithText("寻找你的 Apple TV").assertIsDisplayed()
    }

    @Test fun requestedRemoteRendersRemoteScreen() {
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(),
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Idle,
                deviceName = "Apple TV",
                connectedDeviceId = null,
                pairingDeviceName = null,
                initialDevices = true, // start on CONNECT…
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                // …then MainActivity drives a nav to REMOTE (the auto-reconnect
                // / post-pair success path).
                requestedDestination = NavRequest(AppDestinations.REMOTE, 1),
                multicastLock = lock(),
                haptics = null,
                keyboardProbe = { "" },
            )
        }
        // RemoteScreen's touchpad testTag proves we rendered the remote.
        rule.onNodeWithTag("trackpad").assertExists()
    }

    @Test fun multicastLockHeldOnConnectReleasedAfterNavigatingAway() {
        val h = lock()
        // Hoisted, non-composable backing state so the test can drive
        // navigation away from CONNECT after composition.
        val reqState = mutableStateOf<NavRequest?>(null)
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(),
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Idle,
                deviceName = "Apple TV",
                connectedDeviceId = null,
                pairingDeviceName = null,
                initialDevices = true, // start on CONNECT
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                requestedDestination = reqState.value,
                multicastLock = h,
                haptics = null,
                keyboardProbe = { "" },
            )
        }
        rule.waitForIdle()
        assertTrue(h.isHeld(), "multicast lock must be held while CONNECT is shown")

        // Navigate away (to TUNING) — the DisposableEffect (now on CONNECT)
        // onDispose must release it.
        rule.runOnUiThread { reqState.value = NavRequest(AppDestinations.TUNING, 1) }
        rule.waitForIdle()
        assertFalse(h.isHeld(), "multicast lock must be released after leaving CONNECT")
    }

    @Test fun remoteDeviceSwitcherChipOpensConnectInSwitcherOverlayMode() {
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(), // contains device id="id1"
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Connected(device),
                deviceName = "Living Room",
                connectedDeviceId = "id1",
                pairingDeviceName = null,
                initialDevices = false, // reconnectable → start on REMOTE
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                requestedDestination = null,
                multicastLock = lock(),
                haptics = null,
                keyboardProbe = { "" },
            )
        }
        rule.waitForIdle()
        // On REMOTE: the centered device-switcher chip shows the device name.
        rule.onNodeWithText("Living Room").assertIsDisplayed()
        // Tapping it opens CONNECT in switcher-overlay mode (currentId="id1",
        // onClose != null).
        rule.onNodeWithText("Living Room").performClick()
        rule.waitForIdle()
        // Switcher-overlay mode proof: the switch eyebrow + the connected
        // device's CURRENT badge (currentId == the device id → ConnectScreen
        // marks that card current).
        rule.onNodeWithText("SWITCH — SELECT DEVICE").assertIsDisplayed()
        rule.onNodeWithText("CURRENT").assertIsDisplayed()

        // Tapping the current device card invokes onClose → back to REMOTE
        // (ConnectScreen routes a current-card tap to onClose). Proven by the
        // touchpad reappearing.
        rule.onNodeWithText("CURRENT").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("trackpad").assertExists()
    }
}
