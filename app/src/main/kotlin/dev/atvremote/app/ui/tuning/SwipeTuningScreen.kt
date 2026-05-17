package dev.atvremote.app.ui.tuning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.ui.remote.Touchpad
import dev.atvremote.app.ui.theme.DesignTokens

/**
 * Debug A/B tuning harness (spec §5/§8). Each parameter now carries a plain-
 * language explanation of what it does and which direction does what, so the
 * screen is self-describing instead of a row of bare numbers.
 *
 * Immersive: the [Surface] background is full-bleed (flows behind the
 * transparent status bar); only the content is `statusBarsPadding()`-inset.
 */
@Composable
fun SwipeTuningScreen() {
    var gain by remember { mutableStateOf(SwipeTuning.DEFAULT.gain) }
    var exponent by remember { mutableStateOf(SwipeTuning.DEFAULT.velocityExponent) }
    var decay by remember { mutableStateOf(SwipeTuning.DEFAULT.inertiaDecay) }
    val log = remember { mutableStateOf(listOf<TouchEvent>()) }
    val tuning = SwipeTuning.DEFAULT.copy(
        gain = gain, velocityExponent = exponent, inertiaDecay = decay,
    )
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
                text = "滑动手感调试",
                color = DesignTokens.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "拖动下面的滑块实时调整触摸板的滑动手感，然后在底部的圆形" +
                    "触摸板上滑动来测试效果。调整即时生效，无需保存。",
                color = DesignTokens.TextMuted55,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(24.dp))

            TuningParam(
                title = "增益 · gain",
                value = gain,
                description = "手指移动距离换算成光标移动量的放大倍数。" +
                    "调大 → 轻轻一划就能跨很远（更灵敏）；调小 → 需要划更多" +
                    "才能移动同样距离（更精准、更稳）。",
                valueRange = 0.5f..6f,
                onValueChange = { gain = it },
            )
            TuningParam(
                title = "速度指数 · velocityExponent",
                value = exponent,
                description = "快速滑动时的加速强度（对滑动速度取的幂）。" +
                    "调大 → 快划时距离被进一步放大、慢划基本不变（快慢差异更" +
                    "明显）；调小 → 快慢更线性、更均匀。",
                valueRange = 0.5f..3f,
                onValueChange = { exponent = it },
            )
            TuningParam(
                title = "惯性衰减 · inertiaDecay",
                value = decay,
                description = "抬手之后惯性滑行每帧保留的速度比例。" +
                    "越接近 1 → 抬手后滑行得越久、越顺；越小 → 抬手后很快停下" +
                    "（几乎没有惯性）。",
                valueRange = 0.5f..0.99f,
                onValueChange = { decay = it },
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = "在下面的触摸板上滑动 / 点按来测试当前手感：",
                color = DesignTokens.TextMuted55,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Touchpad(
                tuning = tuning,
                onDirection = { btn ->
                    log.value = (log.value + TouchEvent.DirectionalStep(btn)).takeLast(50)
                },
                onTouchEvent = { e -> log.value = (log.value + e).takeLast(50) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "已捕获事件：${log.value.size}（最近 8 条）",
                color = DesignTokens.TextMuted55,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            log.value.takeLast(8).forEach {
                Text(
                    text = it.toString(),
                    color = DesignTokens.TextMuted45,
                    fontSize = 11.sp,
                )
            }
        }
    }
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
