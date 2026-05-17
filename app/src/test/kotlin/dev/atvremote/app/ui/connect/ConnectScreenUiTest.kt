package dev.atvremote.app.ui.connect

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.atvremote.app.vm.DiscoveredDevice
import dev.atvremote.app.vm.PairingUiState
import dev.atvremote.protocol.AppleTvDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Reconciliation E: Compose UI test runs JVM-side under Robolectric (no
// emulator here). Mirrors RemoteScreenUiTest / PairingSheetUiTest (the
// reference templates); runs via :app:testDebugUnitTest, NOT
// connectedDebugAndroidTest. Assertions are real (assert on actual params /
// rendered content), never tautological.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectScreenUiTest {
    @get:Rule val rule = createComposeRule()

    private val living = AppleTvDevice(
        "lr@10.0.0.5:49153", "客厅", "10.0.0.5", 49153, "Apple TV 4K", true,
    )
    private val bedroom = AppleTvDevice(
        "br@10.0.0.6:49153", "主卧", "10.0.0.6", 49153, null, false,
    )

    private fun devices() = listOf(
        DiscoveredDevice(living, paired = true),
        DiscoveredDevice(bedroom, paired = false),
    )

    @Test fun showsDeviceNameModelHostAndNoSignalBars() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
            )
        }
        // The screen is a vertical scroll (short Robolectric viewport — same
        // convention as RemoteScreenUiTest): presence checks use assertExists,
        // and we performScrollTo before a displayed assertion.
        rule.onNodeWithText("客厅").assertIsDisplayed()
        rule.onNodeWithText("Apple TV 4K").assertExists()
        rule.onNodeWithText("10.0.0.5").performScrollTo().assertIsDisplayed()
        // bedroom: model is null → host shown on the model line instead.
        rule.onNodeWithText("主卧").assertExists()

        // RSSI / signal bars were intentionally dropped (spec reconciliation
        // §1) — there must be NO signal-bar node.
        rule.onAllNodesWithContentDescription("signal").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("RSSI").assertCountEquals(0)
    }

    @Test fun currentBadgeAndCheckOnlyForCurrentDevice() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                currentId = living.id,
                onClose = {},
            )
        }
        // CURRENT badge appears exactly once (only the current device).
        rule.onAllNodesWithText("CURRENT").assertCountEquals(1)
        rule.onNodeWithContentDescription("Current device").assertExists()
    }

    @Test fun pairedChipOnlyWhenPaired() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
            )
        }
        // Only the living-room device is paired → exactly one 已配对 chip.
        rule.onAllNodesWithText("已配对").assertCountEquals(1)
    }

    @Test fun tappingNonCurrentCardInvokesOnSelectDevice() {
        var selected: DiscoveredDevice? = null
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = { selected = it },
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                currentId = living.id,
                onClose = {},
            )
        }
        rule.onNodeWithText("主卧").performScrollTo().performClick()
        rule.waitForIdle()
        assertNotNull(selected)
        assertEquals(bedroom.id, selected!!.device.id)
    }

    @Test fun tappingCurrentCardInvokesOnClose() {
        var closed = false
        var selected: DiscoveredDevice? = null
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = { selected = it },
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                currentId = living.id,
                onClose = { closed = true },
            )
        }
        rule.onNodeWithText("客厅").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(closed, "tapping the current device card must invoke onClose")
        assertNull(selected, "the current device must NOT go through onSelectDevice")
    }

    @Test fun scanningEyebrowReflectsScanningAndCount() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = true,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
            )
        }
        rule.onNodeWithText("搜索中... 已发现 2").assertIsDisplayed()
    }

    @Test fun notScanningEyebrowShowsCount() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
            )
        }
        rule.onNodeWithText("已发现 2 台设备").assertIsDisplayed()
    }

    @Test fun statusPillDegradesToConnectedWhenSsidNull() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                ssid = null,
                localIp = null,
            )
        }
        rule.onNodeWithText("已连接").assertIsDisplayed()
    }

    @Test fun statusPillShowsSsidAndIpWhenProvided() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                ssid = "HomeNet_5G",
                localIp = "192.168.1.42",
            )
        }
        rule.onNodeWithText("HomeNet_5G").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.42 · 已连接").assertIsDisplayed()
    }

    @Test fun settingsGearInvokesOnOpenSettings() {
        var opened = false
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                onOpenSettings = { opened = true },
            )
        }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        assertTrue(opened, "settings gear must invoke onOpenSettings")
    }

    @Test fun firstRunModeShowsLogoAndStep01() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                // onClose == null → first-run mode.
            )
        }
        rule.onNodeWithText("TV Remote").assertIsDisplayed()
        rule.onNodeWithText("STEP 01 — DISCOVER").assertIsDisplayed()
        rule.onNodeWithText("寻找你的 Apple TV").assertIsDisplayed()
        // first-run has no back button.
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }

    @Test fun switcherModeShowsBackAndSwitchEyebrow() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                onClose = {},
            )
        }
        rule.onNodeWithText("切换设备").assertIsDisplayed()
        rule.onNodeWithText("SWITCH — SELECT DEVICE").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test fun backButtonInvokesOnClose() {
        var closed = false
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                onClose = { closed = true },
            )
        }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertTrue(closed)
    }

    @Test fun manualAddDialogOpensAndValidIpInvokesOnManualAdd() {
        var added: AppleTvDevice? = null
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                onManualAdd = { added = it },
            )
        }
        rule.onNodeWithText("+ 手动添加 IP 地址").performScrollTo().performClick()
        rule.waitForIdle()
        // dialog IP field present.
        rule.onNodeWithContentDescription("Manual IP address").assertExists()
        rule.onNodeWithContentDescription("Manual IP address").performTextInput("192.168.1.99")
        rule.onNodeWithText("添加").performClick()
        rule.waitForIdle()
        assertNotNull(added)
        assertEquals("192.168.1.99", added!!.host)
        assertEquals(49153, added!!.port)
    }

    @Test fun manualAddRejectsInvalidIpWithoutCrashOrCallback() {
        var added: AppleTvDevice? = null
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = null,
                onSubmitPin = {},
                onPairCancel = {},
                onManualAdd = { added = it },
            )
        }
        rule.onNodeWithText("+ 手动添加 IP 地址").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Manual IP address").performTextInput("not-an-ip")
        rule.onNodeWithText("添加").performClick()
        rule.waitForIdle()
        assertNull(added, "an invalid IP must NOT invoke onManualAdd")
        // inline error shown, dialog still open.
        rule.onNodeWithText("请输入有效的 IP 地址").assertIsDisplayed()
    }

    @Test fun pairingStateRendersPairingSheetOverlay() {
        rule.setContent {
            ConnectScreen(
                devices = devices(),
                scanning = false,
                onSelectDevice = {},
                pairingState = PairingUiState.AwaitingPin,
                onSubmitPin = {},
                onPairCancel = {},
                pairingDeviceName = "客厅",
            )
        }
        // PairingSheet sub-copy proves the overlay composed on top.
        rule.onNodeWithText("请输入电视屏幕上显示的 4 位配对码").assertIsDisplayed()
    }
}
