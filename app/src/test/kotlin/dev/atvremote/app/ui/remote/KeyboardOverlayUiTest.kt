package dev.atvremote.app.ui.remote

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Reconciliation E: JVM-side Robolectric Compose test (no emulator). Mirrors
// the old KeyboardScreenUiTest — runs via :app:testDebugUnitTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardOverlayUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun counterReflectsLength() {
        rule.setContent {
            KeyboardOverlay(
                text = "hello",
                onTextChange = {},
                onClose = {},
            )
        }
        rule.onNodeWithText("5 chars · 实时发送到 Apple TV").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun typingCallsOnTextChange() {
        var pushed: String? = null
        rule.setContent {
            KeyboardOverlay(
                text = "ne",
                onTextChange = { pushed = it },
                onClose = {},
            )
        }
        rule.onNodeWithContentDescription("TV text field").assertIsDisplayed()
        // Robolectric's headless IME defaults the cursor to index 0, so place
        // it at end first so the test exercises an append (mirrors the old
        // KeyboardScreenUiTest rationale).
        rule.onNodeWithContentDescription("TV text field")
            .performTextInputSelection(TextRange(2))
        rule.onNodeWithContentDescription("TV text field").performTextInput("t")
        assertEquals("net", pushed)
    }

    @Test fun donePillClosesOverlay() {
        var closed = false
        rule.setContent {
            KeyboardOverlay(
                text = "",
                onTextChange = {},
                onClose = { closed = true },
            )
        }
        rule.onNodeWithText("完成").performClick()
        rule.waitForIdle()
        assertTrue(closed, "完成 must invoke onClose")
    }
}
