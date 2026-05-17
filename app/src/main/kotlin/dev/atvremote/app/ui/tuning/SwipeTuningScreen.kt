package dev.atvremote.app.ui.tuning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.ui.remote.Touchpad

/** Debug A/B tuning harness (spec §5/§8). */
@Composable
fun SwipeTuningScreen() {
    var gain by remember { mutableStateOf(SwipeTuning.DEFAULT.gain) }
    var exponent by remember { mutableStateOf(SwipeTuning.DEFAULT.velocityExponent) }
    var decay by remember { mutableStateOf(SwipeTuning.DEFAULT.inertiaDecay) }
    val log = remember { mutableStateOf(listOf<TouchEvent>()) }
    val tuning = SwipeTuning.DEFAULT.copy(
        gain = gain, velocityExponent = exponent, inertiaDecay = decay,
    )
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Swipe tuning (debug)")
        Text("gain ${"%.2f".format(gain)}")
        Slider(value = gain, onValueChange = { gain = it }, valueRange = 0.5f..6f)
        Text("velocityExponent ${"%.2f".format(exponent)}")
        Slider(value = exponent, onValueChange = { exponent = it }, valueRange = 0.5f..3f)
        Text("inertiaDecay ${"%.2f".format(decay)}")
        Slider(value = decay, onValueChange = { decay = it }, valueRange = 0.5f..0.99f)
        // T3: the old `Trackpad` was deleted with HeroScreen; this debug
        // harness now uses the T2 [Touchpad]. Discrete tap-zone directions and
        // SwipeEngine drag events are both logged (the final Tuning wiring is
        // T5's concern — this is the minimal compile-keeping swap).
        Touchpad(
            tuning = tuning,
            onDirection = { btn ->
                log.value = (log.value + TouchEvent.DirectionalStep(btn)).takeLast(50)
            },
            onTouchEvent = { e -> log.value = (log.value + e).takeLast(50) },
        )
        Text("Emitted events: ${log.value.size}")
        log.value.takeLast(8).forEach { Text(it.toString()) }
    }
}
