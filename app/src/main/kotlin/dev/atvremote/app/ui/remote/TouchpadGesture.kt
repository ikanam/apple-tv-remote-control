package dev.atvremote.app.ui.remote

import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.RemoteButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Pure, Compose-free reducer for a single Touchpad press → release/cancel.
 *
 * It exists so the dual-path **no-double-fire** + **clean-termination**
 * contract (the T2 review's C2/C3/I1) is unit-testable deterministically,
 * independent of whether the host Robolectric `performTouchInput` can faithfully
 * replay multi-touch / cancel. [Touchpad]'s `awaitPointerEventScope` loop is a
 * thin adapter that latches the tracked pointer id, converts pointer changes to
 * these calls, and renders the visual side-effects this reducer returns.
 *
 * ## Contract (the invariants this enforces)
 *
 * A gesture is one of two mutually-exclusive things, decided by touch-slop:
 *
 *  - **Discrete tap** (finger up before the unsigned path length exceeds the
 *    host touch-slop): delivers **exactly one** [Outcome.direction] (the
 *    [zoneFor] zone of the down point) and **zero** [TouchEvent]s to the VM.
 *  - **Drag** (path length exceeds slop): delivers deterministic
 *    [TouchEvent.DirectionalStep] events based on the drag's dominant axis,
 *    with repeat steps as the finger keeps moving. This is intentionally HID
 *    navigation, not touch streaming: tvOS/app touch inertia can turn
 *    a visually-left swipe into a right rebound, while button steps are a
 *    stable focus-navigation contract.
 *
 * Termination is guaranteed exactly once: on a normal up **or** a cancel
 * (pointer consumed by an ancestor / composable left composition mid-gesture)
 * a *cancelled tap* fires nothing. A cancelled **drag** stops immediately; any
 * steps already emitted are real HID presses and are not replayed or undone.
 *
 * All timestamps are the monotonic pointer `uptimeMillis` (I2) supplied by the
 * caller; this reducer never reads wall-clock time.
 *
 * NOT thread-safe; confine to one coroutine.
 */
internal class TouchpadGesture(
    /** Touchpad box width/height in px (for the [zoneFor] center math). */
    private val widthPx: Float,
    private val heightPx: Float,
    /** Compose `LocalViewConfiguration.touchSlop`, in px. */
    private val touchSlopPx: Float,
    /** Fraction of the smaller pad dimension per repeated HID step. */
    private val dragStepFraction: Float = DEFAULT_DRAG_STEP_FRACTION,
    /** iPhone-style trackpad should follow deliberate direction changes mid-drag. */
    private val allowDirectionChanges: Boolean = false,
    private val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    /**
     * What [Touchpad] should do after a reducer call.
     *
     * @param events [TouchEvent]s to forward to the VM, in order.
     * @param direction a discrete tap-zone press to fire (drives the ~180ms
     *   active visual) — non-null at most once per gesture, only on a tap up.
     */
    data class Outcome(
        val events: List<TouchEvent> = emptyList(),
        val direction: RemoteButton? = null,
    ) {
        companion object {
            val NONE = Outcome()
        }
    }

    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var totalDx = 0f
    private var totalDy = 0f
    private var isDrag = false
    private var dragAxis: DragAxis? = null
    private var dragButton: RemoteButton? = null
    private var dragSign = 0f
    private var lastStepProgressPx = 0f
    private var realtimePendingDx = 0f
    private var realtimePendingDy = 0f
    private var realtimeNextStepPx = 0f
    /** Set once a centre long-press has been delivered *during* the hold
     *  ([onHoldTick]); the rest of that touch is then inert (hunt #2 — the
     *  long-press is a discrete event, like a hardware remote, not gated on
     *  finger-up). */
    private var longPressDelivered = false
    private var longPressSuppressed = false

    /** True between [onDown] and a terminal [onUp]/[onCancel]. */
    val inProgress: Boolean get() = active

    /** The caller should keep polling [onHoldTick] only while this holds:
     *  in progress, not yet a drag, long-press not already delivered. */
    val canLongPress: Boolean
        get() = active && !isDrag && !longPressDelivered && !longPressSuppressed

    /**
     * Finger down at the tracked pointer's position/time. Resets per-gesture
     * state. Nothing is forwarded until the reducer knows tap-vs-drag-vs-hold.
     */
    fun onDown(x: Float, y: Float, uptimeMs: Long): Outcome {
        active = true
        downX = x; downY = y
        downAtMs = uptimeMs
        totalDx = 0f; totalDy = 0f
        isDrag = false
        dragAxis = null
        dragButton = null
        dragSign = 0f
        lastStepProgressPx = 0f
        realtimePendingDx = 0f
        realtimePendingDy = 0f
        realtimeNextStepPx = touchSlopPx
        longPressDelivered = false
        longPressSuppressed = false
        return Outcome.NONE
    }

    /**
     * Periodic "is the finger still held?" poll the [Touchpad] loop drives
     * while [canLongPress] (a perfectly still finger emits NO Compose pointer
     * events). On the centre (Select) zone, crossing [longPressMs] delivers
     * `LongPress` **during** the hold (→ `click(Hold)`), once, and marks the
     * rest of the touch inert. A directional-zone long hold is left alone
     * (stays its zone tap on up).
     */
    fun onHoldTick(uptimeMs: Long): Outcome {
        return maybeLongPress(uptimeMs)
    }

    /**
     * A move sample for the **tracked** pointer (caller has already resolved
     * the latched pointer id — C2). [dx]/[dy] are this sample's delta
     * ([androidx.compose.ui.input.pointer.PointerInputChange.positionChange]).
     * Returns deterministic directional steps only once classified as a drag —
     * a not-yet-drag press never streams Moves or steps to the VM (I1).
     */
    @Suppress("UNUSED_PARAMETER")
    fun onMove(x: Float, y: Float, dx: Float, dy: Float, uptimeMs: Long): Outcome {
        if (!active) return Outcome.NONE
        // Once a long-press fired mid-hold the touch is spent: ignore further
        // motion so a post-long-press drift can't open a phantom drag whose
        // Release the longPressDelivered onUp would then swallow (hunt #2).
        if (longPressDelivered) return Outcome.NONE
        totalDx += dx
        totalDy += dy
        if (allowDirectionChanges) {
            realtimePendingDx += dx
            realtimePendingDy += dy
        }
        if (!isDrag && hypot(totalDx, totalDy) > touchSlopPx) {
            isDrag = true
            if (!allowDirectionChanges) {
                startDirectionalDrag()
            }
        }
        return if (isDrag) {
            // Focus navigation must be deterministic. The physical drag emits
            // HID direction presses, so tvOS/app content cannot reinterpret a
            // leftward stream as a right rebound.
            Outcome(
                events = if (allowDirectionChanges) {
                    realtimeDirectionalDragSteps()
                } else {
                    directionalDragSteps()
                },
            )
        } else {
            maybeLongPress(uptimeMs)
        }
    }

    /**
     * The tracked pointer lifted. Classifies the gesture:
     *  - **tap** → fire exactly the [zoneFor] direction.
     *  - **stationary long-hold on the centre (Select) zone** → route it as a
     *    touch event (no zone press) so a long press on OK (→ `click(Hold)`)
     *    differs from a tap (hunt #2).
     *  - **drag** → close engine bookkeeping and emit no extra events; any HID
     *    steps were already emitted during [onMove].
     */
    fun onUp(x: Float, y: Float, uptimeMs: Long): Outcome {
        if (!active) return Outcome.NONE
        active = false
        if (longPressDelivered) {
            // The centre long-press already fired *during* the hold
            // ([onHoldTick]); emit nothing — no second LongPress and no tap.
            return Outcome.NONE
        }
        if (isDrag) {
            return Outcome.NONE
        }
        // Non-drag. The zone press is the single source of truth (I1) except
        // a stationary long-hold on the centre (Select) zone. Directional
        // zones are unaffected — a long hold there stays a tap.
        val btn = zoneFor(downX - widthPx / 2f, downY - heightPx / 2f, widthPx)
        if (btn == RemoteButton.Select && uptimeMs - downAtMs >= longPressMs) {
            return Outcome(events = listOf(TouchEvent.LongPress))
        }
        return Outcome(direction = btn)
    }

    /**
     * The gesture was cancelled (pointer consumed by an ancestor, or the
     * composable left composition mid-gesture):
     *  - cancelled **drag** → close engine bookkeeping, no new direction and
     *    no trailing output.
     *  - cancelled **tap**  → drop everything (no zone press for an aborted
     *    tap).
     *
     * Uses the last known position for the terminal release.
     */
    fun onCancel(uptimeMs: Long): Outcome {
        if (!active) return Outcome.NONE
        active = false
        return Outcome.NONE
    }

    private fun maybeLongPress(uptimeMs: Long): Outcome {
        if (!canLongPress) return Outcome.NONE
        if (uptimeMs - downAtMs < longPressMs) return Outcome.NONE
        val btn = zoneFor(downX - widthPx / 2f, downY - heightPx / 2f, widthPx)
        if (btn != RemoteButton.Select) {
            longPressSuppressed = true
            return Outcome.NONE
        }
        longPressDelivered = true
        return Outcome(events = listOf(TouchEvent.LongPress))
    }

    private fun startDirectionalDrag() {
        val axisTieEpsilonPx = min(widthPx, heightPx) * AXIS_TIE_EPSILON_FRACTION
        val horizontal = abs(totalDx) + axisTieEpsilonPx >= abs(totalDy)
        dragAxis = if (horizontal) DragAxis.Horizontal else DragAxis.Vertical
        dragSign = if ((if (horizontal) totalDx else totalDy) >= 0f) 1f else -1f
        dragButton = when {
            horizontal && dragSign > 0f -> RemoteButton.Right
            horizontal -> RemoteButton.Left
            dragSign > 0f -> RemoteButton.Down
            else -> RemoteButton.Up
        }
        lastStepProgressPx = 0f
    }

    private fun directionalDragSteps(): List<TouchEvent> {
        val axis = dragAxis ?: return emptyList()
        val button = dragButton ?: return emptyList()
        val progress = when (axis) {
            DragAxis.Horizontal -> totalDx * dragSign
            DragAxis.Vertical -> totalDy * dragSign
        }
        if (progress <= 0f) return emptyList()

        val out = ArrayList<TouchEvent>()
        var nextStepAtPx = if (lastStepProgressPx == 0f) {
            touchSlopPx
        } else {
            lastStepProgressPx + dragStepPx()
        }
        while (progress >= nextStepAtPx) {
            out += TouchEvent.DirectionalStep(button)
            lastStepProgressPx = nextStepAtPx
            nextStepAtPx += dragStepPx()
        }
        return out
    }

    private fun realtimeDirectionalDragSteps(): List<TouchEvent> {
        val out = ArrayList<TouchEvent>()
        val axisTieEpsilonPx = min(widthPx, heightPx) * AXIS_TIE_EPSILON_FRACTION
        while (true) {
            val horizontal = abs(realtimePendingDx) + axisTieEpsilonPx >= abs(realtimePendingDy)
            val progress = if (horizontal) abs(realtimePendingDx) else abs(realtimePendingDy)
            if (progress < realtimeNextStepPx) break

            if (horizontal) {
                val sign = if (realtimePendingDx >= 0f) 1f else -1f
                out += TouchEvent.DirectionalStep(
                    if (sign > 0f) RemoteButton.Right else RemoteButton.Left,
                )
                realtimePendingDx -= sign * realtimeNextStepPx
            } else {
                val sign = if (realtimePendingDy >= 0f) 1f else -1f
                out += TouchEvent.DirectionalStep(
                    if (sign > 0f) RemoteButton.Down else RemoteButton.Up,
                )
                realtimePendingDy -= sign * realtimeNextStepPx
            }
            realtimeNextStepPx = dragStepPx()
        }
        return out
    }

    private fun dragStepPx(): Float =
        (min(widthPx, heightPx) * dragStepFraction.coerceAtLeast(MIN_DRAG_STEP_FRACTION))
            .coerceAtLeast(touchSlopPx)

    private enum class DragAxis { Horizontal, Vertical }

    private companion object {
        /** Roughly halves the old app-to-app travel while keeping repeats deliberate. */
        private const val DEFAULT_DRAG_STEP_FRACTION = 0.18f
        private const val MIN_DRAG_STEP_FRACTION = 0.05f
        private const val DEFAULT_LONG_PRESS_MS = 450L
        /** Stabilizes exact 45-degree drags against tiny float/sample jitter. */
        private const val AXIS_TIE_EPSILON_FRACTION = 0.01f
    }
}
