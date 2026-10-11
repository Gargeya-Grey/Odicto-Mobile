package app.odicto.mobile.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import app.odicto.mobile.R
import app.odicto.mobile.storage.VoicePreferences
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Session-local presentation only. Collapsing never detaches the IME's editor or voice target.
 *
 * The IME window keeps the expanded height in both states. Collapsed, the column is pushed down by
 * the typing area's height so only the top row shows, and [contentTop] tells the service which part
 * of the window the host app must keep clear. Motion is a translation of the column, so no frame
 * resizes the IME window or relayouts the host app. The host app is resized once per transition:
 * before an expand starts (the keys slide into space that is already free) and after a collapse
 * ends (the keys leave before the app takes the space back).
 */
internal class KeyboardExpansion(
    private val root: View,
    private val cancelTouch: () -> Unit,
    private val insetsChanged: () -> Unit,
) {
    private val column = root.findViewById<View>(R.id.ime_column)
    private val area = root.findViewById<View>(R.id.ime_typing_area)
    private val tools = root.findViewById<View>(R.id.function_row)
    private val toggle = root.findViewById<ImageButton>(R.id.ime_keyboard_toggle)
    private var expanded = false
    private var recovery = false
    /** True while the host app is told about the full keyboard height. */
    private var insetExpanded = false
    private var animation: ValueAnimator? = null
    var onPresentationChanged: ((Boolean) -> Unit)? = null
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { render(false) }
    }
    private val sizeWatcher = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        // A new grid height (rotation, key size) moves the collapsed resting place.
        if (bottom - top != oldBottom - oldTop && animation == null) settle()
    }

    init {
        root.background = NavigationBand(root)
        toggle.setOnClickListener {
            Haptics.tap(root.context, VoicePreferences.levelOf(VoicePreferences.state.value))
            expanded = !expanded
            render(true)
        }
        area.addOnAttachStateChangeListener(attachment)
        area.addOnLayoutChangeListener(sizeWatcher)
        render(false)
    }

    val isExpanded: Boolean get() = expanded

    fun reset() { expanded = false; render(false) }
    fun recoveryVisible(value: Boolean) {
        if (recovery == value) return
        recovery = value
        render(false)
    }
    fun dispose() {
        finishAnimation()
        area.removeOnAttachStateChangeListener(attachment)
        area.removeOnLayoutChangeListener(sizeWatcher)
        toggle.setOnClickListener(null)
    }

    /** Root-relative y of the top edge the host app must keep clear. */
    fun contentTop(): Int = column.top + if (insetExpanded) 0 else hiddenOffset()

    /** Root-relative y where the keyboard is drawn now, for overlays that avoid it. */
    fun visibleTop(): Int = (column.top + column.translationY).toInt()

    private fun shown() = expanded || recovery
    private fun hiddenOffset() = area.height

    private fun finishAnimation() {
        animation?.apply { removeAllListeners(); removeAllUpdateListeners(); cancel() }
        animation = null
    }

    private fun setInsetExpanded(value: Boolean) {
        if (insetExpanded == value) return
        insetExpanded = value
        insetsChanged()
    }

    /** Final state with no motion: also the state a detached or freshly created view starts in. */
    private fun settle() {
        val show = shown()
        column.translationY = if (show) 0f else hiddenOffset().toFloat()
        area.visibility = if (show) View.VISIBLE else View.INVISIBLE
        tools.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
        setInsetExpanded(show)
        root.invalidate()
    }

    private fun render(animate: Boolean) {
        cancelTouch()
        finishAnimation()
        toggle.contentDescription = if (expanded) "Hide keyboard" else "Show keyboard"
        toggle.isSelected = expanded
        toggle.setImageResource(if (expanded) R.drawable.odicto_ic_keyboard_hide else R.drawable.odicto_ic_keyboard)
        onPresentationChanged?.invoke(expanded && !recovery)
        val show = shown()
        val target = if (show) 0f else hiddenOffset().toFloat()
        val from = column.translationY
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (!animate || !column.isAttachedToWindow || !enabled || hiddenOffset() == 0 || from == target) {
            settle()
            return
        }
        area.visibility = View.VISIBLE
        if (expanded) tools.visibility = View.VISIBLE
        if (show) setInsetExpanded(true)
        val distance = abs(target - from) / hiddenOffset()
        animation = ValueAnimator.ofFloat(from, target).apply {
            duration = ((if (show) OPEN_MS else CLOSE_MS) * distance).roundToLong().coerceAtLeast(1L)
            interpolator = if (show) OPEN_CURVE else CLOSE_CURVE
            addUpdateListener {
                column.translationY = it.animatedValue as Float
                root.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) { animation = null; settle() }
            })
            start()
        }
    }

    /** Paints the keyboard surface from the column's drawn top to the bottom, nav bar included. */
    private inner class NavigationBand(private val owner: View) : Drawable() {
        private val paint = Paint().apply { color = owner.context.getColor(R.color.odicto_keyboard_background) }
        override fun draw(canvas: Canvas) {
            val top = (bounds.top + visibleTop()).coerceIn(bounds.top, bounds.bottom)
            canvas.drawRect(bounds.left.toFloat(), top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val OPEN_MS = 300L
        const val CLOSE_MS = 220L
        // The system IME curve to open (no first-frame jump); emphasized accelerate to close.
        val OPEN_CURVE = PathInterpolator(0.2f, 0f, 0f, 1f)
        val CLOSE_CURVE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
