package dev.atvremote.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.atvremote.app.ui.pair.PairScreen
import dev.atvremote.app.vm.PairingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Reconciliation E (Plan-3 T14/T15): Compose UI test runs JVM-side under
// Robolectric (no emulator here); the plan sanctions the base Robolectric
// Compose harness. Mirrors HeroScreenUiTest (the reference template); see
// app/build.gradle.kts "Reconciliation E" — runs via :app:testDebugUnitTest,
// NOT :app:connectedDebugAndroidTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pinEntryAndSubmit() {
        var submitted: String? = null
        rule.setContent {
            PairScreen(
                state = PairingUiState.AwaitingPin,
                onSubmitPin = { submitted = it },
                onCancel = {},
            )
        }
        rule.onNodeWithText("Enter the code shown on your Apple TV").assertIsDisplayed()
        rule.onNodeWithContentDescription("PIN field").performTextInput("4821")
        rule.onNodeWithText("Pair").performClick()
        assertEquals("4821", submitted)
    }

    @Test fun failureShowsReason() {
        rule.setContent {
            PairScreen(
                state = PairingUiState.Failed("Incorrect PIN"),
                onSubmitPin = {},
                onCancel = {},
            )
        }
        rule.onNodeWithText("Incorrect PIN").assertIsDisplayed()
    }
}
