package app.odicto.mobile.overlay

import android.animation.ValueAnimator
import android.content.*
import android.graphics.*
import android.os.Build
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import app.odicto.mobile.MainActivity
import app.odicto.mobile.dictation.*
import app.odicto.mobile.storage.VoicePreferences
import kotlin.math.*

/** Native touch surface. No WebView, network work, or layout animation on pointer moves. */
class VoiceControlView(context: Context, private val keyboard: Boolean = false) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    var resize: ((Int, Int) -> Unit)? = null
    var drag: ((Float, Float) -> Unit)? = null
    var dragEnd: (() -> Unit)? = null
    var mirrored = false
        set(value) { if (field != value) { field = value; render(ui) } }
    private var expanded = keyboard
    private var ui = VoiceSession.state.value
    private var held = false
    private var moving = false
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private val tapSequence = VoiceGestureTiming()
    private var target = ""
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var surfaceWidth = 72; private var surfaceHeight = 72
    val requiredWidth get() = dp(surfaceWidth)
    val requiredHeight get() = dp(surfaceHeight)
    private var lastLayout = ""
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = round(0xF51B1B23.toInt(), 20f)
    }
    private val status = TextView(context).apply { setTextColor(0xFFC6B7FF.toInt()); textSize = 12f; typeface = Typeface.create("sans-serif-medium", 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val preview = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) }
    private val mic = Icon(context, "mic", "Hold to speak. Double tap for options.")
    private val lock = Icon(context, "lock", "Lock recording hands-free")
    private val raw = Icon(context, "raw", "Raw transcription with Groq")
    private val ai = Icon(context, "ai", "AI answer")
    private val live = Icon(context, "live", "Live transcription")
    private val settings = Icon(context, "settings", "Open Odicto settings")
    private val back = Icon(context, "back", "Collapse voice controls")
    private val cancel = Icon(context, "cancel", "Cancel recording")
    private val copy = Icon(context, "copy", "Copy available text")
    private val pill = View(context).apply { background = round(0xFF202029.toInt(), 36f) }
    private val beginHold = Runnable {
        if (!moving && !ui.busy && DictationCoordinator.activeEditorSession != null) {
            held = true; tapSequence.reset(); VoiceSession.start(context); feedback(HapticFeedbackConstants.CLOCK_TICK); render(VoiceSession.state.value)
        }
    }
    init {
        clipChildren = false; clipToPadding = false
        panel.addView(status); panel.addView(preview)
        addView(pill); addView(panel)
        listOf(back, settings, raw, ai, live, lock, cancel, copy, mic).forEach { addView(it) }
        mic.setOnClickListener { if (ui.active) VoiceSession.finish(context) else if (!ui.busy) VoiceSession.start(context, true) }
        val optionsAction = View.generateViewId()
        mic.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (!ui.busy) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(optionsAction, "Show voice options"))
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: android.os.Bundle?): Boolean {
                if (action == optionsAction && !ui.busy) { expanded = true; render(ui); return true }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
        mic.setOnTouchListener { _, event -> touch(event) }
        lock.setOnClickListener { VoiceSession.lock(); feedback(HapticFeedbackConstants.LONG_PRESS) }
        raw.setOnClickListener { choose("raw") }; ai.setOnClickListener { choose("ai") }; live.setOnClickListener { choose("live") }
        back.setOnClickListener { expanded = false; if (!ui.busy) VoiceSession.update { VoiceUi(mode = VoicePreferences.state.value.mode) }; render(VoiceSession.state.value); feedback(HapticFeedbackConstants.CLOCK_TICK) }
        settings.setOnClickListener {
            if (!ui.busy) { feedback(HapticFeedbackConstants.CLOCK_TICK); context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("voice_settings", true)) }
        }
        cancel.setOnClickListener { VoiceSession.cancel(context); feedback(HapticFeedbackConstants.LONG_PRESS) }
        copy.setOnClickListener {
            val text = listOf(ui.finalText, ui.interim).filter { it.isNotBlank() }.joinToString(" ")
            if (text.isNotBlank()) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Odicto", text)); Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show(); feedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
        render(ui)
    }
    private fun round(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), 0x24FFFFFF) }
    private fun feedback(kind: Int) { if (VoicePreferences.state.value.haptics && isShown && isAttachedToWindow) performHapticFeedback(kind) }
    private fun choose(mode: String) { VoiceSession.mode(context, mode); feedback(HapticFeedbackConstants.CLOCK_TICK) }
    private fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
        view.layoutParams = LayoutParams(dp(w), dp(h)).apply { leftMargin = dp(if (mirrored) surfaceWidth - x - w else x); topMargin = dp(y) }
    }
    fun render(value: VoiceUi) {
        val previous = ui
        ui = value
        val showPreview = VoicePreferences.state.value.showTextPreview
        val hasMessage = (value.mode != "live" && (value.busy || value.phase == "done")) || value.phase == "error"
        val compactStatus = hasMessage && !showPreview && value.phase != "error"
        val shortWindow = resources.configuration.screenHeightDp < 480
        val signature = "${value.phase}:${value.locked}:${value.mode}:$expanded:$mirrored:${DictationCoordinator.activeEditorSession}:${VoicePreferences.state.value.mode}:$showPreview:$shortWindow"
        if (lastLayout == signature) {
            if (showPreview && hasMessage && (value.finalText != previous.finalText || value.interim != previous.interim)) updatePreview(value)
            if (!value.active && value.message != previous.message) status.text = value.message
            mic.level = value.level; return
        }
        lastLayout = signature
        if (value.phase != previous.phase) {
            when (value.phase) {
                "recording" -> feedback(HapticFeedbackConstants.LONG_PRESS)
                "done" -> feedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                "error" -> feedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
            }
        }
        val full = expanded || hasMessage || keyboard || value.busy
        val newWidth = if (full) 304 else 72
        val newHeight = if (!hasMessage || compactStatus) 72 else if (!showPreview) 144 else if (shortWindow) 164 else 184
        val changed = surfaceWidth != newWidth || surfaceHeight != newHeight
        surfaceWidth = newWidth; surfaceHeight = newHeight
        if (changed) resize?.invoke(dp(surfaceWidth), dp(surfaceHeight))
        val row = surfaceHeight - 64
        val mx = surfaceWidth - 64
        listOf(pill, panel, back, settings, raw, ai, live, lock, cancel, copy).forEach { it.visibility = View.GONE }
        place(mic, mx, row, 56, 56)
        if ((expanded || keyboard) && !hasMessage && !value.busy) {
            pill.visibility = View.VISIBLE; place(pill, 4, row, 296, 56)
            listOf(back, settings, raw, ai, live).forEach { it.visibility = View.VISIBLE }
            place(back, 8, row + 8, 40, 40); place(settings, 52, row + 8, 40, 40)
            place(raw, 96, row + 8, 40, 40); place(ai, 140, row + 8, 40, 40); place(live, 184, row + 8, 40, 40)
            settings.isEnabled = !value.busy
        }
        if (value.active) {
            pill.visibility = View.GONE; back.visibility = View.GONE; settings.visibility = View.GONE
            raw.visibility = View.GONE; ai.visibility = View.GONE; live.visibility = View.GONE
            lock.visibility = View.VISIBLE
            if (value.locked) lock.visibility = View.GONE
            place(lock, 184, row + 4, 48, 48)
        }
        if (value.busy) {
            cancel.visibility = View.VISIBLE; place(cancel, 128, row + 4, 48, 48)
        }
        if (hasMessage) {
            panel.visibility = View.VISIBLE
            // Without a transcript, status shares the controls row instead of reserving a card.
            panel.background = if (compactStatus) null else round(0xF51B1B23.toInt(), 20f)
            panel.setPadding(dp(if (compactStatus) 4 else 16), dp(if (compactStatus) 0 else 12), dp(if (compactStatus) 4 else 16), dp(if (compactStatus) 0 else 12))
            place(panel, 8, if (compactStatus) row + 4 else 8, if (compactStatus) 112 else 288, if (compactStatus) 48 else surfaceHeight - 76)
            panel.gravity = if (compactStatus) Gravity.CENTER_VERTICAL else Gravity.TOP
            preview.visibility = if (showPreview) View.VISIBLE else View.GONE
            preview.maxLines = if (shortWindow) 2 else 3
            status.text = when { value.locked -> if (compactStatus) "Hands-free" else "Hands-free · Tap mic to finish"; value.phase == "recording" -> "${when (value.mode) { "ai" -> "AI"; "raw" -> "Raw"; else -> "Live" }} · Listening"; else -> value.message }
            if (showPreview) updatePreview(value)
        }
        if (!showPreview && preview.text.isNotEmpty()) preview.text = ""
        if (!value.busy && hasMessage && (value.finalText.isNotBlank() || value.interim.isNotBlank())) { copy.visibility = View.VISIBLE; place(copy, 128, row + 4, 48, 48) }
        if (!value.busy && hasMessage) { back.visibility = View.VISIBLE; place(back, 184, row + 4, 48, 48) }
        val mode = if (value.busy) value.mode else VoicePreferences.state.value.mode
        raw.highlighted = mode == "raw"; ai.highlighted = mode == "ai"; live.highlighted = mode == "live"
        raw.isEnabled = !value.busy; ai.isEnabled = !value.busy; live.isEnabled = !value.busy
        mic.kind = when { value.active && value.locked -> "finish"; value.phase == "processing" || value.phase == "connecting" -> "wait"; else -> "mic" }
        mic.highlighted = value.active; mic.level = value.level
        mic.alpha = if (!value.busy && DictationCoordinator.activeEditorSession == null) 0.5f else 1f
        mic.contentDescription = when { value.active -> "Finish recording"; value.phase == "processing" -> "Processing"; DictationCoordinator.activeEditorSession == null -> "Voice unavailable. Select Odicto keyboard in a text field. Double tap for settings."; else -> "Hold to speak. Double tap for options." }
        if (changed && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            listOf(ai, live, lock, settings, back).filter { it.visibility == View.VISIBLE }.forEach { it.alpha = 0f; it.scaleX = 0.85f; it.scaleY = 0.85f; it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start() }
        }
        mic.invalidate()
    }
    private fun updatePreview(value: VoiceUi) {
        preview.text = listOf(value.finalText, value.interim).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
            when (value.phase) { "connecting" -> "Getting your microphone ready…"; "recording" -> "Speak naturally"; else -> value.message }
        }
    }
    private fun touch(e: MotionEvent): Boolean {
        if ((context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ui.phase == "processing") return true
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; moving = false; held = false; target = ""
                mic.scaleX = 0.94f; mic.scaleY = 0.94f
                if (!ui.busy) postDelayed(beginHold, VoiceGestureTiming.HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = hypot(e.rawX - downX, e.rawY - downY)
                if (!held && !ui.busy && distance > slop && !keyboard) { moving = true; removeCallbacks(beginHold); drag?.invoke(e.rawX - lastX, e.rawY - lastY) }
                if (held && ui.active && !ui.locked) {
                    val next = listOf("lock" to lock).firstOrNull { (_, v) ->
                        val p = IntArray(2); v.getLocationOnScreen(p); hypot(e.rawX - p[0] - v.width / 2f, e.rawY - p[1] - v.height / 2f) < dp(32)
                    }?.first ?: ""
                    if (next.isNotEmpty() && next != target) {
                        target = next
                        if (next == "lock") { VoiceSession.lock(); feedback(HapticFeedbackConstants.LONG_PRESS) } else choose(next)
                    } else if (next.isEmpty()) target = ""
                }
                lastX = e.rawX; lastY = e.rawY; return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(beginHold); mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                when {
                    moving -> { tapSequence.reset(); dragEnd?.invoke() }
                    held -> if (!ui.locked) VoiceSession.finish(context)
                    ui.active -> VoiceSession.finish(context)
                    !ui.busy -> {
                        if (tapSequence.release(e.downTime, e.eventTime, false)) { expanded = !expanded; render(ui); feedback(HapticFeedbackConstants.CLOCK_TICK) }
                    }
                }
                held = false; return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(beginHold); mic.scaleX = 1f; mic.scaleY = 1f
                tapSequence.reset()
                if (held && ui.active && !ui.locked) VoiceSession.cancel(context)
                held = false; moving = false; return true
            }
        }
        return true
    }
    override fun onDetachedFromWindow() { removeCallbacks(beginHold); super.onDetachedFromWindow() }

    private class Icon(context: Context, var kind: String, description: String) : View(context) {
        var highlighted = false
            set(value) { field = value; isSelected = value; invalidate() }
        private var meter = 0f
        private var meterAnimation: ValueAnimator? = null
        var level = 0f
            set(value) {
                if (field == value) return
                field = value; meterAnimation?.cancel()
                if (!isShown || !isAttachedToWindow || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) { meter = value; invalidate(); return }
                meterAnimation = ValueAnimator.ofFloat(meter, value).apply { duration = 100; addUpdateListener { meter = it.animatedValue as Float; invalidate() }; start() }
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = description; isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val s = width / 56f; c.save(); c.scale(s, height / 56f)
            paint.style = Paint.Style.FILL; paint.color = if (highlighted) 0xFFC5B3FF.toInt() else 0xFF292933.toInt()
            c.drawCircle(28f, 28f, 27f, paint)
            paint.color = if (highlighted) 0xFF201832.toInt() else 0xFFF5F2FF.toInt(); paint.strokeWidth = 2f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.style = Paint.Style.STROKE
            when (kind) {
                "mic" -> { c.drawRoundRect(23f, 15f, 33f, 32f, 5f, 5f, paint); c.drawArc(19f, 21f, 37f, 37f, 0f, 180f, false, paint); c.drawLine(28f,37f,28f,42f,paint); c.drawLine(23f,42f,33f,42f,paint); if (meter > 0) { paint.alpha = (80 + meter*175).toInt(); c.drawArc(12f-meter*3,14f,44f+meter*3,42f,-45f,90f,false,paint); c.drawArc(12f-meter*3,14f,44f+meter*3,42f,135f,90f,false,paint); paint.alpha = 255 } }
                "raw" -> { paint.style=Paint.Style.FILL; paint.textSize=13f; paint.typeface=Typeface.create("sans-serif-medium",0); paint.textAlign=Paint.Align.CENTER; c.drawText("Raw",28f,33f,paint) }
                "ai" -> { paint.style=Paint.Style.FILL; paint.textSize=16f; paint.typeface=Typeface.create("sans-serif-medium",0); paint.textAlign=Paint.Align.CENTER; c.drawText("AI",28f,34f,paint) }
                "live" -> { for (i in 0..4) { val h = floatArrayOf(5f,10f,15f,8f,4f)[i]; c.drawLine(16f+i*6,28-h,16f+i*6,28+h,paint) } }
                "lock" -> { c.drawRoundRect(18f,25f,38f,41f,4f,4f,paint); c.drawArc(22f,14f,34f,34f,180f,180f,false,paint) }
                "finish" -> { paint.style=Paint.Style.FILL; c.drawRoundRect(19f,19f,37f,37f,4f,4f,paint) }
                "wait" -> { val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled(); val angle = if (animate) (android.os.SystemClock.uptimeMillis() % 1200) * 0.3f else -90f; c.drawArc(18f,18f,38f,38f,angle,250f,false,paint); if (animate && isShown) postInvalidateOnAnimation() }
                "back" -> { c.drawLine(31f,20f,23f,28f,paint); c.drawLine(23f,28f,31f,36f,paint) }
                "cancel" -> { c.drawLine(21f,21f,35f,35f,paint); c.drawLine(35f,21f,21f,35f,paint) }
                "copy" -> { c.drawRoundRect(23f,22f,37f,39f,3f,3f,paint); c.drawLine(19f,33f,19f,17f,paint); c.drawLine(19f,17f,32f,17f,paint) }
                "settings" -> { c.drawCircle(28f,28f,9f,paint); c.drawCircle(28f,28f,3f,paint); for (i in 0..7) { val a=i*Math.PI/4; c.drawLine(28f+cos(a).toFloat()*10,28f+sin(a).toFloat()*10,28f+cos(a).toFloat()*14,28f+sin(a).toFloat()*14,paint) } }
            }
            c.restore()
        }
        override fun onDetachedFromWindow() { meterAnimation?.cancel(); super.onDetachedFromWindow() }
    }
}
