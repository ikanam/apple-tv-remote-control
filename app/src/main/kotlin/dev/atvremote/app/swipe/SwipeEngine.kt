package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pyatv-faithful **absolute-position** trackpad mapper.
 *
 * The Apple TV Companion `_hidT` touch model — and pyatv's `swipe()` /
 * [dev.atvremote.protocol.session.TouchTransport.swipe] — is **absolute**:
 * each sample is the finger's position on the trackpad linearly mapped into a
 * `0..1000` box, phased Press → Hold… → Release. tvOS derives velocity and
 * momentum itself from the `(cx, cy, _ns)` stream, exactly like a physical
 * Siri remote.
 *
 * The previous engine instead accumulated `dxPx * gain(2.4) * accel` into a
 * space recentered to 500 on every touch-down, with client-side inertia. A
 * real swipe's `2.4 × (hundreds of px)` instantly exceeded the 500 half-range
 * so `clamp` pinned every frame at 0/1000 — proven on-device: a controlled
 * +440 px drag sent `x=1000` for 100 % of frames (no proportional tracking →
 * "方向/距离不准、过冲、飘"). This rewrite drops gain / acceleration /
 * recenter / client-inertia and emits the finger's absolute pad position,
 * linearly. (hunt root-cause fix #15.)
 *
 * Kept deliberately (owner-confirmed):
 *  - outer-edge press → [TouchEvent.DirectionalStep] (a real Siri remote's
 *    edge is a directional click, not a swipe surface), gated by
 *    [SwipeTuning.edgeZoneFraction];
 *  - tap / long-press classification, so the Touchpad's discrete-tap path and
 *    the [SwipeEngine] unit contract are otherwise unchanged.
 *
 * `tuning.gain` / `velocityExponent` / `inertiaDecay` / `inertiaMinSpeed` are
 * now unused (momentum is the TV's job — the Tuning debug sliders are inert
 * for positioning). The fields stay on [SwipeTuning] to avoid churn.
 *
 * Output is throttled to `tuning.maxEventsPerSecond`. No Android/Compose
 * imports. **NOT thread-safe** — confine to one coroutine (mirrors the
 * caller); see [dev.atvremote.app.ui.remote.TouchpadGesture].
 */
class SwipeEngine(
    private val tuning: SwipeTuning,
    private val widthPx: Float,
    private val heightPx: Float,
) {
    private companion object {
        private const val MAX = 1000f
    }

    /** Finger px → absolute `0..1000` pad coordinate (pyatv model), clamped. */
    private fun mapX(px: Float): Int =
        (px / widthPx * MAX).coerceIn(0f, MAX).roundToInt()

    private fun mapY(px: Float): Int =
        (px / heightPx * MAX).coerceIn(0f, MAX).roundToInt()

    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var lastEmitMs = Long.MIN_VALUE
    private var maxTravelPx = 0f
    private var longPressed = false
    private var edgeConsumed = false

    /** No client-side inertia — tvOS owns momentum (pyatv parity). Retained as
     *  a stable `false` so [dev.atvremote.app.ui.remote.TouchpadGesture]'s
     *  inertia loop compiles and simply never iterates. */
    val inertiaActive: Boolean = false

    private val minSpacingMs: Long
        get() = (1000L / tuning.maxEventsPerSecond).coerceAtLeast(1L)

    /** First move after a press is never throttled (lastEmitMs == MIN). */
    private fun throttled(nowMs: Long): Boolean {
        if (lastEmitMs == Long.MIN_VALUE) return false
        return nowMs - lastEmitMs < minSpacingMs
    }

    private fun edgeButton(px: Float, py: Float): RemoteButton? {
        val ex = tuning.edgeZoneFraction * (widthPx / 2f)
        val ey = tuning.edgeZoneFraction * (heightPx / 2f)
        val left = px <= ex
        val right = px >= widthPx - ex
        val top = py <= ey
        val bottom = py >= heightPx - ey
        // Resolve corners by the larger normalized overshoot.
        val cands = buildList {
            if (left) add(RemoteButton.Left to (ex - px))
            if (right) add(RemoteButton.Right to (px - (widthPx - ex)))
            if (top) add(RemoteButton.Up to (ey - py))
            if (bottom) add(RemoteButton.Down to (py - (heightPx - ey)))
        }
        return cands.maxByOrNull { it.second }?.first
    }

    fun onDown(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        down = true
        downX = x; downY = y
        downAtMs = nowMs
        lastEmitMs = Long.MIN_VALUE
        maxTravelPx = 0f
        longPressed = false
        edgeConsumed = false
        val edge = edgeButton(x, y)
        if (edge != null) {
            edgeConsumed = true
            return listOf(TouchEvent.DirectionalStep(edge))
        }
        return listOf(TouchEvent.Move(mapX(x), mapY(y), TouchPhase.Press))
    }

    fun onMove(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        if (!down || edgeConsumed) return emptyList()
        val travel = hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
        if (travel > maxTravelPx) maxTravelPx = travel
        // Travel is tracked even on a throttled (dropped) frame so the
        // tap/long-press classification stays accurate.
        if (throttled(nowMs)) return emptyList()
        lastEmitMs = nowMs
        return listOf(TouchEvent.Move(mapX(x), mapY(y), TouchPhase.Hold))
    }

    /** Called periodically while the finger is down so a stationary
     *  long-press can fire before the up. */
    fun onTick(nowMs: Long): List<TouchEvent> {
        if (!down || longPressed || edgeConsumed) return emptyList()
        if (maxTravelPx <= tuning.tapSlopUnits &&
            nowMs - downAtMs >= tuning.longPressMs
        ) {
            longPressed = true
            return listOf(TouchEvent.LongPress)
        }
        return emptyList()
    }

    fun onUp(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        if (!down) return emptyList()
        down = false
        if (edgeConsumed) { edgeConsumed = false; return emptyList() }
        // Tap is appended BEFORE the terminal Release so the Release frame is
        // always the last event of the gesture (callers replay the stream and
        // the Release must close it).
        val out = ArrayList<TouchEvent>()
        val isTap = maxTravelPx <= tuning.tapSlopUnits &&
            nowMs - downAtMs < tuning.longPressMs
        if (isTap && !longPressed) out += TouchEvent.Tap
        out += TouchEvent.Move(mapX(x), mapY(y), TouchPhase.Release)
        return out
    }

    /** Retained for source compatibility — always empty (no client inertia;
     *  tvOS computes its own momentum from the absolute sample stream). */
    @Suppress("UNUSED_PARAMETER")
    fun onInertiaFrame(nowMs: Long): List<TouchEvent> = emptyList()
}
