package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent

/** One recorded raw input event for the tuning harness. */
sealed interface TuningSample {
    data class Down(val x: Float, val y: Float, val t: Long) : TuningSample
    data class Move(val x: Float, val y: Float, val t: Long) : TuningSample
    data class Up(val x: Float, val y: Float, val t: Long) : TuningSample
}

data class AbResult(val a: List<TouchEvent>, val b: List<TouchEvent>)

/**
 * Swipe-tuning harness (spec §5/§8): replays a recorded gesture through SwipeEngine
 * with given SwipeTuning params and returns the emitted TouchEvent log so the
 * developer can A/B compare velocity/inertia curves.
 */
class TuningViewModel(
    private val widthPx: Float,
    private val heightPx: Float,
) : ViewModel() {

    fun replay(tuning: SwipeTuning, drag: List<TuningSample>): List<TouchEvent> {
        val engine = SwipeEngine(tuning, widthPx, heightPx)
        val out = ArrayList<TouchEvent>()
        for (s in drag) {
            when (s) {
                is TuningSample.Down -> out += engine.onDown(s.x, s.y, s.t)
                is TuningSample.Move -> out += engine.onMove(s.x, s.y, s.t)
                is TuningSample.Up -> {
                    out += engine.onUp(s.x, s.y, s.t)
                    var now = s.t + 8L
                    var guard = 0
                    while (engine.inertiaActive && guard < 300) {
                        out += engine.onInertiaFrame(now); now += 8L; guard++
                    }
                }
            }
        }
        return out
    }

    fun compare(a: SwipeTuning, b: SwipeTuning, drag: List<TuningSample>): AbResult =
        AbResult(a = replay(a, drag), b = replay(b, drag))
}
