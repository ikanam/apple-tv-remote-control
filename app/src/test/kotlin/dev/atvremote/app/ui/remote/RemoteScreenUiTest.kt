package dev.atvremote.app.ui.remote

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.RemoteViewModel
import dev.atvremote.protocol.KeyboardFocusState
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

    // Same construction KeyboardViewModelTest uses (KeyboardViewModel { s }):
    // the VM collects session.keyboardFocus, so driving session.focusFlow ==
    // the ATV focusing a text field. The VMs are logic-locked — not modified.
    private fun keyboardVm(session: FakeSession) = KeyboardViewModel { session }

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

    // Coverage relocated from ConnectScreenUiTest: the settings gear moved to
    // the main (Remote) screen's top bar.
    @Test fun settingsGearInvokesOnOpenSettings() {
        var opened = false
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(FakeSession()),
                keyboardVm = keyboardVm(),
                deviceName = "Living Room",
                onSwitchDevice = {},
                onOpenSettings = { opened = true },
            )
        }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        assertTrue(opened, "settings gear must invoke onOpenSettings")
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
        // tap → wake (RemoteViewModel.wake → power(true)).
        rule.onNodeWithContentDescription("Power").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), session.powerCalls)
        // long-press → sleep (RemoteViewModel.sleep → power(false)). The spec
        // REQUIRES both halves of the power gesture; lock the sleep path too.
        rule.onNodeWithContentDescription("Power").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(listOf(true, false), session.powerCalls)
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

    // Locks the real kb.visible OR-branch (overlayVisible = localKbOpen ||
    // kb.visible): with a NotImplementedError probe the local toggle can NEVER
    // open it, so the overlay appearing proves it was driven purely by the ATV
    // focusing a field (KeyboardViewModel.state.visible) — and that 完成
    // (which only clears the local toggle) does NOT hide it while the ATV
    // still has focus (we do not fight the VM).
    @Test fun atvFocusAutoShowsOverlayAndLocalCloseDoesNotFightVm() {
        val session = FakeSession()
        rule.setContent {
            RemoteScreen(
                remoteVm = fakeRemoteVm(session),
                keyboardVm = keyboardVm(session),
                deviceName = "Living Room",
                onSwitchDevice = {},
                // stub probe ⇒ keyboardAvailable == false ⇒ localKbOpen path
                // is unreachable; only kb.visible can show the overlay.
                keyboardProbe = { throw NotImplementedError("stub") },
            )
        }
        rule.waitForIdle()
        // not focused yet ⇒ no overlay.
        rule.onAllNodesWithContentDescription("TV text field").assertCountEquals(0)

        // ATV focuses a text field (same hook KeyboardViewModelTest drives).
        session.focusFlow.value = KeyboardFocusState.Focused
        rule.waitForIdle()
        rule.onNodeWithContentDescription("TV text field").assertIsDisplayed()

        // 完成 clears only the local toggle; kb.visible is still true so the
        // overlay must stay.
        rule.onNodeWithText("完成").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("TV text field").assertIsDisplayed()

        // ATV unfocuses ⇒ kb.visible false, local toggle never set ⇒ gone.
        session.focusFlow.value = KeyboardFocusState.Unfocused
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("TV text field").assertCountEquals(0)
    }
}
