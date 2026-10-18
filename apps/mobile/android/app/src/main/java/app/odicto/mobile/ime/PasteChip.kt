package app.odicto.mobile.ime

import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.odicto.mobile.R

/**
 * The quiet "Paste" pill in the header's centre slot. It only draws: [PasteSuggestion] decides whether
 * there is an offer, and the owner decides whether this field and moment may show it.
 */
internal class PasteChip(
    private val slot: FrameLayout,
    private val paste: () -> Unit,
    private val dismiss: () -> Unit,
) {
    private val context = slot.context
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private val jade = context.getColor(R.color.odicto_accent)
    private val muted = context.getColor(R.color.odicto_text_muted)

    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.odicto_ic_clipboard)
        setColorFilter(jade)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = TextView(context).apply {
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        typeface = OdictoKeyboardView.typefaceFor(context)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val close = ImageView(context).apply {
        setImageResource(R.drawable.odicto_ic_close)
        setColorFilter(context.getColor(R.color.odicto_paste_close))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        contentDescription = "Dismiss paste suggestion"
        PressSurface.styleIcon(this)
        setOnClickListener { dismiss() }
    }
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(11), 0, dp(3), 0)
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.odicto_paste_fill))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), context.getColor(R.color.odicto_paste_stroke))
        }
        setOnClickListener { paste() }
    }

    init {
        PressSurface.pressInSoft(pill)
        pill.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)))
        pill.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(7); marginEnd = dp(1) })
        pill.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
        slot.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.CENTER))
        slot.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ -> fit(right - left) }
    }

    val shown: Boolean get() = slot.visibility == View.VISIBLE

    /** Keeps the dismiss button on screen: the text gives way, never the button. */
    private fun fit(width: Int) {
        val room = width - dp(11 + 16 + 7 + 1 + 30 + 3) - dp(2)
        label.maxWidth = room.coerceAtLeast(dp(48))
    }

    fun show(text: String, animate: Boolean) {
        val preview = PasteSuggestion.preview(text)
        val line = SpannableString(if (preview == null) "Paste copied text" else "Paste  “$preview”")
        line.setSpan(ForegroundColorSpan(jade), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (line.length > 5) line.setSpan(ForegroundColorSpan(muted), 5, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (label.text.toString() != line.toString()) label.text = line
        pill.contentDescription = if (preview == null) "Paste copied text" else "Paste copied text: $preview"
        if (slot.width > 0) fit(slot.width)
        if (shown) return
        slot.animate().cancel()
        slot.visibility = View.VISIBLE
        if (animate) {
            // transitions.dev toast: it rises a few dp, fades and settles from 0.97, on the smooth-out curve.
            pill.scaleX = 0.97f; pill.scaleY = 0.97f; pill.translationY = dp(6).toFloat()
            slot.alpha = 0f
            slot.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(PressSurface.SMOOTH_OUT).start()
            pill.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(ENTER_MS).setInterpolator(PressSurface.SMOOTH_OUT).start()
        } else {
            slot.alpha = 1f
            pill.scaleX = 1f; pill.scaleY = 1f; pill.translationY = 0f
        }
    }

    fun hide() {
        if (!shown) return
        slot.animate().cancel()
        pill.animate().cancel()
        pill.scaleX = 1f; pill.scaleY = 1f; pill.translationY = 0f
        slot.alpha = 1f
        slot.visibility = View.GONE
    }

    private companion object {
        const val ENTER_MS = 250L
    }
}
