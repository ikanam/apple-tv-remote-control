package dev.atvremote.app.swipe

/**
 * Tunable velocity/inertia curve for the full-fidelity trackpad (spec §2/§5/§8).
 * All fields are A/B-tweakable from the SwipeTuningScreen.
 */
data class SwipeTuning(
    /** Linear displacement gain: screen px delta -> 0..1000 units. */
    val gain: Float = 2.4f,
    /** Velocity exponent: applied to normalized speed for the acceleration curve. */
    val velocityExponent: Float = 1.35f,
    /** Per-frame inertia retention after finger-up (0=no glide, 1=never stops). */
    val inertiaDecay: Float = 0.92f,
    /** Inertia stops once speed falls below this (units/frame). */
    val inertiaMinSpeed: Float = 0.6f,
    /** Fraction of the trackpad radius treated as a directional edge zone. */
    val edgeZoneFraction: Float = 0.18f,
    /** Max emitted touch events per second (spec §6: ≤120 Hz). */
    val maxEventsPerSecond: Int = 120,
    /** Movement (in 0..1000 units) below which a finger-up counts as a tap. */
    val tapSlopUnits: Int = 18,
    /** Press duration (ms) at/above which a stationary press is a long-press. */
    val longPressMs: Long = 450L,
) {
    companion object { val DEFAULT = SwipeTuning() }
}
