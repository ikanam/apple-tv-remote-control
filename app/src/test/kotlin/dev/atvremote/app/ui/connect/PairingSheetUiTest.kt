package dev.atvremote.app.ui.connect

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.atvremote.app.vm.PairingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Reconciliation E: Compose UI test runs JVM-side under Robolectric (no
// emulator here). Mirrors KeyboardOverlayUiTest / the old PairScreenUiTest;
// runs via :app:testDebugUnitTest, NOT connectedDebugAndroidTest.
//
// The auto-advance / backspace / single-submit *logic* is exhaustively pinned
// by the deterministic PinBoxReducerTest below (pure reducer, no IME). The
// Compose tests here pin rendering + the end-to-end wiring through the real
// composable (4 boxes render, onValueChange→reducer→onSubmitPin once, state
// mapping for Connecting/Failed, 取消).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingSheetUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rendersFourBoxesAndCopy() {
        rule.setContent {
            PairingSheet(
                deviceName = "客厅",
                pairingState = PairingUiState.AwaitingPin,
                onSubmitPin = {},
                onCancel = {},
            )
        }
        rule.onNodeWithText("客厅").assertIsDisplayed()
        rule.onNodeWithText("请输入电视屏幕上显示的 4 位配对码").assertIsDisplayed()
        for (i in 1..4) {
            rule.onNodeWithContentDescription("PIN digit $i").assertIsDisplayed()
        }
    }

    @Test fun typingFourDigitsAutoAdvancesAndSubmitsExactlyOnce() {
        var submitCount = 0
        var lastCode: String? = null
        rule.setContent {
            PairingSheet(
                deviceName = "客厅",
                pairingState = PairingUiState.AwaitingPin,
                onSubmitPin = { submitCount++; lastCode = it },
                onCancel = {},
            )
        }
        // Each box accepts ONE digit; the reducer auto-advances focus. Driving
        // each box's BasicTextField directly is reliable under Robolectric's
        // headless IME (same approach KeyboardOverlayUiTest uses).
        rule.onNodeWithContentDescription("PIN digit 1").performTextInput("1")
        rule.onNodeWithContentDescription("PIN digit 2").performTextInput("2")
        rule.onNodeWithContentDescription("PIN digit 3").performTextInput("3")
        rule.onNodeWithContentDescription("PIN digit 4").performTextInput("4")
        rule.waitForIdle()

        assertEquals("1234", lastCode)
        assertEquals(1, submitCount) // exactly once per completed fill

        // A bare recomposition must not resubmit (force one via no-op idle).
        rule.waitForIdle()
        assertEquals(1, submitCount)
    }

    @Test fun nonDigitInputIsIgnoredNoSubmit() {
        var submitCount = 0
        rule.setContent {
            PairingSheet(
                deviceName = "客厅",
                pairingState = PairingUiState.AwaitingPin,
                onSubmitPin = { submitCount++ },
                onCancel = {},
            )
        }
        rule.onNodeWithContentDescription("PIN digit 1").performTextInput("a")
        rule.onNodeWithContentDescription("PIN digit 1").performTextInput("x")
        rule.waitForIdle()
        assertEquals(0, submitCount)
        // Box 1 stays empty (no digit accepted) — it shows no text node "a".
        rule.onAllNodesWithText("a").assertCountEquals(0)
    }

    @Test fun connectingDisablesBoxesNoSubmitAndShowsState() {
        var submitCount = 0
        rule.setContent {
            PairingSheet(
                deviceName = "客厅",
                pairingState = PairingUiState.Connecting,
                onSubmitPin = { submitCount++ },
                onCancel = {},
            )
        }
        rule.waitForIdle()
        // Boxes must be disabled (not focusable/typable) — assert the semantics
        // directly. (Compose's test harness *throws* on performTextInput into a
        // disabled node, which is itself the proof the field is locked.)
        for (i in 1..4) {
            rule.onNodeWithContentDescription("PIN digit $i").assertIsNotEnabled()
        }
        // Disabled ⇒ the onValueChange path is unreachable ⇒ never submits.
        assertEquals(0, submitCount)
        // Title still rendered.
        rule.onNodeWithText("客厅").assertIsDisplayed()
    }

    @Test fun failedShowsReasonClearsBoxesAndReentryWorks() {
        var submitCount = 0
        var lastCode: String? = null
        // A tiny holder so we can flip the state like the real VM would.
        rule.setContent {
            var st by remember {
                mutableStateOf<PairingUiState>(PairingUiState.AwaitingPin)
            }
            PairingSheet(
                deviceName = "客厅",
                pairingState = st,
                onSubmitPin = {
                    submitCount++
                    lastCode = it
                    // Simulate the VM rejecting the PIN.
                    st = PairingUiState.Failed("配对码错误")
                },
                onCancel = {},
            )
        }
        // First (wrong) attempt.
        rule.onNodeWithContentDescription("PIN digit 1").performTextInput("9")
        rule.onNodeWithContentDescription("PIN digit 2").performTextInput("9")
        rule.onNodeWithContentDescription("PIN digit 3").performTextInput("9")
        rule.onNodeWithContentDescription("PIN digit 4").performTextInput("9")
        rule.waitForIdle()
        assertEquals("9999", lastCode)
        assertEquals(1, submitCount)

        // Failed → reason shown, boxes cleared & re-enabled.
        rule.onNodeWithText("配对码错误").assertIsDisplayed()
        rule.onAllNodesWithText("9").assertCountEquals(0) // all boxes cleared

        // Re-entry must work and submit again exactly once (guard re-armed).
        rule.onNodeWithContentDescription("PIN digit 1").performTextInput("1")
        rule.onNodeWithContentDescription("PIN digit 2").performTextInput("2")
        rule.onNodeWithContentDescription("PIN digit 3").performTextInput("3")
        rule.onNodeWithContentDescription("PIN digit 4").performTextInput("4")
        rule.waitForIdle()
        assertEquals("1234", lastCode)
        assertEquals(2, submitCount)
    }

    @Test fun completedInvokesOnPairedOnceAndShowsAffirmation() {
        var pairedCount = 0
        rule.setContent {
            PairingSheet(
                deviceName = "客厅",
                pairingState = PairingUiState.Completed,
                onSubmitPin = {},
                onCancel = {},
                onPaired = { pairedCount++ },
            )
        }
        rule.waitForIdle()
        assertEquals(1, pairedCount)
        rule.onNodeWithText("已配对").assertIsDisplayed()
    }

    @Test fun cancelInvokesOnCancel() {
        var cancelled = false
        rule.setContent {
            PairingSheet(
                deviceName = "客厅",
                pairingState = PairingUiState.AwaitingPin,
                onSubmitPin = {},
                onCancel = { cancelled = true },
            )
        }
        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()
        assertTrue(cancelled, "取消 must invoke onCancel")
    }
}

