package dev.atvremote.app.ui.remote

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.RemoteViewModel
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Reconciliation E: Compose UI test runs JVM-side under Robolectric (no
// emulator here). Mirrors the old HeroScreenUiTest (the reference template);
// runs via :app:testDebugUnitTest, NOT connectedDebugAndroidTest.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteScreenUiTest {
    @get:Rule val rule = createComposeRule()

    // RemoteViewModel.pressButton/menu/... launch on viewModelScope
    // (Dispatchers.Main). Pin Main to a UnconfinedTestDispatcher so the
    // FakeSession records synchronously after a click + waitForIdle.
    private val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun fakeRemoteVm(session: FakeSession) = RemoteViewModel(
        sessionProvider = { session },
        isConnected = { true },
        onTap = {}, onEdge = {}, onSelect = {},
    )

    private fun keyboardVm() = KeyboardViewModel { null }

    @Test fun showsTouchpadSixControlsAndDeviceChipNoDpadNoMuteSiriApps() {
        val session = FakeSession()
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(session),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
            )
        }

        // The center region is a vertical scroll (robust on a short viewport —
        // see RemoteScreen KDoc); these existence checks don't require the node
        // on-screen, only present in the (scrollable) tree.
        rule.onNodeWithTag("trackpad").assertExists()
        rule.onNodeWithContentDescription("Back").assertExists()
        rule.onNodeWithContentDescription("TV/Home").assertExists()
        rule.onNodeWithContentDescription("Play/Pause").assertExists()
        rule.onNodeWithContentDescription("Volume Up").assertExists()
        rule.onNodeWithContentDescription("Volume Down").assertExists()
        rule.onNodeWithContentDescription("Keyboard").assertExists()
        rule.onNodeWithContentDescription("Power").assertExists()

        // device-switcher chip shows the name.
        rule.onNodeWithText("Living Room").assertIsDisplayed()

        // NO D-pad — directions are the touchpad zones, not buttons.
        rule.onAllNodesWithContentDescription("Up").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Down").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Left").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Right").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Select").assertCountEquals(0)
        // NO Mute / Siri / Apps (dropped from the final design).
        rule.onAllNodesWithContentDescription("Mute").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Siri").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Apps").assertCountEquals(0)
    }

    @Test fun deviceChipTapInvokesOnSwitchDevice() {
        var switched = false
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(FakeSession()),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = { switched = true },
            )
        }
        rule.onNodeWithText("Living Room").performClick()
        rule.waitForIdle()
        assertTrue(switched, "tapping the device chip must call onSwitchDevice")
    }

    @Test fun backInvokesMenu() {
        val session = FakeSession()
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(session),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
            )
        }
        rule.onNodeWithContentDescription("Back").performScrollTo().performClick()
        rule.waitForIdle()
        // RemoteViewModel.menu() => button(Menu,true)+button(Menu,false).
        assertEquals(RemoteButton.Menu, session.buttons.first().first)
    }

    @Test fun homePlayPauseVolumeRouteToViewModel() {
        val session = FakeSession()
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(session),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
            )
        }
        rule.onNodeWithContentDescription("TV/Home").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Play/Pause").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Volume Up").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Volume Down").performScrollTo().performClick()
        rule.waitForIdle()
        val pressed = session.buttons.map { it.first }.toSet()
        assertTrue(RemoteButton.Home in pressed)
        assertTrue(RemoteButton.PlayPause in pressed)
        assertTrue(RemoteButton.VolumeUp in pressed)
        assertTrue(RemoteButton.VolumeDown in pressed)
    }

    @Test fun powerTapWakesAndLongPressSleeps() {
        val session = FakeSession()
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(session),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
            )
        }
        rule.onNodeWithContentDescription("Power").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), session.powerCalls)
    }

    @Test fun keyboardDisabledWhenProbeUnavailableNoOverlay() {
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(FakeSession()),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
                // a NotImplementedError probe => keyboardAvailable == false.
                keyboardProbe = { throw NotImplementedError("stub") },
            )
        }
        rule.waitForIdle()
        // the cell stays (dimmed/disabled) — tapping must NOT open the overlay.
        rule.onNodeWithContentDescription("Keyboard").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("TV text field").assertCountEquals(0)
    }

    @Test fun keyboardEnabledOpensOverlay() {
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(FakeSession()),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
                keyboardProbe = { "" }, // available
            )
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Keyboard").performScrollTo().performClick()
        rule.waitForIdle()
        // overlay covers the screen ⇒ its field is on-screen (not in the scroll).
        rule.onNodeWithContentDescription("TV text field").assertIsDisplayed()
    }
}
