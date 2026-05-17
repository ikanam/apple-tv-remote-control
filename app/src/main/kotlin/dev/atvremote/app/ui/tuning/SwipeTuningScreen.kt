package dev.atvremote.app.ui.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atvremote.app.ui.remote.RemoteLayoutStyle
import dev.atvremote.app.ui.theme.DesignTokens

/**
 * Main trackpad settings. Only exposes the focus-step threshold used by HID
 * directional navigation; the retired touch-stream tuning harness is gone.
 */
@Composable
fun SwipeTuningScreen(
    layoutStyle: RemoteLayoutStyle,
    onLayoutStyleChange: (RemoteLayoutStyle) -> Unit,
    dragStepFraction: Float,
    onDragStepFractionChange: (Float) -> Unit,
) {
    // A Surface supplies the themed background AND LocalContentColor (without
    // it MaterialTheme leaves content color at the default black = invisible).
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DesignTokens.RemoteBgMid,
        contentColor = DesignTokens.TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "遥控器设置",
                color = DesignTokens.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "切换主界面的布局风格，并调整拖动多少距离触发一次焦点移动。" +
                    "调整即时生效。",
                color = DesignTokens.TextMuted55,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(24.dp))

            Text(
                text = "布局风格",
                color = DesignTokens.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StyleChoice(
                    label = "实体遥控器风格",
                    selected = layoutStyle == RemoteLayoutStyle.Physical,
                    onClick = { onLayoutStyleChange(RemoteLayoutStyle.Physical) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                StyleChoice(
                    label = "iPhone 风格",
                    selected = layoutStyle == RemoteLayoutStyle.Iphone,
                    onClick = { onLayoutStyleChange(RemoteLayoutStyle.Iphone) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))

            TuningParam(
                title = "步进阈值",
                value = dragStepFraction,
                description = "每次重复移动需要拖过的触控区比例。当前默认值 0.18，" +
                    "更小会更快连续移动，更大则更稳。",
                valueRange = 0.10f..0.50f,
                onValueChange = onDragStepFractionChange,
            )
        }
    }
}

@Composable
private fun StyleChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (selected) DesignTokens.TextPrimary else DesignTokens.TextMuted55,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2A2D) else Color(0xFF1C1C1E))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** One labelled slider with its title, live value and a plain-language note. */
@Composable
private fun TuningParam(
    title: String,
    value: Float,
    description: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Text(
        text = "$title — ${"%.2f".format(value)}",
        color = DesignTokens.TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = description,
        color = DesignTokens.TextMuted55,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    Spacer(Modifier.height(18.dp))
}
