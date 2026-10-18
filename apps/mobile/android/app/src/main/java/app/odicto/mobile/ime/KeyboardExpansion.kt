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
 * The IME window keeps one height in every state. Collapsed, the column is pushed down by the typing
 * area's height so only the header shows (the voice row and the quick-edit row), and [contentTop] tells
 * the service which part of the window the host app must keep clear. Expanded, the quick-edit row is
 * redundant with the grid's own bottom row, so it is squeezed away: the typing area slides up over it
 * while it shrinks and fades, and the column settles lower by the row's height, leaving that much
 * transparent window above the keyboard exactly as the collapsed state does. Motion is only
 * translation, scale and alpha, so no frame resizes the IME window or relayouts the host app. The host app is resized once per transition:
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
    private val quick = root.findViewById<View>(R.id.ime_quick_keys)
    private var expanded = false
    /** How far the typing area is revealed (0 hidden, 1 shown) and how far the quick row is squeezed (0 whole, 1 gone). */
    private var reveal = 0f
    private var squeeze = 0f
    /** The squeeze the host app is told about, held until a collapse has finished. */
    private var insetSqueeze = 0
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
        quick.pivotY = 0f
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
        toggle.animate().cancel()
        toggle.setOnClickListener(null)
    }

    /** Root-relative y of the top edge the host app must keep clear. */
    fun contentTop(): Int = column.top + if (insetExpanded) insetSqueeze else hiddenOffset()

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

    private fun setInsetSqueeze(px: Int) {
        if (insetSqueeze == px) return
        insetSqueeze = px
        if (insetExpanded) insetsChanged()
    }

    /** Both inset facts at once, so the host app is told exactly once however they change together. */
    private fun setInsets(expandedValue: Boolean, squeezePx: Int) {
        val changed = insetExpanded != expandedValue || (expandedValue && insetSqueeze != squeezePx)
        insetExpanded = expandedValue
        insetSqueeze = squeezePx
        if (changed) insetsChanged()
    }

    private var toggleIcon = 0

    /** transitions.dev icon swap: the old glyph dips out, the new one settles in, so the toggle never just snaps. */
    private fun swapToggleIcon(res: Int, animate: Boolean) {
        if (toggleIcon == res) return
        val first = toggleIcon == 0
        toggleIcon = res
        toggle.animate().cancel()
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (first || !animate || !toggle.isAttachedToWindow || !enabled) {
            toggle.setImageResource(res); toggle.alpha = 1f
            return
        }
        toggle.animate().alpha(0f).setDuration(80).withEndAction {
            toggle.setImageResource(res)
            toggle.animate().alpha(1f).setDuration(170).setInterpolator(PressSurface.SMOOTH_OUT).start()
        }.start()
    }

    /** One place that turns the two fractions into pixels, for both the settled and the animated frames. */
    private fun present(newReveal: Float, newSqueeze: Float) {
        reveal = newReveal
        squeeze = newSqueeze
        val gone = quick.height.toFloat() * newSqueeze
        column.translationY = hiddenOffset() * (1f - newReveal) + gone
        area.translationY = -gone
        quick.scaleY = 1f - newSqueeze
        // The row thins out faster than it shrinks, so the keys sliding up never pass over live text.
        quick.alpha = (1f - newSqueeze * 1.6f).coerceIn(0f, 1f)
    }

    /** Final state with no motion: also the state a detached or freshly created view starts in. */
    private fun settle() {
        val show = shown()
        present(if (show) 1f else 0f, if (expanded) 1f else 0f)
        area.visibility = if (show) View.VISIBLE else View.INVISIBLE
        tools.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
        quick.visibility = if (expanded) View.INVISIBLE else View.VISIBLE
        setInsets(show, if (expanded) quick.height else 0)
        root.invalidate()
    }

    private fun render(animate: Boolean) {
        cancelTouch()
        finishAnimation()
        toggle.contentDescription = if (expanded) "Hide keyboard" else "Show keyboard"
        toggle.isSelected = expanded
        swapToggleIcon(if (expanded) R.drawable.odicto_ic_keyboard_hide else R.drawable.odicto_ic_keyboard, animate)
        onPresentationChanged?.invoke(expanded && !recovery)
        val show = shown()
        val revealTo = if (show) 1f else 0f
        val squeezeTo = if (expanded) 1f else 0f
        val revealFrom = reveal
        val squeezeFrom = squeeze
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        // Told before the motion starts, so the keys slide into space the app has already given up.
        if (expanded) setInsetSqueeze(quick.height)
        if (!animate || !column.isAttachedToWindow || !enabled || (hiddenOffset() == 0 && quick.height == 0) ||
            (revealFrom == revealTo && squeezeFrom == squeezeTo)) {
            settle()
            return
        }
        area.visibility = View.VISIBLE
        quick.visibility = View.VISIBLE
        if (expanded) tools.visibility = View.VISIBLE
        if (show) setInsetExpanded(true)
        val opening = revealTo + squeezeTo > revealFrom + squeezeFrom
        val distance = maxOf(abs(revealTo - revealFrom), abs(squeezeTo - squeezeFrom))
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ((if (opening) OPEN_MS else CLOSE_MS) * distance).roundToLong().coerceAtLeast(1L)
            interpolator = if (opening) OPEN_CURVE else CLOSE_CURVE
            addUpdateListener {
                val u = it.animatedValue as Float
                present(revealFrom + (revealTo - revealFrom) * u, squeezeFrom + (squeezeTo - squeezeFrom) * u)
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
