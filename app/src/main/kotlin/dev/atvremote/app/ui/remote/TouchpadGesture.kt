package dev.atvremote.app.ui.remote

import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.math.hypot

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
 *    [zoneFor] zone of the down point) and **zero** [TouchEvent]s to the VM —
 *    the [SwipeEngine]'s own `Tap`/`LongPress`/`DirectionalStep` are buffered
 *    while unclassified and **discarded** on a tap classification (I1: a slow
 *    in-zone press can no longer emit a `LongPress` *and* a zone press).
 *  - **Drag** (path length exceeds slop): delivers the [SwipeEngine] stream
 *    (buffered `Move(Press)` + drag/inertia `Move(Hold)` frames, **closed by
 *    a terminal `Move(Release)` as the very last event**) via [Outcome.events]
 *    and **zero** direction.
 *
 * Termination is guaranteed exactly once: on a normal up **or** a cancel
 * (pointer consumed by an ancestor / composable left composition mid-gesture)
 * the [SwipeEngine] receives `onUp` exactly once and a terminal `Move(Release)`
 * reaches the VM iff the gesture had been classified as a drag (a *cancelled
 * tap* fires nothing — there is no zone press for an aborted tap, and no
 * virtual finger was ever shown to the VM because tap engine events were
 * buffered). For a cancelled **drag** the buffered stream is flushed and a
 * terminal `Move(Release)` is emitted so the VM/device never sees a virtual
 * finger that never lifts. Inertia is dropped on cancel.
 *
 * All timestamps are the monotonic pointer `uptimeMillis` (I2) supplied by the
 * caller; this reducer never reads wall-clock time.
 *
 * NOT thread-safe (mirrors [SwipeEngine]); confine to one coroutine.
 */
