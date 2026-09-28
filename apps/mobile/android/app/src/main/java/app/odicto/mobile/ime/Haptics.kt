package app.odicto.mobile.ime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import app.odicto.mobile.BuildConfig

internal object Haptics {
    const val OFF = 0
    const val LIGHT = 1
    const val MEDIUM = 2
    const val STRONG = 3
    const val DEFAULT = MEDIUM
    const val EMPHASIS_NONE = 0
    const val EMPHASIS_LONG = 1

    fun clamp(level: Int): Int = level.coerceIn(OFF, STRONG)

    fun strengthFor(level: Int): Pair<Long, Int> = when (clamp(level)) {
        LIGHT -> 8L to 80
        STRONG -> 18L to 255
        else -> 12L to 200
    }

    private class Prepared(
        val context: Context,
        val vibrator: Vibrator?,
        val effects: Array<VibrationEffect?>,
        val durations: LongArray,
        val attributes: VibrationAttributes?,
    )

    @Volatile private var prepared: Prepared? = null

    fun prepare(context: Context) {
        val app = context.applicationContext
        if (prepared?.context === app) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val available = vibrator?.takeIf { it.hasVibrator() }
            val effects = arrayOfNulls<VibrationEffect>(8)
            val durations = LongArray(8)
            val attributes = if (Build.VERSION.SDK_INT >= 33) VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build() else null
            val tick = available != null && Build.VERSION.SDK_INT >= 31 && available.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            val click = available != null && Build.VERSION.SDK_INT >= 31 && available.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            for (strength in LIGHT..STRONG) {
                val (duration, amplitude) = strengthFor(strength)
                for (emphasis in EMPHASIS_NONE..EMPHASIS_LONG) {
                    val index = strength * 2 + emphasis
                    durations[index] = duration + if (emphasis == EMPHASIS_LONG) 10L else 0L
                    if (available == null || Build.VERSION.SDK_INT < 26) continue
                    effects[index] = if (Build.VERSION.SDK_INT >= 31 && emphasis == EMPHASIS_NONE && (if (strength == LIGHT) tick else click)) {
                        val primitive = if (strength == LIGHT) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
                        VibrationEffect.startComposition().addPrimitive(primitive, if (strength == LIGHT) 0.7f else 1f).compose()
                    } else VibrationEffect.createOneShot(durations[index], amplitude)
                }
            }
            prepared = Prepared(app, available, effects, durations, attributes)
        } catch (_: RuntimeException) {
            prepared = Prepared(app, null, arrayOfNulls(8), LongArray(8), null)
        }
    }

    fun tap(context: Context, level: Int, emphasis: Int = EMPHASIS_NONE) {
        tapMeasured(context, level, emphasis)
    }

    fun tapMeasured(context: Context, level: Int, emphasis: Int = EMPHASIS_NONE, touchAtNanos: Long = 0L): Long {
        val helperAt = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
        val strength = clamp(level)
        if (strength == OFF) return 0L
        prepare(context)
        val state = prepared ?: return 0L
        val vibrator = state.vibrator ?: return 0L
        val index = strength * 2 + if (emphasis == EMPHASIS_LONG) 1 else 0
        val requestAt = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(state.effects[index]!!, state.attributes!!)
            } else if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(state.effects[index]!!)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(state.durations[index])
            }
        } catch (_: RuntimeException) {
            prepared = Prepared(state.context, null, state.effects, state.durations, state.attributes)
            return 0L
        } finally {
            if (BuildConfig.DEBUG) KeyLatencyProbe.haptic(touchAtNanos, helperAt, requestAt, SystemClock.elapsedRealtimeNanos())
        }
        return requestAt
    }

    fun levelFor(kind: Int): Int = when (kind) {
        HapticFeedbackConstants.CLOCK_TICK -> LIGHT
        HapticFeedbackConstants.LONG_PRESS -> STRONG
        HapticFeedbackConstants.REJECT -> STRONG
        HapticFeedbackConstants.CONFIRM -> STRONG
        else -> MEDIUM
    }

    fun viewFallback(view: View, level: Int) {
        if (clamp(level) == OFF || !view.isHapticFeedbackEnabled) return
        @Suppress("DEPRECATION")
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
