package dev.atvremote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import dev.atvremote.app.ui.keyboard.KeyboardScreen
import dev.atvremote.app.vm.KeyboardUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Reconciliation E (Plan-3 T14/T15): Compose UI test runs JVM-side under
// Robolectric (no emulator here). Mirrors HeroScreenUiTest; see
// app/build.gradle.kts "Reconciliation E" — runs via :app:testDebugUnitTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun visibleStateShowsFieldAndEditsPropagate() {
        var pushed: String? = null
        rule.setContent {
            KeyboardScreen(
                state = KeyboardUiState(visible = true, text = "ne"),
                onTextChange = { pushed = it },
            )
        }
        rule.onNodeWithContentDescription("TV text field").assertIsDisplayed()
        // Robolectric's headless IME defaults the cursor to index 0, so a bare
        // performTextInput("t") would PREPEND ("tne"); on a real device the
        // cursor sits at end. Place it at end first so the test exercises the
        // intended append. Assertion (verbatim) is unchanged.
        rule.onNodeWithContentDescription("TV text field")
            .performTextInputSelection(TextRange(2))
        rule.onNodeWithContentDescription("TV text field").performTextInput("t")
        assertEquals("net", pushed)
    }
}
