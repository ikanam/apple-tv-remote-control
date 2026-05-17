package dev.atvremote.app.ui.connect

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atvremote.app.ui.theme.DesignTokens
import dev.atvremote.app.ui.theme.JetBrainsMonoFontFamily
import dev.atvremote.app.vm.PairingUiState

/**
 * Bottom-sheet 4-digit pairing PIN entry — ported 1:1 from
 * `connect.jsx:24-109` (the `PairingSheet` React component), with the real-
 * state additions the prototype lacked (it had no `Connecting`/`Failed`):
 * driven by [PairingViewModel]'s [PairingUiState]
 * (`docs/.../2026-05-17-claude-design-ui-reskin.md` Screen 2 + reconciliation
 * §2: "4 fixed digit boxes, per-digit auto-advance").
 *
 * Rendered as an overlay *within* ConnectScreen (T4b wires the AppNav side).
 *
 * ## Blur tradeoff (spec-named)
 * connect.jsx:43-44 uses `background: rgba(8,9,12,0.72)` + `backdropFilter:
 * blur(14px)`. Compose [Modifier.blur] only produces a real backdrop blur on
 * **API ≥ 31** (Android 12+); below that there is no backdrop-blur primitive,
 * so we drop the blur and keep just the solid 0.72 scrim — the see-through
 * blur is pure polish, the scrim alone keeps the sheet legible and the layout
 * identical (same convention as `KeyboardOverlay`).
 *
 * ## State mapping ([pairingState])
 *  - [PairingUiState.Connecting] — boxes disabled (not focusable/typable,
 *    dimmed) + a small spinner near the title.
 *  - [PairingUiState.AwaitingPin] — boxes enabled, box 1 focused; a complete
 *    4-digit fill submits once via [onSubmitPin].
 *  - [PairingUiState.Completed] — a brief `已配对` affirmation; [onPaired] is
 *    invoked once on entering Completed (default no-op — T4b/T5 wires the
 *    actual connect+navigation; this composable does NOT drive connection).
 *  - [PairingUiState.Failed] — shows `reason` in soft red, clears all boxes,
 *    re-enables for a retry; `取消` still cancels.
 *
 * @param deviceName shown as the 22sp sheet title.
 * @param pairingState the live [PairingViewModel.state] value.
 * @param onSubmitPin invoked exactly once with the concatenated 4-char code
 *        when all boxes are filled (guarded against recomposition resubmit).
 * @param onCancel the `取消` button / dismiss.
 * @param onPaired invoked once on entering [PairingUiState.Completed]. Default
 *        no-op — must NOT itself drive connection (owned by MainActivity).
 */
