package dev.atvremote.app.ui.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.ui.theme.Brushes
import dev.atvremote.app.ui.theme.DesignTokens
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure discrete-tap zone hit-test — the exact port of `remote.jsx:15-34`.
 *
 * [dx]/[dy] are the tap offset **from the touchpad center** (screen px, +y =
 * down, identical to the JSX `clientY` math). [width] is the touchpad box
 * width in px (`rect.width`).
 *
 *  - `r = hypot(dx,dy)`; `innerR = width * 0.18`
 *  - `r < innerR`            → [RemoteButton.Select]   (the center "OK")
 *  - else `ang = atan2(dy,dx)·180/π`:
 *      - `[-45, 45)`   → [RemoteButton.Right]
 *      - `[45, 135)`   → [RemoteButton.Down]
 *      - `[-135, -45)` → [RemoteButton.Up]
 *      - else          → [RemoteButton.Left]
 *
 * Boundaries are inclusive-lower / exclusive-upper exactly as the JSX `if`
 * chain; `r == innerR` falls through to the angle test (JSX uses strict `<`).
 */
internal fun zoneFor(dx: Float, dy: Float, width: Float): RemoteButton {
    val r = hypot(dx, dy)
    val innerR = width * 0.18f
    if (r < innerR) return RemoteButton.Select
    val ang = atan2(dy, dx) * 180f / Math.PI.toFloat()
    return when {
        ang >= -45f && ang < 45f -> RemoteButton.Right
        ang >= 45f && ang < 135f -> RemoteButton.Down
        ang >= -135f && ang < -45f -> RemoteButton.Up
        else -> RemoteButton.Left
    }
}

private fun RemoteButton.glowEdge(): Brushes.GlowEdge? = when (this) {
    RemoteButton.Up -> Brushes.GlowEdge.Up
    RemoteButton.Down -> Brushes.GlowEdge.Down
    RemoteButton.Left -> Brushes.GlowEdge.Left
    RemoteButton.Right -> Brushes.GlowEdge.Right
    else -> null
}

/**
 * The Apple-TV touchpad — visuals lifted pixel-for-pixel from
 * `remote.jsx:42-124` (T1 [Brushes] supply the gradients), with the
 * reskin's **reconciliation interaction contract**:
 *
 *  - a *discrete tap* (finger up before exceeding [LocalViewConfiguration]'s
 *    touch-slop) is hit-tested with [zoneFor] and reported via [onDirection],
 *    driving the matching active-dot / edge-glow / center-OK visual for
 *    ~180ms (`remote.jsx:12`);
 *  - any gesture that *moves beyond touch-slop* is a **drag**: it is fed to
 *    the existing pure [SwipeEngine] and its decisions are emitted through
 *    [onTouchEvent] (continuous Siri-remote-equivalent touch — a preserved
 *    real capability, never a regression). A drag NEVER also fires a
 *    tap-zone [onDirection].
 *
 * The engine is driven on every gesture (down/move + tick) so its slop/long-
 * press accounting stays consistent; on finger-up the classification decides
 * which path "wins": a drag flushes the engine's terminal events + inertia, a
 * tap drops them and fires the design's zone direction instead (so the
 * SwipeEngine `Tap` and the zone press can never double-fire).
 *
 * Slop source: Compose's [LocalViewConfiguration.touchSlop] (the platform
 * `ViewConfiguration` slop, ~the same threshold Compose's own `detectDrag*`
 * uses) — accumulated as the unsigned path length since touch-down; once it
 * exceeds slop the gesture is irrevocably a drag for the rest of that press.
 *
 * VM-agnostic: callers (T3) wire [onDirection] → `vm.pressButton(...)` and
 * [onTouchEvent] → `vm.onTouchEvent(...)`.
 */