internal class TouchpadGesture(
    private val engine: SwipeEngine,
    /** Touchpad box width/height in px (for the [zoneFor] center math). */
    private val widthPx: Float,
    private val heightPx: Float,
    /** Compose `LocalViewConfiguration.touchSlop`, in px. */
    private val touchSlopPx: Float,
) {
    /**
     * What [Touchpad] should do after a reducer call.
     *
     * @param events SwipeEngine [TouchEvent]s to forward to the VM, in order.
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
    private var totalDx = 0f
    private var totalDy = 0f
    private var isDrag = false
    /** Engine outputs withheld until we know tap-vs-drag (I1). */
    private val buffered = ArrayList<TouchEvent>()

    /** True between [onDown] and a terminal [onUp]/[onCancel]. */
    val inProgress: Boolean get() = active

    /**
     * Finger down at the tracked pointer's position/time. Resets per-gesture
     * state and primes the engine; the engine's Press (or edge
     * `DirectionalStep`) is buffered, not forwarded (I1).
     */
    fun onDown(x: Float, y: Float, uptimeMs: Long): Outcome {
        active = true
        downX = x; downY = y
        totalDx = 0f; totalDy = 0f
        isDrag = false
        buffered.clear()
        buffered += engine.onDown(x, y, uptimeMs)
        return Outcome.NONE
    }

    /**
     * A move sample for the **tracked** pointer (caller has already resolved
     * the latched pointer id — C2). [dx]/[dy] are this sample's delta
     * ([androidx.compose.ui.input.pointer.PointerInputChange.positionChange]).
     * Returns the engine Move stream only once classified as a drag — a
     * not-yet-drag press never streams Moves to the VM (I1).
     */
    fun onMove(x: Float, y: Float, dx: Float, dy: Float, uptimeMs: Long): Outcome {
        if (!active) return Outcome.NONE
        totalDx += dx
        totalDy += dy
        val crossed = !isDrag && hypot(totalDx, totalDy) > touchSlopPx
        if (crossed) isDrag = true
        val tick = engine.onTick(uptimeMs)
        val moved = engine.onMove(x, y, uptimeMs)
        return if (isDrag) {
            // Drag: flush anything buffered before the slop crossing, then
            // stream live frames.
            val out = ArrayList<TouchEvent>(buffered.size + tick.size + moved.size)
            if (buffered.isNotEmpty()) { out += buffered; buffered.clear() }
            out += tick
            out += moved
            Outcome(events = out)
        } else {
            // Still unclassified: withhold (a tap must deliver zero events).
            buffered += tick
            buffered += moved
            Outcome.NONE
        }
    }

    /**
     * The tracked pointer lifted. Classifies the gesture:
     *  - **tap** → discard buffered engine events, drive `engine.onUp` (so the
     *    engine's own bookkeeping closes — its Tap/Release are dropped), fire
     *    exactly the [zoneFor] direction.
     *  - **drag** → flush buffered, then the inertia glide `Move(Hold)`
     *    frames, then the terminal `Move(Release)` LAST (the stream always
     *    ends finger-up); no direction.
     */
    fun onUp(x: Float, y: Float, uptimeMs: Long): Outcome {
        if (!active) return Outcome.NONE
        active = false
        val tick = engine.onTick(uptimeMs)
        val up = engine.onUp(x, y, uptimeMs)
        if (isDrag) {
            val out = ArrayList<TouchEvent>()
            if (buffered.isNotEmpty()) { out += buffered }
            out += tick
            // Hold the engine's terminal Move(Release) back: the inertia
            // glide must be emitted FIRST and the Release LAST so the stream
            // always ends finger-up. The previous order (Release, then
            // inertia Move(Hold) frames — onInertiaFrame never closes with a
            // Release) left the device's virtual finger stuck "down" after
            // any flick (slow drags don't arm inertia → intermittent).
            val release = up.lastOrNull {
                it is TouchEvent.Move && it.phase == TouchPhase.Release
            } as TouchEvent.Move?
            out += up.filterNot { it === release }
            var n = uptimeMs + INERTIA_FRAME_MS
            var guard = 0
            var lastGlide: TouchEvent.Move? = null
            while (engine.inertiaActive && guard < INERTIA_MAX_FRAMES) {
                val frame = engine.onInertiaFrame(n)
                out += frame
                (frame.lastOrNull() as? TouchEvent.Move)?.let { lastGlide = it }
                n += INERTIA_FRAME_MS
                guard++
            }
            // Terminal Release LAST — at the glide-final position (no
            // snap-back); fall back to the lift-position Release when no
            // inertia ran (a plain slow drag).
            val terminal = lastGlide
                ?.let { TouchEvent.Move(it.x, it.y, TouchPhase.Release) }
                ?: release
            if (terminal != null) out += terminal
            buffered.clear()
            return Outcome(events = out)
        }
        // Discrete tap: the engine's discrete outputs are intentionally
        // dropped so the zone press is the single source of truth (I1).
        buffered.clear()
        val btn = zoneFor(downX - widthPx / 2f, downY - heightPx / 2f, widthPx)
        return Outcome(direction = btn)
    }

    /**
     * The gesture was cancelled (pointer consumed by an ancestor, or the
     * composable left composition mid-gesture). Guarantees the engine is
     * closed with `onUp` exactly once so a virtual finger never hangs (C3):
     *  - cancelled **drag** → flush buffered + a terminal `Move(Release)`
     *    (NO inertia — a cancel must not glide), no direction.
     *  - cancelled **tap**  → drop everything (no zone press for an aborted
     *    tap; nothing was ever shown to the VM since taps are buffered), but
     *    still call `engine.onUp` to close engine state.
     *
     * Uses the last known position for the synthesized release.
     */
    fun onCancel(uptimeMs: Long): Outcome {
        if (!active) return Outcome.NONE
        active = false
        val up = engine.onUp(downX + totalDx, downY + totalDy, uptimeMs)
        if (isDrag) {
            val out = ArrayList<TouchEvent>()
            if (buffered.isNotEmpty()) { out += buffered }
            out += up
            buffered.clear()
            return Outcome(events = out)
        }
        buffered.clear()
        return Outcome.NONE
    }

    private companion object {
        const val INERTIA_FRAME_MS = 8L
        const val INERTIA_MAX_FRAMES = 240
    }
}
