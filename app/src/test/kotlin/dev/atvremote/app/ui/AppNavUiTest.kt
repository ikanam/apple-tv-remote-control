package dev.atvremote.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.atvremote.app.conn.MulticastLockHolder
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.PairingUiState
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
// emulator here). Mirrors HeroScreenUiTest / PairScreenUiTest (the reference
// templates); runs via :app:testDebugUnitTest, NOT connectedDebugAndroidTest.
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

    @Test fun initialDevicesTrueRendersDevicesNotHero() {
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(),
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Idle,
                deviceName = "Apple TV",
                initialDevices = true,
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
        // T5 fully rewrites AppNav + these tests for the REMOTE/CONNECT
        // restructure. T4b interim: DEVICES now renders the ConnectScreen
        // (first-run mode) — assert its hero copy proves we landed on
        // DEVICES/ConnectScreen, not a dead HERO.
        rule.onNodeWithText("寻找你的 Apple TV").assertIsDisplayed()
    }

    @Test fun requestedPairWithAwaitingPinRendersPairScreen() {
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(),
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Idle,
                deviceName = "Apple TV",
                initialDevices = true,
                onSelectDevice = {},
                pairingState = PairingUiState.AwaitingPin,
                onSubmitPin = {},
                onPairCancel = {},
                // MainActivity drives navigation to PAIR via this hoisted signal.
                requestedDestination = NavRequest(AppDestinations.PAIR, 1),
                multicastLock = lock(),
                haptics = null,
                keyboardProbe = { "" },
            )
        }
        // T5 fully rewrites AppNav + these tests for the REMOTE/CONNECT
        // restructure. T4b interim: PAIR renders ConnectScreen with the
        // in-screen PairingSheet overlay (pairingState=AwaitingPin) — assert
        // the sheet's sub-copy proves the pairing overlay composed.
        rule.onNodeWithText("请输入电视屏幕上显示的 4 位配对码").assertIsDisplayed()
    }

    @Test fun multicastLockHeldOnDevicesReleasedAfterNavigatingAway() {
        val h = lock()
        // Hoisted, non-composable backing state so the test can drive
        // navigation away from DEVICES after composition.
        val reqState = mutableStateOf<NavRequest?>(null)
        rule.setContent {
            AppNav(
                discoveryVm = discoveryVm(),
                remoteVm = remoteVm(),
                keyboardVm = keyboardVm(),
                connectionState = UiConnectionState.Idle,
                deviceName = "Apple TV",
                initialDevices = true, // start on DEVICES
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
        assertTrue(h.isHeld(), "multicast lock must be held while DEVICES is shown")

        // Navigate away (to TUNING) — DisposableEffect onDispose must release it.
        rule.runOnUiThread { reqState.value = NavRequest(AppDestinations.TUNING, 1) }
        rule.waitForIdle()
        assertFalse(h.isHeld(), "multicast lock must be released after leaving DEVICES")
    }
}
