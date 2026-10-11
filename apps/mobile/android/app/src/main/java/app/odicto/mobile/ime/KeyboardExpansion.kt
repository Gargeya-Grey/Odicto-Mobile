package app.odicto.mobile.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import app.odicto.mobile.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Session-local presentation only. Collapsing never detaches the IME's editor or voice target. */
internal class KeyboardExpansion(root: View, private val cancelTouch: () -> Unit) {
    private val area = root.findViewById<View>(R.id.ime_typing_area)
    private val reveal = KeyboardRevealLayout(area) { width -> maximumTypingHeight(root, width) }
    private val assistance = root.findViewById<View>(R.id.ime_assistance)
    private val tools = root.findViewById<View>(R.id.function_row)
    private val toggle = root.findViewById<ImageButton>(R.id.ime_keyboard_toggle)
    private var expanded = false
    private var recovery = false
    private var animation: ValueAnimator? = null
    var onPresentationChanged: ((Boolean) -> Unit)? = null
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { render(false) }
    }

    init {
        toggle.setOnClickListener {
            Haptics.tap(root.context, app.odicto.mobile.storage.VoicePreferences.levelOf(app.odicto.mobile.storage.VoicePreferences.state.value))
            expanded = !expanded
            render(true)
        }
        area.addOnAttachStateChangeListener(attachment)
        render(false)
    }

    fun reset() { expanded = false; render(false) }
    fun recoveryVisible(value: Boolean) {
        if (recovery == value) return
        recovery = value
        render(false)
    }
    fun dispose() {
        finishAnimation()
        settle()
        area.removeOnAttachStateChangeListener(attachment)
        toggle.setOnClickListener(null)
    }

    private fun finishAnimation() {
        animation?.apply { removeAllListeners(); removeAllUpdateListeners(); cancel() }
        animation = null
    }

    private fun settle(keepContentSize: Boolean = false) {
        val show = expanded || recovery
        reveal.settle(keepContentSize && show)
        reveal.visibility = if (show) View.VISIBLE else View.GONE
        area.visibility = reveal.visibility
        tools.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun render(animate: Boolean) {
        cancelTouch()
        finishAnimation()
        toggle.contentDescription = if (expanded) "Hide keyboard" else "Show keyboard"
        toggle.isSelected = expanded
        val headerHeight = if (assistance.visibility == View.VISIBLE) assistance.height else 0
        onPresentationChanged?.invoke(expanded && !recovery)
        toggle.setImageResource(if (expanded) R.drawable.odicto_ic_keyboard_hide else R.drawable.odicto_ic_keyboard)
        val show = expanded || recovery
        val from = if (reveal.visibility == View.VISIBLE && area.height > 0)
            (reveal.height.toFloat() / (area.height + area.top)).coerceIn(0f, 1f) else 0f
        val target = if (show) 1f else 0f
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (!animate || !area.isAttachedToWindow || !enabled || (reveal.parent as View).width == 0 || from == target) {
            settle()
            return
        }
        // The whole typing area leaves together. Removing the footer here would change key
        // geometry before the first collapse frame and create an abrupt extra height change.
        val lostHeader = if (!show && assistance.visibility == View.GONE) headerHeight else 0
        if (expanded) tools.visibility = View.VISIBLE
        reveal.begin(show, lostHeader)
        reveal.visibility = View.VISIBLE
        area.visibility = View.VISIBLE
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (200L * abs(target - from)).roundToLong().coerceAtLeast(1L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { reveal.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) { animation = null; settle(true) }
            })
            start()
        }
    }

    private fun maximumTypingHeight(root: View, width: Int): Int {
        // An IME's own window shrinks during the reveal. Its display frame remains the available
        // display region; rootView.height/currentWindowMetrics would feed the animation back in.
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        val available = if (!frame.isEmpty) frame.height() else if (Build.VERSION.SDK_INT >= 30) {
            val metrics = root.context.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val safe = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            metrics.bounds.height() - safe.top - safe.bottom
        } else root.resources.displayMetrics.heightPixels
        val column = root.findViewById<View>(R.id.ime_column)
        val top = root.findViewById<View>(R.id.ime_top_controls)
        top.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), View.MeasureSpec.AT_MOST))
        return (available - root.paddingTop - root.paddingBottom - column.paddingTop - column.paddingBottom - top.measuredHeight)
            .coerceAtLeast(0)
    }
}