@Composable
fun Touchpad(
    tuning: SwipeTuning,
    onDirection: (RemoteButton) -> Unit,
    onTouchEvent: (TouchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which direction/center is visually "active" (null = idle). Held ~180ms
    // after a discrete tap (remote.jsx:12 setTimeout 180).
    var active by remember { mutableStateOf<RemoteButton?>(null) }
    // bumped on every tap so a rapid re-tap restarts the 180ms window.
    var activeNonce by remember { mutableStateOf(0) }
    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    LaunchedEffect(activeNonce) {
        if (active != null) {
            delay(180L)
            active = null
        }
    }

    // Ring-glow opacity: 0.4 idle / 1.0 active (remote.jsx:53, transition .3s).
    val ringAlpha by animateFloatAsState(
        targetValue = if (active != null) 1f else 0.4f,
        animationSpec = tween(durationMillis = 300),
        label = "ringGlow",
    )
    // Center-OK scale: 0.94 when Select active else 1.0 (remote.jsx:113).
    val okScale by animateFloatAsState(
        targetValue = if (active == RemoteButton.Select) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "okScale",
    )

    // T1 brushes allocate per call — hoist (per the T1 review note).
    val ringGlow = remember { Brushes.touchpadRingGlow() }
    val disk = remember { Brushes.touchpadDisk() }
    val okIdle = remember { Brushes.centerOkIdle() }
    val okActive = remember { Brushes.centerOkActive() }
    val dirGlows = remember {
        mapOf(
            Brushes.GlowEdge.Up to Brushes.directionalGlow(Brushes.GlowEdge.Up),
            Brushes.GlowEdge.Right to Brushes.directionalGlow(Brushes.GlowEdge.Right),
            Brushes.GlowEdge.Down to Brushes.directionalGlow(Brushes.GlowEdge.Down),
            Brushes.GlowEdge.Left to Brushes.directionalGlow(Brushes.GlowEdge.Left),
        )
    }

    // remote.jsx:43-47 — 240×240, centered.
    Box(modifier = modifier.size(240.dp), contentAlignment = Alignment.Center) {

        // --- outer ring glow (inset -10dp ⇒ +10dp each side) — :48-55 ----
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        brush = ringGlow,
                        radius = size.minDimension / 2f,
                        alpha = ringAlpha,
                    )
                },
        )

        // --- base disk + gesture surface — remote.jsx:57-67 ---------------
        Box(
            modifier = Modifier
                .size(240.dp)
                .testTag("trackpad")
                .clip(CircleShape)
                .background(disk, CircleShape)
                .drawBehind {
                    // inset hairline ring — remote.jsx:64
                    // `0 0 0 1px rgba(255,255,255,0.04)`.
                    drawCircle(
                        color = Color.White.copy(alpha = 0.04f),
                        radius = size.minDimension / 2f - 0.5f,
                        style = Stroke(width = 1f),
                    )
                }
                .pointerInput(tuning) {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val cx = w / 2f
                    val cy = h / 2f
                    val engine = SwipeEngine(tuning, w, h)
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            val t0 = System.currentTimeMillis()
                            val downPos = down.position
                            engine.onDown(downPos.x, downPos.y, t0).forEach(onTouchEvent)
                            var totalDx = 0f
                            var totalDy = 0f
                            var isDrag = false
                            var dragging = true
                            while (dragging) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                val now = System.currentTimeMillis()
                                engine.onTick(now).forEach(onTouchEvent)
                                if (ch.pressed) {
                                    val d = ch.positionChange()
                                    totalDx += d.x
                                    totalDy += d.y
                                    if (!isDrag && hypot(totalDx, totalDy) > touchSlopPx) {
                                        isDrag = true
                                    }
                                    engine.onMove(ch.position.x, ch.position.y, now)
                                        .forEach(onTouchEvent)
                                    ch.consume()
                                } else {
                                    val upEvents =
                                        engine.onUp(ch.position.x, ch.position.y, now)
                                    if (isDrag) {
                                        // Drag wins: flush the engine's
                                        // terminal stream + inertia; no zone.
                                        upEvents.forEach(onTouchEvent)
                                        var n = now + 8L
                                        var guard = 0
                                        while (engine.inertiaActive && guard < 240) {
                                            engine.onInertiaFrame(n).forEach(onTouchEvent)
                                            n += 8L; guard++
                                        }
                                    } else {
                                        // Discrete tap wins: the engine's
                                        // Tap/Move(Release) are intentionally
                                        // dropped so the zone press is the
                                        // single source of truth for a tap.
                                        val btn = zoneFor(downPos.x - cx, downPos.y - cy, w)
                                        active = btn
                                        activeNonce++
                                        onDirection(btn)
                                    }
                                    ch.consume()
                                    dragging = false
                                }
                            }
                        }
                    }
                },
        ) {
            // --- direction dots — remote.jsx:69-88 ------------------------
            DirectionDots(active = active)

            // --- directional press glow — remote.jsx:90-107 ---------------
            active?.glowEdge()?.let { edge ->
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(dirGlows.getValue(edge)),
                )
            }

            // --- center OK — remote.jsx:109-123 ---------------------------
            val okPressed = active == RemoteButton.Select
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(okScale)
                    .clip(CircleShape)
                    .background(if (okPressed) okActive else okIdle, CircleShape)
                    .drawBehind {
                        if (okPressed) {
                            // active inset glow — remote.jsx:119
                            // `inset 0 0 0 1px rgba(91,137,255,0.45)`.
                            drawCircle(
                                color = DesignTokens.AccentBlue.copy(alpha = 0.45f),
                                radius = size.minDimension / 2f - 0.5f,
                                style = Stroke(width = 1f),
                            )
                        }
                    },
            )
        }
    }
}

/**
 * The 4 subtle direction indicator dots — remote.jsx:69-88. 6dp dots inset
 * 14dp from the disk edge; active dot `#cfdaff` (else `rgba(255,255,255,0.4)`)
 * with a blue glow when active.
 */
@Composable
private fun DirectionDots(active: RemoteButton?) {
    Box(modifier = Modifier.size(240.dp)) {
        Dot(RemoteButton.Up, active, Alignment.TopCenter, Modifier.padding(top = 14.dp))
        Dot(RemoteButton.Right, active, Alignment.CenterEnd, Modifier.padding(end = 14.dp))
        Dot(RemoteButton.Down, active, Alignment.BottomCenter, Modifier.padding(bottom = 14.dp))
        Dot(RemoteButton.Left, active, Alignment.CenterStart, Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun BoxScope.Dot(
    dir: RemoteButton,
    active: RemoteButton?,
    alignment: Alignment,
    insetModifier: Modifier,
) {
    val on = active == dir
    Box(
        modifier = Modifier
            .align(alignment)
            .then(insetModifier)
            .size(6.dp)
            .clip(CircleShape)
            .drawBehind {
                if (on) {
                    // glow — remote.jsx:83 `0 0 10px rgba(91,137,255,0.6)`.
                    drawCircle(
                        color = DesignTokens.AccentBlue.copy(alpha = 0.6f),
                        radius = size.minDimension * 1.6f,
                    )
                }
                drawCircle(
                    color = if (on) {
                        DesignTokens.AccentActiveText // #cfdaff
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    },
                    radius = size.minDimension / 2f,
                )
            },
    )
}