@Composable
fun PairingSheet(
    deviceName: String,
    pairingState: PairingUiState,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit,
    onPaired: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val enabled = pairingState is PairingUiState.AwaitingPin ||
        pairingState is PairingUiState.Failed
    val connecting = pairingState is PairingUiState.Connecting
    val completed = pairingState is PairingUiState.Completed
    val failed = pairingState as? PairingUiState.Failed

    // The 4-box model lives here; all transitions go through the pure
    // [pinBoxReduce] reducer (unit-tested in PinBoxReducerTest) so the
    // auto-advance / backspace / single-submit logic is deterministic and not
    // entangled with Compose recomposition.
    var boxes by remember { mutableStateOf(PinBoxState()) }

    // Single-submit guard: submit exactly once per completed fill. Reset when
    // the boxes are edited (handled in onChange below) or on Failed (re-arm
    // for the retry) — never resubmit on a bare recomposition.
    var submitted by remember { mutableStateOf(false) }

    // Clear + re-arm on Failed (and re-enable). Keyed on the reason so a fresh
    // Failed(reason) after a retry clears again.
    LaunchedEffect(failed?.reason) {
        if (failed != null) {
            boxes = PinBoxState()
            submitted = false
        }
    }

    // onPaired fires once on entering Completed. Default is a no-op; T4b/T5
    // wires the real connect+close. This composable must NOT connect itself.
    LaunchedEffect(completed) {
        if (completed) onPaired()
    }

    val focusRequesters = remember { List(PIN_LEN) { FocusRequester() } }

    // Box 1 auto-focus on first show when entry is live (AwaitingPin). Re-runs
    // when it transitions to enabled (e.g. Connecting → AwaitingPin).
    LaunchedEffect(enabled, boxes.focused) {
        if (enabled) {
            runCatching { focusRequesters[boxes.focused].requestFocus() }
        }
    }

    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier = modifier
            .fillMaxSize()
            // Swallow taps so they don't fall through to ConnectScreen behind.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Scrim is a SEPARATE underlay layer (blurring the whole subtree pushes
        // the sheet content into a RenderEffect layer the headless test
        // renderer reports not-displayed, and visually smears it). Below API 31
        // `blur` is a silent no-op, so the solid 0.72 scrim compensates.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurSupported) Modifier.blur(14.dp) else Modifier)
                .background(DesignTokens.SheetScrim72),
        )

        // Slide-up entrance ~300ms — spec "Press timing": sheet slide-up
        // ~300ms cubic-bezier(.2,.7,.2,1) (connect.jsx:58 `slup .3s`). tween's
        // default easing is the closest stock curve; the exact cubic-bezier is
        // a polish detail.
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 300),
                initialOffsetY = { it },
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(DesignTokens.SheetBg) // #16181d
                    // connect.jsx:59 subtle top border (1px rgba(255,255,255,0.06)).
                    .border(
                        width = 1.dp,
                        color = DesignTokens.HairlineBorder,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    // connect.jsx:57 padding: '20px 24px 32px'.
                    .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 32.dp),
            ) {
                // --- grip — connect.jsx:62 (40×4, white@0.15, r2, mb18) ------
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 18.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f)),
                )

                // --- eyebrow `配对设备` — connect.jsx:63 --------------------
                Text(
                    text = "配对设备", // CSS textTransform:uppercase — no Latin glyphs to case
                    color = DesignTokens.AccentLight, // #8fb8ff
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    // connect.jsx:63 letterSpacing 0.18em → 0.18 × 11sp.
                    letterSpacing = (0.18f * 11).sp,
                )

                // --- title row: device name (+ spinner / 已配对) ------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp), // connect.jsx:66 marginTop:6
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (completed) "已配对" else deviceName,
                        color = DesignTokens.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = DesignTokens.AccentLight,
                            strokeWidth = 2.dp,
                        )
                    }
                }

                // --- sub-copy — connect.jsx:69 -------------------------------
                Text(
                    text = "请输入电视屏幕上显示的 4 位配对码",
                    color = DesignTokens.TextMuted55, // rgba(255,255,255,0.55)
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp), // connect.jsx:69 marginTop:4
                )

                // --- Failed reason — real-state addition (prototype had none).
                // Soft red Color(0xFFFF6B6B): T1 maps Material `error`→AccentBlue
                // and the design has NO Failed state, so this is the single
                // sanctioned non-token literal (spec-flagged).
                if (failed != null) {
                    Text(
                        text = failed.reason,
                        color = Color(0xFFFF6B6B),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // --- 4 digit boxes — connect.jsx:73-95 -----------------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp, bottom = 24.dp), // connect.jsx:73
                    horizontalArrangement = Arrangement.spacedBy(10.dp), // gap 10
                ) {
                    for (i in 0 until PIN_LEN) {
                        val ch = boxes.digits[i]
                        BasicTextField(
                            value = ch,
                            onValueChange = onChange@{ raw ->
                                if (!enabled) return@onChange
                                val before = boxes
                                boxes = pinBoxReduce(before, i, raw)
                                // Any user edit re-arms the single-submit guard
                                // unless it's the completing edit itself.
                                if (!boxes.isComplete) submitted = false
                                if (boxes.isComplete && !submitted) {
                                    submitted = true
                                    onSubmitPin(boxes.code)
                                }
                            },
                            enabled = enabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            cursorBrush = SolidColor(DesignTokens.AccentBlue),
                            textStyle = TextStyle(
                                color = if (enabled) {
                                    DesignTokens.TextPrimary
                                } else {
                                    DesignTokens.TextMuted35 // dimmed while disabled
                                },
                                fontSize = 28.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontFamily = JetBrainsMonoFontFamily,
                                textAlign = TextAlign.Center,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .focusRequester(focusRequesters[i])
                                .clip(RoundedCornerShape(14.dp))
                                .background(DesignTokens.InsetFieldBg) // #0e1014
                                .border(
                                    width = 1.5.dp,
                                    // border #5b89ff when filled else white@0.10
                                    color = if (ch.isNotEmpty()) {
                                        DesignTokens.AccentBlue
                                    } else {
                                        DesignTokens.InactiveBorder10
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .semantics {
                                    contentDescription = "PIN digit ${i + 1}"
                                },
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) { inner() }
                            },
                        )
                    }
                }

                // --- 取消 — connect.jsx:97-105 -------------------------------
                Text(
                    text = "取消",
                    color = Color.White.copy(alpha = 0.7f), // rgba(255,255,255,0.7)
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = DesignTokens.OutlineBorder12, // white@0.12
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable(role = Role.Button, onClick = onCancel)
                        // vertical centering of the 14sp label in the 48dp pill
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pure 4-box PIN reducer — extracted so the auto-advance / backspace / single-
// submit logic is deterministically unit-testable (PinBoxReducerTest) without
// Robolectric's headless-IME quirks. The Compose layer above only renders this
// state and feeds raw BasicTextField onValueChange strings into the reducer.
// ---------------------------------------------------------------------------

/** Number of PIN digit boxes (Companion PIN is 4 digits — spec §2). */
internal const val PIN_LEN = 4

/**
 * Immutable model for the 4 PIN boxes.
 *
 * @param digits one entry per box; each is `""` or a single `0-9` char.
 * @param focused index of the box that should hold focus next.
 */
internal data class PinBoxState(
    val digits: List<String> = List(PIN_LEN) { "" },
    val focused: Int = 0,
) {
    /** True once every box holds a digit. */
    val isComplete: Boolean get() = digits.all { it.isNotEmpty() }

    /** The concatenated 4-char code (only meaningful when [isComplete]). */
    val code: String get() = digits.joinToString("")
}

/**
 * Pure transition for a single box's `BasicTextField` change.
 *
 * `BasicTextField` hands us the box's *new* full value [raw]:
 *  - **Typed a digit** → `raw` ends with a fresh `0-9` char. Take the last
 *    char (so an overtype on a filled box replaces it — mirrors the prototype
 *    `v.slice(-1)`), keep only digits, fill box [index], and advance focus to
 *    the next box (clamped at the last).
 *  - **Non-digit** input → ignored (the box keeps its previous value, focus
 *    unchanged) — `inputMode="numeric"` in the prototype + spec "0-9 only".
 *  - **Cleared to empty** (`raw == ""`) → this is the Backspace path. If the
 *    box was **already empty**, retreat focus to the previous box AND clear
 *    *that* previous box (spec: "Backspace on an EMPTY box moves focus to the
 *    previous box and clears it"). If the box was **non-empty**, just clear
 *    *this* box (focus stays) — "backspace on a non-empty box clears that box".
 */
internal fun pinBoxReduce(state: PinBoxState, index: Int, raw: String): PinBoxState {
    val digitsOnly = raw.filter { it.isDigit() }

    if (digitsOnly.isEmpty()) {
        // Empty result ⇒ a clear/backspace (or a pure non-digit that filtered
        // to nothing while the box was empty — same no-op-ish handling).
        val wasEmpty = state.digits[index].isEmpty()
        return if (wasEmpty && index > 0) {
            // Backspace on an already-empty box: retreat + clear the prior box.
            val next = state.digits.toMutableList()
            next[index - 1] = ""
            state.copy(digits = next, focused = index - 1)
        } else if (!wasEmpty) {
            // Backspace on a non-empty box: clear just this box, focus stays.
            val next = state.digits.toMutableList()
            next[index] = ""
            state.copy(digits = next, focused = index)
        } else {
            // Already-empty first box, nothing to do.
            state
        }
    }

    if (raw.filter { !it.isDigit() }.isNotEmpty() && digitsOnly.isEmpty()) {
        // Pure non-digit input on a non-empty box: ignore entirely.
        return state
    }

    // A digit was entered: take the last digit char (overtype-replaces),
    // fill this box, advance focus.
    val ch = digitsOnly.last().toString()
    val next = state.digits.toMutableList()
    next[index] = ch
    val nextFocus = if (index < PIN_LEN - 1) index + 1 else index
    return state.copy(digits = next, focused = nextFocus)
}
