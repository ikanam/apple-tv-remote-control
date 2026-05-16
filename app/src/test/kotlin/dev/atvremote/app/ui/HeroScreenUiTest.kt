package dev.atvremote.app.ui

import androidx.compose.ui.test.assertCountEquals
// NOTE: in compose-bom 2024.08 (ui-test 1.6.8) `assertExists` is a MEMBER of
// SemanticsNodeInteraction, not a top-level function — calling `.assertExists()`
// needs no import (only `assertIsDisplayed`/`assertCountEquals` are top-level).
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.app.ui.hero.HeroScreen
import dev.atvremote.app.vm.RemoteViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Reconciliation E: Compose UI test runs JVM-side under Robolectric (no
// emulator here); the plan sanctions the base Robolectric Compose harness.
// Reference template for all :app Compose UI tests — Robolectric/JVM, see app/build.gradle.kts "Reconciliation E" (NOT connectedDebugAndroidTest).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeroScreenUiTest {
    @get:Rule val rule = createComposeRule()

    private fun fakeRemoteVm() = RemoteViewModel(
        sessionProvider = { FakeSession() },
        isConnected = { true },
        onTap = {}, onEdge = {}, onSelect = {},
    )

    @Test fun heroHasPowerKeyboardNoLauncherNoMuteNoSiri() {
        rule.setContent { HeroScreen(vm = fakeRemoteVm(), onOpenKeyboard = {}) }
        rule.onNodeWithTag("trackpad").assertIsDisplayed()
        rule.onNodeWithContentDescription("Power").assertExists()
        rule.onNodeWithContentDescription("Back").assertExists()
        rule.onNodeWithContentDescription("TV/Home").assertExists()
        rule.onNodeWithContentDescription("Play/Pause").assertExists()
        rule.onNodeWithContentDescription("Volume Up").assertExists()
        rule.onNodeWithContentDescription("Volume Down").assertExists()
        rule.onNodeWithContentDescription("Keyboard").assertExists()
        rule.onAllNodesWithContentDescription("Mute").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Siri").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Apps").assertCountEquals(0)
        rule.onNodeWithContentDescription("Up").assertExists()
        rule.onNodeWithContentDescription("Down").assertExists()
        rule.onNodeWithContentDescription("Left").assertExists()
        rule.onNodeWithContentDescription("Right").assertExists()
        rule.onNodeWithContentDescription("Select").assertExists()
    }
}
