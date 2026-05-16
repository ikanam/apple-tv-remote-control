// app/src/main/kotlin/dev/atvremote/app/ui/hero/KeyboardCapability.kt
package dev.atvremote.app.ui.hero

/**
 * True iff the :protocol keyboard surface is implemented (deferred Plan-2
 * T15/T16/T19). While `textGet()` is a NotImplementedError stub the Keyboard
 * key renders disabled — a build-order state, not a permanent dead button
 * (spec 2026-05-16). Probe is a side-effect-free `textGet()` (read-only).
 */
suspend fun keyboardAvailable(textGetProbe: suspend () -> String): Boolean =
    try { textGetProbe(); true }
    catch (e: NotImplementedError) { false }
    catch (e: Throwable) { false }
