package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure full-fidelity trackpad gesture engine (spec §2/§5).
 * - single-finger drag -> relative focus move w/ velocity & inertia -> TouchEvent.Move stream
 * - tap -> TouchEvent.Tap ; long-press -> TouchEvent.LongPress
 * - press in an edge zone -> TouchEvent.DirectionalStep
 * Output is throttled to tuning.maxEventsPerSecond. No Android/Compose imports.
 *
 * **Thread-safety**: NOT thread-safe. All calls (`onDown`/`onMove`/`onTick`/`onUp`/
 * `onInertiaFrame`) must be confined to a single coroutine/dispatcher. `onDown` cancels
 * in-flight inertia so a re-gesture race is handled, but concurrent calls from different
 * threads would tear state.
 *
 * ## Non-obvious math reconciliations (the plan's draft did not pass its own tests)
 *
 * ### 1. First move after a press is never throttled
 * `onDown` does NOT seed `lastEmitMs` from the Press frame — it stays
 * `Long.MIN_VALUE`, so `throttled()` returns false for the *first* `onMove`
 * regardless of how soon it arrives (the press and the first move are distinct
 * gesture milestones; the press is an instantaneous state transition, not a
 * sampled point that should consume the rate budget). After that first move
 * sets `lastEmitMs`, the normal `minSpacingMs` throttle applies. This makes
 * `moveThrottledToMaxEventsPerSecond` exact: a@4 emits (lastEmitMs MIN -> set
 * 4), b@7 dropped (7-4=3 < 8), c@13 emits (13-4=9 >= 8).
 *
 * ### 2. Velocity-acceleration curve (slow drag ~= linear gain)
 * The draft computed `speed = travel / (now - downAtMs)` and
 * `accel = (1 + speed) ^ velocityExponent`, which inflates even a slow drag
 * (a 20px move over 16ms gave accel ~= 3, x ~= 644 vs the spec'd ~548).
 *
 * Fixed to a *per-move* speed (px / ms since the previous sampled point, not
 * since press) with a slow-drag dead-band: only the portion of speed *above*
 * `SLOW_SPEED_THRESHOLD_PX_PER_MS` is accelerated:
 *
 *     excess = max(0, speed - SLOW_SPEED_THRESHOLD_PX_PER_MS)
 *     accel  = (1 + excess) ^ tuning.velocityExponent      // >= 1, == 1 for slow drags
 *     dUnits = dPx * tuning.gain * accel
 *
 * For `dragEmitsHoldPhaseScaledByGain` speed = 20px/16ms = 1.25 px/ms which is
 * below the 1.5 threshold => excess 0 => accel 1 => +20 * 2.4 ~= +48 => x 548
 * (in 540..560), y unchanged at 500. A genuine flick (inertia test: 200px/8ms
 * = 25 px/ms) is well above the threshold => large accel => large retained
 * `velX` => inertia runs many frames then decays below `inertiaMinSpeed`.
 * The `tuning.gain` / `velocityExponent` / `inertiaDecay` / `inertiaMinSpeed`
 * semantics from `SwipeTuning` are preserved (only the speed normalization and
 * the dead-band are added; `SwipeTuning` is untouched).
 */
class SwipeEngine(
    private val tuning: SwipeTuning,
    private val widthPx: Float,
    private val heightPx: Float,
) {
    private companion object {
        /**
         * Per-move speed (px/ms) at/below which a drag is treated as
         * non-accelerated (accel == 1, pure linear `gain`). A normal deliberate
         * focus drag is well under this; only fast flicks exceed it and feed
         * the `velocityExponent` curve + inertia.
         */
        const val SLOW_SPEED_THRESHOLD_PX_PER_MS = 1.5f

        /** Virtual trackpad coordinate center (origin of relative movement). */
        private const val VIRT_CENTER = 500f
        /** Virtual trackpad coordinate minimum bound. */
        private const val VIRT_MIN = 0f
        /** Virtual trackpad coordinate maximum bound. */
        private const val VIRT_MAX = 1000f
    }

    private fun clampVirt(v: Float) = v.coerceIn(VIRT_MIN, VIRT_MAX)

    private var down = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveMs = 0L         // timestamp of the previous sampled point (down or move)
    private var virtX = VIRT_CENTER     // current virtual position in VIRT_MIN..VIRT_MAX
    private var virtY = VIRT_CENTER
    private var downAtMs = 0L
    private var lastEmitMs = Long.MIN_VALUE
    private var maxTravelUnits = 0f
    private var longPressed = false
    private var edgeConsumed = false

    private var velX = 0f              // units/frame, for inertia
    private var velY = 0f
    var inertiaActive = false
        private set

    private val minSpacingMs: Long get() = (1000L / tuning.maxEventsPerSecond).coerceAtLeast(1L)

    private fun clamp(v: Float) = v.coerceIn(VIRT_MIN, VIRT_MAX).roundToInt()

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

    private fun throttled(nowMs: Long): Boolean {
        if (lastEmitMs == Long.MIN_VALUE) return false
        return nowMs - lastEmitMs < minSpacingMs
    }

    fun onDown(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        down = true
        lastX = x; lastY = y
        downAtMs = nowMs
        lastMoveMs = nowMs
        // Intentionally NOT seeding lastEmitMs from the Press: see KDoc note 1 —
        // the first onMove after a press must never be throttled.
        lastEmitMs = Long.MIN_VALUE
        maxTravelUnits = 0f
        longPressed = false
        edgeConsumed = false
        velX = 0f; velY = 0f
        inertiaActive = false
        virtX = VIRT_CENTER; virtY = VIRT_CENTER
        val edge = edgeButton(x, y)
        if (edge != null) {
            edgeConsumed = true
            return listOf(TouchEvent.DirectionalStep(edge))
        }
        return listOf(TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Press))
    }

    fun onMove(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        if (!down || edgeConsumed) return emptyList()
        val dxPx = x - lastX
        val dyPx = y - lastY
        lastX = x; lastY = y
        // Per-move speed (px/ms since the previous sampled point), with a
        // slow-drag dead-band so a deliberate drag is ~ linear gain (note 2).
        // assumes monotonic nowMs (Compose pointer timestamps are); a backwards/out-of-order
        // dt collapses to the 1ms floor = a single-frame speed spike — acceptable, not corrected here.
        val dt = (nowMs - lastMoveMs).coerceAtLeast(1L).toFloat()
        lastMoveMs = nowMs
        val speed = hypot(dxPx.toDouble(), dyPx.toDouble()).toFloat() / dt
        val excess = max(0f, speed - SLOW_SPEED_THRESHOLD_PX_PER_MS)
        val accel = (1f + excess).pow(tuning.velocityExponent)
        val dux = dxPx * tuning.gain * accel
        val duy = dyPx * tuning.gain * accel
        virtX = clampVirt(virtX + dux)
        virtY = clampVirt(virtY + duy)
        velX = dux; velY = duy
        val travel = abs(virtX - VIRT_CENTER) + abs(virtY - VIRT_CENTER)
        if (travel > maxTravelUnits) maxTravelUnits = travel
        if (throttled(nowMs)) return emptyList()
        lastEmitMs = nowMs
        return listOf(TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Hold))
    }

    /** Called periodically while finger is down so a stationary long-press can fire. */
    fun onTick(nowMs: Long): List<TouchEvent> {
        if (!down || longPressed || edgeConsumed) return emptyList()
        if (maxTravelUnits <= tuning.tapSlopUnits &&
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
        // Tap is appended BEFORE the terminal Move(Release) so that the
        // Release frame is always the *last* event of the gesture (the spec
        // contract: callers replay the stream and the Release must close it,
        // whether or not a Tap classification preceded it).
        val out = ArrayList<TouchEvent>()
        val isTap = maxTravelUnits <= tuning.tapSlopUnits &&
            nowMs - downAtMs < tuning.longPressMs
        if (isTap && !longPressed) out += TouchEvent.Tap
        out += TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Release)
        // Begin inertia only on a real flick (had recent velocity, moved past slop).
        if (!longPressed && maxTravelUnits > tuning.tapSlopUnits &&
            (abs(velX) + abs(velY)) > tuning.inertiaMinSpeed
        ) {
            inertiaActive = true
        }
        return out
    }

    /** Drive after onUp to glide; returns Move(Hold) frames until inertia stops. */
    fun onInertiaFrame(nowMs: Long): List<TouchEvent> {
        if (!inertiaActive) return emptyList()
        velX *= tuning.inertiaDecay
        velY *= tuning.inertiaDecay
        virtX = clampVirt(virtX + velX)
        virtY = clampVirt(virtY + velY)
        if ((abs(velX) + abs(velY)) < tuning.inertiaMinSpeed) {
            inertiaActive = false
            return emptyList()
        }
        if (throttled(nowMs)) return emptyList()
        lastEmitMs = nowMs
        return listOf(TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Hold))
    }
}
