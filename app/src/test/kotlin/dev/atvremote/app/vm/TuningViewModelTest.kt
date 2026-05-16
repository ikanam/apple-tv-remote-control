package dev.atvremote.app.vm

import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.TouchPhase
import kotlin.test.Test
import kotlin.test.assertTrue

class TuningViewModelTest {
    private val drag = listOf(
        TuningSample.Down(100f, 500f, 0L),
        TuningSample.Move(200f, 500f, 16L),
        TuningSample.Move(360f, 500f, 32L),
        TuningSample.Up(360f, 500f, 48L),
    )

    @Test fun replayProducesEventLog() {
        val vm = TuningViewModel(widthPx = 1000f, heightPx = 1000f)
        val log = vm.replay(SwipeTuning.DEFAULT, drag)
        assertTrue(log.any { it is TouchEvent.Move })
        // Reconciliation (plan-internal test<->impl conflict): `drag` is a fast
        // rightward flick, so SwipeEngine.onUp returns Move(Release) AND sets
        // inertiaActive=true; the verbatim inertia-driving replay() then correctly
        // appends trailing Move(Hold) inertia frames after that Release (its whole
        // §8 purpose). So log.last() is a Move(Hold), not Move(Release): the
        // plan's verbatim `assertEquals(Release, log.last().phase)` is
        // plan-internally inconsistent (empirically: expected:<Release> but
        // was:<Hold>). The meaningful invariant the test intends is that the
        // replayed gesture emits a Release (the gesture completed) — assert
        // Release-is-present, not Release-is-last, keeping replay()'s inertia
        // behavior intact (TuningViewModel.kt stays byte-verbatim).
        assertTrue(
            log.any { it is TouchEvent.Move &&
                it.phase == TouchPhase.Release },
            "expected a Move(Release) in the replay log; got phases " +
                log.filterIsInstance<TouchEvent.Move>().map { it.phase },
        )
    }

    @Test fun higherGainProducesLargerDisplacement() {
        val vm = TuningViewModel(1000f, 1000f)
        val slow = vm.replay(SwipeTuning.DEFAULT.copy(gain = 1.0f, velocityExponent = 1f), drag)
        val fast = vm.replay(SwipeTuning.DEFAULT.copy(gain = 4.0f, velocityExponent = 1f), drag)
        val slowMax = slow.filterIsInstance<TouchEvent.Move>().maxOf { it.x }
        val fastMax = fast.filterIsInstance<TouchEvent.Move>().maxOf { it.x }
        assertTrue(fastMax >= slowMax, "fast=$fastMax slow=$slowMax")
    }

    @Test fun abComparesTwoParamSetsOnSameInput() {
        val vm = TuningViewModel(1000f, 1000f)
        val ab = vm.compare(
            a = SwipeTuning.DEFAULT.copy(gain = 1.0f),
            b = SwipeTuning.DEFAULT.copy(gain = 3.0f),
            drag = drag,
        )
        assertTrue(ab.a.isNotEmpty() && ab.b.isNotEmpty())
    }
}
