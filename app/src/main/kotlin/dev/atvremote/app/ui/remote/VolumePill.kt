package dev.atvremote.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.atvremote.app.ui.icons.IconMinus
import dev.atvremote.app.ui.icons.IconPlus
import dev.atvremote.app.ui.theme.Brushes
import dev.atvremote.app.ui.theme.DesignTokens

/**
 * The single tall volume rocker — ported from `remote.jsx:169-207`.
 *
 *  - 80×172dp, radius 40dp, bg [Brushes.volumePill] (`radial circle at
 *    35% 20%, #232730→#14171d`) — remote.jsx:194-198.
 *  - two equal-flex halves: top `IconPlus` → [onUp], bottom `IconMinus` →
 *    [onDown], split by a 1dp `rgba(255,255,255,0.05)` divider
 *    (remote.jsx:203).
 *  - a pressed half tints `rgba(91,137,255,0.10)`; icon `#CFDAFF` when pressed
 *    else `rgba(255,255,255,0.78)` (remote.jsx:181-189). Icons are drawn at
 *    22px / stroke 2 exactly as `<IconCmp size={22} sw={2} />`.
 *
 * `contentDescription`s "Volume Up"/"Volume Down" are on the halves for a11y +
 * the rewritten UI tests. **No labels, no toasts** (final design).
 */
@Composable
fun VolumePill(
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = remember { Brushes.volumePill() }
    Column(
        modifier = modifier
            .width(80.dp)
            .height(172.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(bg),
    ) {
        Half(
            cd = "Volume Up",
            onTap = onUp,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { tint -> IconPlus(size = 22.dp, color = tint, strokeWidth = 2f) }

        // divider — remote.jsx:203 height 1, rgba(255,255,255,0.05).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.05f)),
        )

        Half(
            cd = "Volume Down",
            onTap = onDown,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { tint -> IconMinus(size = 22.dp, color = tint, strokeWidth = 2f) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Half(
    cd: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (pressed) {
        DesignTokens.AccentActiveText // #cfdaff
    } else {
        Color.White.copy(alpha = 0.78f)
    }
    Box(
        modifier = modifier
            .background(
                if (pressed) {
                    // rgba(91,137,255,0.10) — remote.jsx:181.
                    DesignTokens.AccentBlue.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onTap,
            )
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}