// ---------------------------------------------------------------------------
// Deterministic pure-reducer tests — the auto-advance / backspace / single-
// submit invariants, with zero Compose/IME involvement (TDD core of T4a).
// ---------------------------------------------------------------------------
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PinBoxReducerTest {

    @Test fun typingADigitFillsBoxAndAdvancesFocus() {
        var s = PinBoxState()
        s = pinBoxReduce(s, 0, "1")
        assertEquals(listOf("1", "", "", ""), s.digits)
        assertEquals(1, s.focused) // advanced to box 2
        assertEquals(false, s.isComplete)

        s = pinBoxReduce(s, 1, "2")
        s = pinBoxReduce(s, 2, "3")
        s = pinBoxReduce(s, 3, "4")
        assertEquals(listOf("1", "2", "3", "4"), s.digits)
        assertEquals(3, s.focused) // last box clamps focus
        assertTrue(s.isComplete)
        assertEquals("1234", s.code)
    }

    @Test fun nonDigitInputIsIgnored() {
        var s = PinBoxState()
        s = pinBoxReduce(s, 0, "a")
        assertEquals(listOf("", "", "", ""), s.digits)
        assertEquals(0, s.focused) // focus unchanged, nothing entered
        assertEquals(false, s.isComplete)

        // Non-digit on a non-empty box must not wipe or advance it.
        s = pinBoxReduce(PinBoxState(digits = listOf("7", "", "", "")), 0, "7q")
        // "7q" → digitsOnly "7" → still a digit edit, last digit kept.
        assertEquals(listOf("7", "", "", ""), s.digits)
    }

    @Test fun overtypeReplacesDigitInPlace() {
        // BasicTextField hands the new full value; "53" on a box holding "5"
        // ⇒ keep the last digit (mirrors the prototype `v.slice(-1)`).
        val s = pinBoxReduce(PinBoxState(digits = listOf("5", "", "", "")), 0, "53")
        assertEquals(listOf("3", "", "", ""), s.digits)
        assertEquals(1, s.focused)
    }

    @Test fun backspaceOnNonEmptyBoxClearsThatBoxFocusStays() {
        val start = PinBoxState(digits = listOf("1", "2", "", ""), focused = 2)
        val s = pinBoxReduce(start, 1, "") // clear box 2 (non-empty)
        assertEquals(listOf("1", "", "", ""), s.digits)
        assertEquals(1, s.focused) // focus stays on this box
    }

    @Test fun backspaceOnEmptyBoxRetreatsAndClearsPriorBox() {
        // Box 3 is empty; backspace there retreats to box 2 AND clears box 2.
        val start = PinBoxState(digits = listOf("1", "2", "", ""), focused = 2)
        val s = pinBoxReduce(start, 2, "")
        assertEquals(listOf("1", "", "", ""), s.digits) // box 2 cleared
        assertEquals(1, s.focused) // focus retreated to box 2
    }

    @Test fun backspaceOnEmptyFirstBoxIsNoop() {
        val start = PinBoxState()
        val s = pinBoxReduce(start, 0, "")
        assertEquals(start, s) // nothing to retreat to
    }
}
