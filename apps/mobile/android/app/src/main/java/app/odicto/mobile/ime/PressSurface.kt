package app.odicto.mobile.ime

import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.PathInterpolator
import app.odicto.mobile.R

/**
 * How a pressed control looks: a soft rounded square that fades in as the finger lands and out as it
 * leaves, and icon-style controls pressing in a little. There is no spreading ripple circle, which on a
 * small icon reads as a toy. Timing follows the transitions.dev scale: a quick in, a gentle smooth-out.
 */
internal object PressSurface {
    private const val PRESS_IN_MS = 80L
    private const val PRESS_OUT_MS = 250L
    private const val SELECT_MS = 200L

    /** A square-ish icon button: soft rounded fill, plus a quiet fill that stays while it is selected. */
    fun icon(context: Context): Drawable {
        val d = context.resources.displayMetrics.density
        // The tile hugs the icon: a cell can be over 100dp wide on the tool row, and a bar that wide stops looking
        // like a button.
        return SoftPress(
            radius = 13f * d, insetH = 4f * d, insetV = 6f * d,
            press = context.getColor(R.color.odicto_press_fill), selected = context.getColor(R.color.odicto_selected_fill), maxWidth = 56f * d,
        )
    }

    /** A text or emoji tile, or a card: [radiusDp] corners, optionally with a resting [base] fill. */
    fun tile(context: Context, radiusDp: Float, base: Int = 0, insetDp: Float = 0f, insetVDp: Float = insetDp): Drawable {
        val d = context.resources.displayMetrics.density
        return SoftPress(radius = radiusDp * d, insetH = insetDp * d, insetV = insetVDp * d, press = context.getColor(R.color.odicto_press_fill), base = base)
    }

    /** Icon-style controls press in to 0.9 and spring back. */
    fun pressIn(view: View) {
        view.stateListAnimator = AnimatorInflater.loadStateListAnimator(view.context, R.animator.odicto_press)
    }

    /** Wide surfaces press in to 0.97 only. */
    fun pressInSoft(view: View) {
        view.stateListAnimator = AnimatorInflater.loadStateListAnimator(view.context, R.animator.odicto_press_soft)
    }

    /** The standard icon button: soft fill and the press-in. */
    fun styleIcon(view: View) {
        view.background = icon(view.context)
        pressIn(view)
    }

    private class SoftPress(
        private val radius: Float,
        private val insetH: Float,
        private val insetV: Float,
        private val press: Int,
        private val selected: Int = 0,
        private val base: Int = 0,
        private val maxWidth: Float = 0f,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var pressLevel = 0f
        private var selectLevel = 0f
        private var pressAnimator: ValueAnimator? = null
        private var selectAnimator: ValueAnimator? = null

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val pressed = state.contains(android.R.attr.state_pressed) && state.contains(android.R.attr.state_enabled)
            val isSelected = state.contains(android.R.attr.state_selected)
            pressAnimator = glide(pressAnimator, pressLevel, if (pressed) 1f else 0f, if (pressed) PRESS_IN_MS else PRESS_OUT_MS) { pressLevel = it }
            if (selected != 0) selectAnimator = glide(selectAnimator, selectLevel, if (isSelected) 1f else 0f, SELECT_MS) { selectLevel = it }
            return true
        }

        private fun glide(running: ValueAnimator?, from: Float, to: Float, duration: Long, set: (Float) -> Unit): ValueAnimator? {
            running?.cancel()
            if (from == to) return null
            if (!isVisible) { set(to); invalidateSelf(); return null }
            return ValueAnimator.ofFloat(from, to).apply {
                this.duration = duration
                interpolator = SMOOTH_OUT
                addUpdateListener { set(it.animatedValue as Float); invalidateSelf() }
                start()
            }
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            if (!visible) {
                pressAnimator?.cancel(); selectAnimator?.cancel()
                pressLevel = 0f
            }
            return super.setVisible(visible, restart)
        }

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            rect.inset(insetH, insetV)
            if (maxWidth > 0f && rect.width() > maxWidth) rect.inset((rect.width() - maxWidth) / 2f, 0f)
            if (base != 0) { paint.color = base; canvas.drawRoundRect(rect, radius, radius, paint) }
            if (selected != 0 && selectLevel > 0f) { paint.color = withAlpha(selected, selectLevel); canvas.drawRoundRect(rect, radius, radius, paint) }
            if (pressLevel > 0f) { paint.color = withAlpha(press, pressLevel); canvas.drawRoundRect(rect, radius, radius, paint) }
        }

        private fun withAlpha(color: Int, level: Float): Int {
            val alpha = ((color ushr 24) * level).toInt().coerceIn(0, 255)
            return (alpha shl 24) or (color and 0x00FFFFFF)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** transitions.dev "smooth-out". */
    val SMOOTH_OUT = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** False when the user has turned animations off, or the view is not on screen: then nothing moves. */
    fun canAnimate(view: View): Boolean =
        view.isAttachedToWindow && (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())

    /** transitions.dev text swap: the new content rises a few dp into place as it fades in. */
    fun riseIn(view: View, distanceDp: Float = 4f, durationMs: Long = 150L) {
        if (!canAnimate(view)) return
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = distanceDp * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(durationMs).setInterpolator(SMOOTH_OUT).start()
    }

    /** transitions.dev toast: rises, fades and settles from 0.97 on the smooth-out curve. */
    fun toastIn(view: View) {
        if (!canAnimate(view)) return
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = 0.97f; view.scaleY = 0.97f
        view.translationY = 12f * view.resources.displayMetrics.density
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(250L).setInterpolator(SMOOTH_OUT).start()
    }

    /** Puts a view back to rest, with nothing left running, so the next show starts clean. */
    fun rest(view: View) {
        view.animate().cancel()
        view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; view.translationX = 0f; view.translationY = 0f
    }

    /** An eased change of opacity, or an immediate one when nothing should move. */
    fun fadeTo(view: View, alpha: Float, durationMs: Long = 180L) {
        view.animate().cancel()
        if (!canAnimate(view)) { view.alpha = alpha; return }
        view.animate().alpha(alpha).setDuration(durationMs).setInterpolator(SMOOTH_OUT).start()
    }

    /**
     * transitions.dev error shake: 6dp one way, back past centre by the same, then a smaller overshoot each way,
     * and settle. Each leg is eased on its own (80, 80, 60, 60, 100ms): one curve across the whole sequence would
     * spend nearly all of the motion in its first instants. It moves translationX only, so it never fights the
     * view's entrance or press animation.
     */
    fun shake(view: View, delayMs: Long = 0L) {
        if (!canAnimate(view)) return
        val d = view.resources.displayMetrics.density
        val legs = listOf(-6f to 80L, 6f to 80L, -4f to 60L, 3f to 60L, 0f to 100L).map { (dx, ms) ->
            android.animation.ObjectAnimator.ofFloat(view, View.TRANSLATION_X, dx * d).apply {
                duration = ms
                interpolator = SMOOTH_OUT
            }
        }
        android.animation.AnimatorSet().apply {
            playSequentially(legs)
            startDelay = delayMs
            start()
        }
    }
}
