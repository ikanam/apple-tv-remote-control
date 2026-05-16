package dev.atvremote.app.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Light haptics matching Apple feel (spec §5): tap / edge-step / select. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun oneShot(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }

    /** Crisp light tick on trackpad tap. */
    fun tap() = oneShot(10L, 80)

    /** Slightly firmer tick on directional edge step. */
    fun edgeStep() = oneShot(14L, 130)

    /** Confirmation pop on select/long-press. */
    fun select() = oneShot(20L, 200)
}
