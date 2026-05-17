package dev.atvremote.app.ui.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.atvremote.app.ui.theme.Brushes
import dev.atvremote.app.ui.theme.DesignTokens
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor

/**
 * Generic round remote button — ported from `remote.jsx:130-165`. **No label,
 * no toast** (the final design dropped both, per spec §"Screen 1" / the design
 * tokens table); the on/pressed state is purely the gradient + tint + scale +
 * blue glow.
 *
 *  - 80dp circle by default ([size]).
 *  - idle bg = [Brushes.roundButtonIdle] (`radial circle at 35% 30%,
 *    #232730→#14171d`); pressed/[active] bg = [Brushes.roundButtonActive]
 *    (`#2c3956→#1a2236`) — remote.jsx:140-142.
 *  - content tint = `#CFDAFF` when pressed/[active] else
 *    `rgba(255,255,255,0.78)` — remote.jsx:144.
 *  - scale `.95` while the finger is down (remote.jsx:150, transition ~.1s);
 *    blue inset ring + soft glow when pressed/[active] (remote.jsx:148).
 *
 * The icon/glyph is supplied by [content]; it is tinted via
 * [LocalContentColor] so the design icons (which paint `currentColor`) follow
 * the press/active state automatically. [contentDescription] is set as a
 * semantics property so a11y + the rewritten UI tests can find the button.
 */
@Composable
fun RemoteButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val on = pressed || active

    val idle = remember { Brushes.roundButtonIdle() }
    val activeBrush = remember { Brushes.roundButtonActive() }

    // scale .95 on press (remote.jsx:150 transition 'transform .1s').
    val btnScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "btnScale",
    )
    // glow alpha (remote.jsx:151 'box-shadow .15s').
    val glowAlpha by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "btnGlow",
    )

    // Recolor (owner): icon color is flat #FEFEFE in all states; press is
    // conveyed by scale + the blue glow ring drawn below.
    val tint = Color(0xFFFEFEFE)

    Box(
        modifier = modifier
            .size(size)
            .scale(btnScale)
            .clip(CircleShape)
            .background(if (on) activeBrush else idle, CircleShape)
            .drawBehind {
                if (glowAlpha > 0f) {
                    // remote.jsx:148 'inset 0 0 0 1px rgba(91,137,255,0.4),
                    // 0 0 18px rgba(91,137,255,0.2)'.
                    drawCircle(
                        color = DesignTokens.AccentBlue.copy(alpha = 0.2f * glowAlpha),
                        radius = size.toPx() / 2f + 6f,
                    )
                    drawCircle(
                        color = DesignTokens.AccentBlue.copy(alpha = 0.4f * glowAlpha),
                        radius = this.size.minDimension / 2f - 0.5f,
                        style = Stroke(width = 1f),
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null, // design has no ripple — state is the gradient
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint, content = content)
    }
}
