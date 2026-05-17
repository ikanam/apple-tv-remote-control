package dev.atvremote.app.ui.remote

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atvremote.app.ui.theme.DesignTokens
import dev.atvremote.app.ui.theme.JetBrainsMonoFontFamily

/**
 * Full-screen text-input overlay — ported 1:1 from `remote.jsx:232-277`.
 *
 * Composited *on top of* [RemoteScreen] (not a separate nav destination): the
 * Remote owns whether it shows (the local Keyboard-button toggle OR the ATV
 * focusing a field via `KeyboardViewModel.state.visible`).
 *
 * ## Blur tradeoff (spec-named)
 * remote.jsx uses `background: rgba(8,9,12,0.85)` + `backdropFilter: blur(14px)`.
 * Compose's [Modifier.blur] with a `RenderEffect` only blurs **API ≥ 31**
 * (Android 12+); below that there is no backdrop-blur primitive, so we fall
 * back to a solid darker scrim ([DesignTokens.OverlayOpaqueFallback]) instead
 * of the translucent-blurred one. The translucency+blur is a polish detail —
 * the opaque fallback keeps the input legible and the layout identical, only
 * the see-through-blur aesthetic is lost on < API 31. (We blur the scrim layer
 * itself rather than the RemoteScreen content beneath: Compose cannot sample an
 * arbitrary ancestor's pixels as a backdrop, so this is the faithful-as-
 * possible equivalent of the CSS `backdropFilter`.)
 *
 * @param text the live keyboard text (= `KeyboardViewModel.state.text`).
 * @param onTextChange real-time edit callback (→ `keyboardVm.setText`).
 * @param onClose the `完成` pill / dismiss.
 */
@Composable
fun KeyboardOverlay(
    text: String,
    onTextChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scrimColor =
        if (blurSupported) DesignTokens.OverlayScrim85 else DesignTokens.OverlayOpaqueFallback

    Box(
        modifier = modifier
            .fillMaxSize()
            // Swallow taps so they don't fall through to the RemoteScreen
            // controls behind the overlay.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // Blurred scrim is a SEPARATE underlay layer (not a `blur` on the
        // container that also holds the input): blurring the whole subtree
        // pushes the text/field into a RenderEffect layer that the headless
        // test renderer reports as not-displayed, and visually it would smear
        // the typed text. Blurring only the backdrop is also the faithful
        // equivalent of the CSS `backdropFilter` (it blurs what's behind, not
        // the panel content). Below API 31 `blur` is a silent no-op, so the
        // opaque fallback scrim color compensates (documented above).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurSupported) Modifier.blur(14.dp) else Modifier)
                .background(scrimColor),
        )
        // Immersive: the scrim/blur Box above stays full-bleed (covers behind
        // the transparent status bar), but the CONTENT is statusBarsPadding()-
        // inset so the header (the 完成 pill) is not occluded by — and
        // untappable behind — the status bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp),
        ) {
            // --- header: 完成 pill only (the TEXT INPUT eyebrow was removed) -
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "完成",
                    // remote.jsx:253 `color: rgba(255,255,255,0.7)`.
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .border(
                            width = 1.dp,
                            color = DesignTokens.OutlineBorder12,
                            shape = RoundedCornerShape(100.dp),
                        )
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // --- input box — remote.jsx:257-274 ------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DesignTokens.InsetFieldBg) // #0e1014
                    .border(
                        width = 1.dp,
                        color = DesignTokens.AccentBorder30, // rgba(91,137,255,0.3)
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(16.dp),
            ) {
                Column {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(
                            color = DesignTokens.TextPrimary,
                            fontSize = 18.sp,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(DesignTokens.AccentBlue),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "TV text field" },
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "在此输入要发送到电视的文本...",
                                    color = DesignTokens.TextMuted35,
                                    style = LocalTextStyle.current.copy(fontSize = 18.sp),
                                )
                            }
                            inner()
                        },
                    )
                    Text(
                        text = "${text.length} chars · 实时发送到 Apple TV",
                        color = DesignTokens.TextMuted35,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
