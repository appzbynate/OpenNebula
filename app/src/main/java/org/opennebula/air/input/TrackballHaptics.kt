package org.opennebula.air.input

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class TrackballHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.applicationContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var accum = 0f
    private var lastTickMs = 0L

    fun onMove(pixels: Float) {
        accum += pixels
        val now = SystemClock.uptimeMillis()
        if (accum < STEP_PX || now - lastTickMs < MIN_GAP_MS) return
        accum = 0f
        lastTickMs = now
        tick()
    }

    fun click() {
        vibrate(18, 36)
    }

    private fun tick() {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }.onFailure { vibrate(8, 18) }
        } else {
            vibrate(8, 18)
        }
    }

    private fun vibrate(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    companion object {
        private const val STEP_PX = 26f
        private const val MIN_GAP_MS = 18L
    }
}
