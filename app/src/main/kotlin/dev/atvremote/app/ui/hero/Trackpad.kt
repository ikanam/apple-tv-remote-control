package dev.atvremote.app.ui.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent

/**
 * Circular touch trackpad. Raw pointer samples are funneled into the pure
 * SwipeEngine (TDD'd in Task 3); emitted TouchEvents go to onEvent (Task 10 VM).
 */
@Composable
fun Trackpad(
    tuning: SwipeTuning,
    onEvent: (TouchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .aspectRatio(1f)
            .testTag("trackpad")
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(tuning) {
                val engine = SwipeEngine(tuning, size.width.toFloat(), size.height.toFloat())
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        val t0 = System.currentTimeMillis()
                        engine.onDown(down.position.x, down.position.y, t0).forEach(onEvent)
                        var dragging = true
                        while (dragging) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first()
                            val now = System.currentTimeMillis()
                            engine.onTick(now).forEach(onEvent)
                            if (ch.pressed) {
                                engine.onMove(ch.position.x, ch.position.y, now).forEach(onEvent)
                                ch.consume()
                            } else {
                                engine.onUp(ch.position.x, ch.position.y, now).forEach(onEvent)
                                var n = now + 8L
                                var guard = 0
                                while (engine.inertiaActive && guard < 240) {
                                    engine.onInertiaFrame(n).forEach(onEvent)
                                    n += 8L; guard++
                                }
                                ch.consume()
                                dragging = false
                            }
                        }
                    }
                }
            },
    ) {}
}
