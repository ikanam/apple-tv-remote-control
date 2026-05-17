package dev.atvremote.app.swipe

/**
 * Tunable main-remote trackpad parameters.
 */
data class SwipeTuning(
    /**
     * Main remote focus navigation: fraction of the touchpad's smaller
     * dimension needed for each repeated HID directional step after drag starts.
     * Smaller values feel more sensitive; larger values require a longer drag.
     */
    val dragStepFraction: Float = 0.18f,
) {
    companion object { val DEFAULT = SwipeTuning() }
}
