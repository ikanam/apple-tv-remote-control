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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
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
import kotlinx.coroutines.CancellationException
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
 *  - `r = hypot(dx,dy)`; `innerR = width * 0.33` (matches the enlarged center
 *    OK circle so the whole visible button taps as Select)
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
    val innerR = width * 0.33f
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
 * press accounting stays consistent, but its outputs are **withheld** until
 * the gesture is classified: on finger-up the classification decides which
 * path "wins". The precise invariant (enforced by [TouchpadGesture]):
 *
 *  - a **tap** delivers **exactly one** [onDirection] (the [zoneFor] zone) and
 *    **zero** [onTouchEvent] — the engine's own `Tap`/`LongPress`/
 *    `DirectionalStep` are buffered while unclassified and discarded on a tap,
 *    so a slow in-zone press can no longer fire a `LongPress` *and* a zone
 *    press (no undocumented double input);
 *  - a **drag** delivers the SwipeEngine stream (`Move(Press)` + `Move(Hold)`
 *    + terminal `Move(Release)` + inertia) via [onTouchEvent] and **zero**
 *    [onDirection]. `Move` frames are not streamed to the VM for a press that
 *    later turns out to be a tap (they are buffered until the slop crossing).
 *
 * Termination is guaranteed exactly once. On a normal up OR a cancellation
 * (an ancestor consumes the pointer, or the composable leaves composition
 * mid-gesture) the engine receives `onUp` exactly once and — for a drag — a
 * terminal `Move(Release)` reaches [onTouchEvent], so the VM/device never sees
 * a virtual finger that never lifts; a cancelled drag drops inertia, a
 * cancelled tap fires nothing.
 *
 * Multi-touch safe: the gesture tracks the pointer id latched from
 * `awaitFirstDown()`; a second finger cannot hijack `positionChange()` nor keep
 * the gesture from terminating.
 *
 * Slop source: Compose's [LocalViewConfiguration.touchSlop] (the platform
 * `ViewConfiguration` slop, ~the same threshold Compose's own `detectDrag*`
 * uses) — accumulated as the unsigned path length since touch-down; once it
 * exceeds slop the gesture is irrevocably a drag for the rest of that press.
 * Timestamps are the monotonic pointer `uptimeMillis` (SwipeEngine assumes
 * monotonic time; wall-clock can jump).
 *
 * VM-agnostic: callers (T3) wire [onDirection] → `vm.pressButton(...)` and
 * [onTouchEvent] → `vm.onTouchEvent(...)`. Both are read through
 * [rememberUpdatedState] so the long-lived gesture loop always invokes the
 * latest lambdas (T3 re-creates them each recomposition).
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

    // C1: the gesture loop below is started once (keyed on `tuning`) and lives
    // across recompositions; T3 re-creates these lambdas every recomposition.
    // Reading them through rememberUpdatedState makes the loop always invoke
    // the LATEST instances instead of the ones captured at first composition.
    val onDirectionState = rememberUpdatedState(onDirection)
    val onTouchEventState = rememberUpdatedState(onTouchEvent)

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
                    // pointerInput is keyed on `tuning` only (a legitimate
                    // engine rebuild); onDirection/onTouchEvent are read live
                    // via rememberUpdatedState (C1) so this long-lived loop
                    // never invokes stale lambdas.
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // edge zones intentionally KEPT (tuning.edgeZoneFraction):
                    // a real Siri remote's outer edge is NOT a swipe surface —
                    // a press there is a directional press, not a drag.
                    val engine = SwipeEngine(tuning, w, h)
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            // C2: latch the tracked pointer id so a second
                            // finger can never hijack positionChange()/pressed.
                            val pid = down.id
                            val gesture = TouchpadGesture(engine, w, h, touchSlopPx)

                            fun apply(outcome: TouchpadGesture.Outcome) {
                                outcome.events.forEach { onTouchEventState.value(it) }
                                outcome.direction?.let { btn ->
                                    active = btn
                                    activeNonce++
                                    onDirectionState.value(btn)
                                }
                            }

                            // C3: if the awaitPointerEventScope coroutine is
                            // cancelled (composable leaves composition / an
                            // ancestor cancels the gesture) while a touch is
                            // in progress, synthesize a clean terminal so the
                            // engine still gets onUp and a drag's Release
                            // reaches the VM — then rethrow.
                            var lastUptime = down.uptimeMillis
                            try {
                                apply(gesture.onDown(down.position.x, down.position.y, down.uptimeMillis))
                                var dragging = true
                                while (dragging) {
                                    // Main pass so an ancestor that consumes
                                    // the change in an earlier pass is seen by
                                    // us as a cancellation, not a phantom move.
                                    val ev = awaitPointerEvent(PointerEventPass.Main)
                                    val ch = ev.changes.firstOrNull { it.id == pid }
                                        ?: continue // C2/I3: our pointer absent
                                    lastUptime = ch.uptimeMillis
                                    val up = !ch.pressed || ch.changedToUp()
                                    // We only consume our own change AFTER this
                                    // check (move/up branches below), so an
                                    // already-consumed change here means an
                                    // ancestor claimed it — treat as a cancel
                                    // (C3): clean terminal Release, no inertia.
                                    if (ch.isConsumed && !up) {
                                        apply(gesture.onCancel(ch.uptimeMillis))
                                        dragging = false
                                    } else if (up) {
                                        apply(gesture.onUp(ch.position.x, ch.position.y, ch.uptimeMillis))
                                        ch.consume()
                                        dragging = false
                                    } else {
                                        val d = ch.positionChange()
                                        apply(
                                            gesture.onMove(
                                                ch.position.x, ch.position.y,
                                                d.x, d.y, ch.uptimeMillis,
                                            ),
                                        )
                                        ch.consume()
                                    }
                                }
                            } catch (c: CancellationException) {
                                // The invariant: after ANY gesture end (normal
                                // up OR cancel) the engine has had onUp exactly
                                // once and a drag's Release reached the VM.
                                if (gesture.inProgress) {
                                    apply(gesture.onCancel(lastUptime))
                                }
                                throw c
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
            // .align(Center): this disk Box has no contentAlignment, so it
            // defaults to TopStart — without this the 96dp OK lands in the
            // disk's top-left corner, not dead center. DirectionDots and the
            // press-glow are parent-sized so they were unaffected, which hid
            // this until the Touchpad was first seen on the tuning screen.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    // Enlarged confirm/OK button (96dp → 128 → 160dp). The
                    // outer pad (240dp disk / 260dp ring / dot insets) is
                    // unchanged; only this center grows. zoneFor's innerR
                    // (width*0.33) matches this so the whole visible OK taps
                    // as Select.
                    .size(160.dp)
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