/**
 * A changing viewport over full-size content. Only the display budget may size the content during
 * motion: an IME WindowManager can feed the last revealed height back as the parent's height cap.
 * Its top-aligned content slides into the bottom-anchored IME as the clipped viewport grows.
 */
private class KeyboardRevealLayout(private val content: View, private val maximumHeight: (Int) -> Int) : FrameLayout(content.context) {
    private data class Transition(val opening: Boolean, val startHeight: Int, val topGap: Int)
    private var transition: Transition? = null
    private var fullContentHeight: Int? = null
    private var contentWidth = 0
    var progress = 1f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            requestLayout()
        }

    fun begin(opening: Boolean, lostHeader: Int) {
        val visible = visibility == View.VISIBLE
        if (fullContentHeight == null) {
            if (visible && content.height > 0) {
                fullContentHeight = content.height
                contentWidth = content.width
            } else measureFullContent((parent as View).width)
        }
        transition = Transition(opening, (if (visible) height else 0) + lostHeader,
            (if (visible) content.top else 0) + lostHeader)
        progress = 0f
        requestLayout()
    }

    fun settle(keepContentSize: Boolean) {
        transition = null
        fullContentHeight = if (keepContentSize) fullContentHeight?.coerceAtMost(maximumHeight(contentWidth)) else null
        progress = 1f
        requestLayout()
    }

    private fun measureFullContent(width: Int) {
        contentWidth = width
        content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maximumHeight(width), MeasureSpec.AT_MOST))
        fullContentHeight = content.measuredHeight
    }

    init {
        val parent = content.parent as ViewGroup
        val index = parent.indexOfChild(content)
        // Weight makes LinearLayout measure this viewport a second time at its already revealed
        // height, which would feed the shrinking height back into the key rows.
        val params = (content.layoutParams as LinearLayout.LayoutParams).apply { weight = 0f }
        parent.removeView(content)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        parent.addView(this, index, params)
        clipChildren = true
        clipToPadding = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = transition
        if (fullContentHeight != null && MeasureSpec.getSize(widthMeasureSpec) != contentWidth) {
            measureFullContent(MeasureSpec.getSize(widthMeasureSpec))
        }
        // Keep the final full-size content through the last window-resize acknowledgement too.
        // Once the non-animated parent can fit it, ordinary bounded measurement takes over again.
        if (state == null && fullContentHeight != null && (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ||
                MeasureSpec.getSize(heightMeasureSpec) >= fullContentHeight!!)) fullContentHeight = null
        val fixedHeight = fullContentHeight
        val fullHeight = if (fixedHeight == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            measuredHeight
        } else {
            content.measure(getChildMeasureSpec(widthMeasureSpec, 0, LayoutParams.MATCH_PARENT),
                MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY))
            fixedHeight
        }
        val visibleHeight = if (state == null) fullHeight else {
            val target = if (state.opening) fullHeight else 0
            (state.startHeight + (target - state.startHeight) * progress).roundToInt()
        }
        setMeasuredDimension(resolveSizeAndState(content.measuredWidth, widthMeasureSpec, content.measuredState), visibleHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val state = transition
        // On reversal the retired header's gap goes away continuously, rather than snapping the
        // visible keys upward. No gap survives completion, reset, recovery or a detached view.
        val gap = if (state == null) 0 else if (state.opening) (state.topGap * (1f - progress)).roundToInt() else state.topGap
        content.layout(0, gap, content.measuredWidth, gap + content.measuredHeight)
    }
}
