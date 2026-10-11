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
import app.odicto.mobile.ime.Haptics
import app.odicto.mobile.storage.VoicePreferences
import kotlin.math.*

/** Native touch surface. No WebView, network work, or layout animation on pointer moves. */
class VoiceControlView(context: Context, private val keyboard: Boolean = false) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    var resize: ((Int, Int) -> Unit)? = null
    // Keyboard fails closed until the owning IME supplies its tap-time editor guard.
    var keyboardCopyAllowed: () -> Boolean = { false }
    var keyboardNotice: (String) -> Unit = {}
    var drag: ((Float, Float) -> Unit)? = null
    var dragEnd: (() -> Unit)? = null
    var mirrored = false
        set(value) { if (field != value) { field = value; render(ui) } }
    private val collapseTimer = Runnable { render(ui) }
    private var expanded = keyboard
    private fun openOptions() { OverlayPresentation.reopen(); render(ui) }
    private var compactKeyboard = false
    private var keyboardCellDp = 48
    private var overlayHeightDp = Int.MAX_VALUE
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
        background = round(0xF5212326.toInt(), 20f)
    }
    private val status = TextView(context).apply { setTextColor(0xFF8EDCBB.toInt()); textSize = 12f; typeface = Typeface.create("sans-serif-medium", 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
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
    private val pill = View(context).apply { background = round(0xF2212326.toInt(), 36f) }
    private val beginHold = Runnable {
        if (moving || ui.busy) return@Runnable
        // A restarted process can miss the focus event that opens the session, so resolve it on demand.
        if (DictationCoordinator.activeEditorSession == null) DictationCoordinator.refreshTarget()
        if (DictationCoordinator.activeEditorSession != null) {
            held = true; tapSequence.reset(); VoiceSession.start(context); feedback(HapticFeedbackConstants.CLOCK_TICK); render(VoiceSession.state.value)
        } else {
            // The control stays on screen but disabled outside supported fields; say why rather than
            // leaving the user with a microphone that silently ignores the press.
            if (keyboard) keyboardNotice("Voice unavailable in this field")
            else VoiceSession.notice("No text field for voice here. Some apps hide their editors from accessibility; select the Odicto keyboard to dictate in this app.")
            render(VoiceSession.state.value)
        }
    }
    init {
        if (!keyboard) OverlayPresentation.start(OverlayPresentation.recommendedTimeout(context))
        clipChildren = false; clipToPadding = false
        panel.addView(status); panel.addView(preview)
        addView(pill); addView(panel); listOf(back, settings, raw, ai, live, lock, cancel, copy, mic).forEach { it.compactSurface = keyboard; addView(it) }
        mic.setOnClickListener {
            if (keyboard && compactKeyboard && ui.busy && !ui.active) VoiceSession.cancel(context)
            else if (ui.active) VoiceSession.finish(context)
            else if (!ui.busy) VoiceSession.start(context, true)
        }
        val optionsAction = View.generateViewId()
        mic.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (!ui.busy) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(optionsAction, "Show voice options"))
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: android.os.Bundle?): Boolean {
                if (action == optionsAction && !ui.busy) {
                    if (keyboard) openSettings() else openOptions()
                    return true
                }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
        mic.setOnTouchListener { _, event -> touch(event) }
        lock.setOnClickListener { VoiceSession.lock(); feedback(HapticFeedbackConstants.LONG_PRESS) }
        raw.setOnClickListener { choose("raw") }; ai.setOnClickListener { choose("ai") }; live.setOnClickListener { choose("live") }
        back.setOnClickListener { OverlayPresentation.collapse(); render(ui); feedback(HapticFeedbackConstants.CLOCK_TICK) }
        settings.setOnClickListener { if (!ui.busy) openSettings() }
        cancel.setOnClickListener { VoiceSession.cancel(context); feedback(HapticFeedbackConstants.LONG_PRESS) }
        copy.setOnClickListener {
            if (keyboard && !keyboardCopyAllowed()) {
                keyboardNotice("Copy unavailable in this field")
                return@setOnClickListener
            }
            if (!keyboard) {
                val allowed = try {
                    DictationCoordinator.refreshTarget()
                    !DictationCoordinator.protectedField.value && DictationCoordinator.activeEditorSession != null
                } catch (_: Exception) { false }
                if (!allowed) {
                    Toast.makeText(context, "Copy unavailable in this field", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            val text = listOf(ui.finalText, ui.interim).filter { it.isNotBlank() }.joinToString(" ")
            if (text.isNotBlank()) {
                if (keyboard) {
                    try {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Odicto", text))
                        keyboardNotice("Copied")
                    } catch (_: Exception) { keyboardNotice("Could not copy result") }
                } else {
                    val message = try {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Odicto", text))
                        "Copied"
                    } catch (_: Exception) { "Could not copy result" }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                feedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        render(ui)
    }
    private fun round(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), 0x24FFFFFF) }
    private fun feedback(kind: Int) {
        if (!isShown || !isAttachedToWindow) return
        val emphasis = when (kind) {
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.CONFIRM,
            HapticFeedbackConstants.REJECT -> Haptics.EMPHASIS_LONG
            else -> Haptics.EMPHASIS_NONE
        }
        Haptics.tap(context, VoicePreferences.levelOf(VoicePreferences.state.value), emphasis)
    }
    private fun choose(mode: String) { VoiceSession.mode(context, mode); feedback(HapticFeedbackConstants.CLOCK_TICK) }
    private fun openSettings() {
        feedback(HapticFeedbackConstants.CLOCK_TICK)
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("voice_settings", true))
    }
    private fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
        val left = dp(if (mirrored) surfaceWidth - x - w else x)
        val old = view.layoutParams as? LayoutParams
        if (keyboard && old?.width == dp(w) && old.height == dp(h) && old.leftMargin == left && old.topMargin == dp(y)) return
        view.layoutParams = LayoutParams(dp(w), dp(h)).apply { leftMargin = left; topMargin = dp(y) }
    }
    fun setKeyboardCompact(compact: Boolean) {
        setKeyboardCellWidth(if (compact) 40 else 48)
    }

    fun setKeyboardCellWidth(widthDp: Int) {
        if (!keyboard || keyboardCellDp == widthDp) return
        keyboardCellDp = widthDp
        compactKeyboard = widthDp < 48
        expanded = !compactKeyboard
        lastLayout = ""
        render(ui)
    }

    fun setOverlayAvailableHeight(heightPx: Int) {
        if (keyboard) return
        val heightDp = (heightPx / density).toInt()
        if (overlayHeightDp == heightDp) return
        overlayHeightDp = heightDp
        render(ui)
    }

    fun render(value: VoiceUi) {
        val previous = ui
        ui = value
        if (keyboard) { renderKeyboard(value, previous); return }
        OverlayPresentation.observe(value)
        val terminalVisible = OverlayPresentation.visible()
        expanded = OverlayPresentation.options
        removeCallbacks(collapseTimer)
        if (isAttachedToWindow) OverlayPresentation.remaining()?.let { postDelayed(collapseTimer, it.coerceAtLeast(1)) }
        val showPreview = VoicePreferences.state.value.showTextPreview
        val explained = value.phase == "error" || value.phase == "notice"
        val retained = value.finalText.isNotBlank() || value.interim.isNotBlank()
        val hasMessage = (value.busy && value.mode != "live") || terminalVisible || (expanded && (retained || explained))
        val compactStatus = hasMessage && !showPreview && !explained && !expanded
        // An explanation must stay readable even with the transcript preview turned off, or the user only
        // sees a red X with no reason attached.
        val showStatus = showPreview || explained
        val shortWindow = resources.configuration.screenHeightDp < 480
        val hasPanelSpace = overlayHeightDp >= if (shortWindow) 164 else 184
        val signature = "${value.phase}:${value.locked}:${value.mode}:$expanded:$hasMessage:$retained:$mirrored:$compactKeyboard:${DictationCoordinator.activeEditorSession}:${VoicePreferences.state.value.mode}:$showPreview:$shortWindow:$hasPanelSpace"
        if (lastLayout == signature) {
            if (!compactKeyboard && hasPanelSpace && showPreview && hasMessage && (value.finalText != previous.finalText || value.interim != previous.interim)) updatePreview(value)
            if (!value.active && value.message != previous.message) status.text = value.message
            mic.level = value.level; return
        }
        lastLayout = signature
        if (value.phase != previous.phase) {
            when (value.phase) {
                "recording" -> feedback(HapticFeedbackConstants.LONG_PRESS)
                "done" -> if (value.inserted) feedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                "error" -> feedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
                "notice" -> feedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        val full = expanded || hasMessage || keyboard || value.busy
        val newWidth = if (keyboard && compactKeyboard) 72 else if (full) 304 else 72
        val newHeight = if (!hasPanelSpace) 72 else if (expanded && hasMessage) { if (shortWindow) 164 else 184 } else if (!hasMessage || !showStatus || compactStatus) 72 else if (shortWindow) 164 else 184
        val changed = surfaceWidth != newWidth || surfaceHeight != newHeight
        surfaceWidth = newWidth; surfaceHeight = newHeight
        if (changed) resize?.invoke(dp(surfaceWidth), dp(surfaceHeight))
        val row = surfaceHeight - 64
        val mx = surfaceWidth - 64
        listOf(pill, panel, back, settings, raw, ai, live, lock, cancel, copy).forEach { it.visibility = View.GONE }
        place(mic, mx, row, 56, 56)
        if (expanded && !value.busy) {
            pill.visibility = View.VISIBLE; place(pill, 4, row, 296, 56)
            listOf(back, settings, raw, ai, live).forEach { it.visibility = View.VISIBLE }
            place(back, 8, row + 8, 40, 40); place(settings, 52, row + 8, 40, 40)
            place(raw, 96, row + 8, 40, 40); place(ai, 140, row + 8, 40, 40); place(live, 184, row + 8, 40, 40)
            settings.isEnabled = !value.busy
        }
        if (value.active && !compactKeyboard) {
            pill.visibility = View.GONE; back.visibility = View.GONE; settings.visibility = View.GONE
            raw.visibility = View.GONE; ai.visibility = View.GONE; live.visibility = View.GONE
            lock.visibility = View.VISIBLE
            if (value.locked) lock.visibility = View.GONE
            place(lock, 184, row + 4, 48, 48)
        }
        if (value.busy && !compactKeyboard) {
            cancel.visibility = View.VISIBLE; place(cancel, 128, row + 4, 48, 48)
        }
        if (hasMessage && !compactKeyboard && hasPanelSpace) {
            panel.visibility = if (showStatus) View.VISIBLE else View.GONE
            // Without a transcript, status shares the controls row instead of reserving a card.
            panel.background = if (compactStatus) null else round(0xF5212326.toInt(), 20f)
            panel.setPadding(dp(if (compactStatus) 4 else 16), dp(if (compactStatus) 0 else 12), dp(if (compactStatus) 4 else 16), dp(if (compactStatus) 0 else 12))
            val panelWidth = if (expanded && retained) 224 else if (compactStatus) 112 else 288
            val panelHeight = if (compactStatus) 48 else surfaceHeight - 76
            val panelTop = if (compactStatus) row + 4 else 8
            place(panel, 8, panelTop, panelWidth, panelHeight)
            panel.gravity = if (compactStatus) Gravity.CENTER_VERTICAL else Gravity.TOP
            preview.visibility = if (showPreview) View.VISIBLE else View.GONE
            preview.maxLines = if (shortWindow) 2 else 3
            status.text = when { value.locked -> if (compactStatus) "Hands-free" else "Hands-free · Tap mic to finish"; value.phase == "recording" -> "${when (value.mode) { "ai" -> "AI"; "raw" -> "Raw"; else -> "Live" }} · Listening"; else -> value.message }
            if (showStatus) {
                if (showPreview) updatePreview(value)
                status.visibility = View.VISIBLE
            } else {
                status.visibility = View.GONE
            }
        }
        if ((!showPreview || compactKeyboard || !hasPanelSpace) && preview.text.isNotEmpty()) preview.text = ""
        if (!value.busy && hasMessage && retained) {
            copy.visibility = View.VISIBLE
            if (expanded && !hasPanelSpace) {
                settings.visibility = View.GONE
                place(copy, 52, row + 8, 40, 40)
            } else place(copy, if (expanded) 240 else 128, if (expanded) row - 56 else row + 4, 48, 48)
        }
        if (!value.busy && hasMessage && !expanded) { back.visibility = View.VISIBLE; place(back, 184, row + 4, 48, 48) }
        val mode = if (value.busy) value.mode else VoicePreferences.state.value.mode
        raw.highlighted = mode == "raw"; ai.highlighted = mode == "ai"; live.highlighted = mode == "live"
        raw.isEnabled = !value.busy; ai.isEnabled = !value.busy; live.isEnabled = !value.busy
        mic.kind = when {
            hasMessage && !showPreview && value.phase == "done" && value.inserted -> "done"
            hasMessage && !showPreview && explained -> "error"
            value.active && value.locked -> "finish"
            value.phase == "processing" || value.phase == "connecting" -> "wait"
            else -> "mic"
        }
        mic.highlighted = value.active; mic.level = value.level
        mic.alpha = if (!value.busy && DictationCoordinator.activeEditorSession == null) 0.5f else 1f
        mic.contentDescription = when {
            keyboard && compactKeyboard && value.busy && !value.active -> "Cancel voice processing"
            value.active -> "Finish recording"
            value.phase == "processing" -> "Processing"
            DictationCoordinator.activeEditorSession == null -> "Voice unavailable. Focus a supported text field, or enable voice typing in settings. Double tap for settings."
            keyboard && compactKeyboard -> "Hold to speak. Double tap to open voice settings."
            else -> "Hold to speak. Double tap for options."
        }
        if (changed && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            listOf(ai, live, lock, settings, back).filter { it.visibility == View.VISIBLE }.forEach { it.alpha = 0f; it.scaleX = 0.85f; it.scaleY = 0.85f; it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start() }
        }
        mic.invalidate()
    }
    /**
     * The keyboard row is six fixed cells: settings or cancel, Raw, AI, Live, copy or lock, mic.
     * When the keyboard shows suggestions or a status line, that text takes the place of the tool
     * cells it covers (idle: cells 0-4; during voice: cells 1-3) instead of a second row, so the
     * cancel, lock and mic controls never move under a finger.
     */
    fun setKeyboardCovered(covered: Boolean) {
        if (!keyboard || keyboardCovered == covered) return
        keyboardCovered = covered
        render(ui)
    }

    val keyboardCellPx: Int get() = dp(keyboardCellDp)
    private var keyboardCovered = false

    private fun renderKeyboard(value: VoiceUi, previous: VoiceUi) {
        val cell = keyboardCellDp
        val extra = value.busy || value.finalText.isNotBlank() || value.interim.isNotBlank()
        val width = cell * 6
        val changed = surfaceWidth != width || surfaceHeight != 48
        val signature = "${value.phase}:${value.locked}:${value.mode}:$extra:$compactKeyboard:$keyboardCovered:${VoicePreferences.state.value.mode}:${DictationCoordinator.activeEditorSession}"
        if (!changed && lastLayout == signature) { mic.level = value.level; return }
        lastLayout = signature
        surfaceWidth = width; surfaceHeight = 48
        if (changed) resize?.invoke(requiredWidth, requiredHeight)
        listOf(pill, panel, back, settings, raw, ai, live, lock, cancel, copy).forEach { it.visibility = View.GONE }
        preview.text = ""
        settings.visibility = if (value.busy || keyboardCovered) View.GONE else View.VISIBLE
        settings.isEnabled = !value.busy
        place(settings, 0, 0, cell, 48)
        val mode = if (value.busy) value.mode else VoicePreferences.state.value.mode
        val modes = if (keyboardCovered) View.GONE else View.VISIBLE
        pill.visibility = modes
        pill.background = round(0xFF282B2E.toInt(), 16f)
        place(pill, cell + 2, 8, cell * 3 - 4, 32)
        listOf("raw" to raw, "ai" to ai, "live" to live).forEachIndexed { index, (name, button) ->
            button.visibility = modes
            button.kind = name
            button.isEnabled = !value.busy
            button.highlighted = mode == name
            button.alpha = if (value.busy) 0.45f else 1f
            place(button, cell * (index + 1), 0, cell, 48)
        }
        if (value.active && !value.locked) { lock.visibility = View.VISIBLE; place(lock, cell * 4, 0, cell, 48) }
        if (value.busy) { cancel.visibility = View.VISIBLE; place(cancel, 0, 0, cell, 48) }
        // Copy recovery is never covered: the keyboard keeps cell 4 out of the slot while it shows.
        else if (extra) {
            copy.visibility = View.VISIBLE; copy.contentDescription = "Copy completed result"; place(copy, cell * 4, 0, cell, 48)
        }
        place(mic, width - cell, 0, cell, 48)
        mic.kind = when { value.active -> "finish"; value.busy -> "wait"; else -> "mic" }
        mic.highlighted = value.active; mic.level = value.level
        mic.contentDescription = when { value.active -> "Finish recording"; value.busy -> "Processing voice"; else -> "Hold to speak. Double tap for voice settings." }
        mic.alpha = if (!value.busy && DictationCoordinator.activeEditorSession == null) 0.5f else 1f
        if (previous.phase != value.phase) {
            when (value.phase) {
                "recording" -> feedback(HapticFeedbackConstants.LONG_PRESS)
                "done" -> if (value.inserted) feedback(HapticFeedbackConstants.CONFIRM)
                "error" -> feedback(HapticFeedbackConstants.REJECT)
            }
        }
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
                    keyboard && compactKeyboard && ui.busy -> VoiceSession.cancel(context)
                    !ui.busy -> {
                        if (tapSequence.release(e.downTime, e.eventTime, false)) {
                            if (keyboard) openSettings()
                            else {
                                if (expanded) OverlayPresentation.collapse() else OverlayPresentation.reopen()
                                render(ui); feedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
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
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!keyboard) render(ui) }
    override fun onDetachedFromWindow() { removeCallbacks(collapseTimer); removeCallbacks(beginHold); super.onDetachedFromWindow() }

    private class Icon(context: Context, kind: String, description: String) : View(context) {
        var compactSurface = false
        var kind = kind
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }
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
        private val glyphs = HashMap<Int, android.graphics.drawable.Drawable?>()
        private fun glyph(id: Int) = glyphs.getOrPut(id) { context.getDrawable(id)?.mutate() }
        private val density = resources.displayMetrics.density

        /**
         * The jade action fill (#2A745D) is 3.15:1 against the graphite canvas; a thin pale-jade ring
         * gives the disc a crisper edge without brightening the fill (EduDojo keeps pale jade off
         * large fills). The off-white glyph on the fill is about 5:1.
         */
        private fun ring(c: Canvas, cx: Float, cy: Float, radius: Float) {
            val style = paint.style; val color = paint.color; val width = paint.strokeWidth
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = HALO; paint.alpha = 150
            c.drawCircle(cx, cy, radius - 0.75f * density, paint)
            paint.style = style; paint.color = color; paint.strokeWidth = width
        }

        /** Keyboard row only: quiet line icons, a text segmented control and one accent mic. */
        private fun drawKeyboard(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            fun icon(id: Int, sizeDp: Float, color: Int) {
                val half = (sizeDp * density / 2f).toInt()
                glyph(id)?.apply { setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt()); setTint(color); draw(c) }
            }
            paint.reset(); paint.isAntiAlias = true
            when (kind) {
                "raw", "ai", "live" -> {
                    if (highlighted) {
                        paint.color = ACCENT
                        val inset = 3f * density
                        c.drawRoundRect(inset, 11f * density, width - inset, height - 11f * density, 13f * density, 13f * density, paint)
                    }
                    paint.color = if (highlighted) ON_ACCENT else MUTED
                    paint.textSize = 13f * resources.displayMetrics.scaledDensity.coerceAtMost(density * 1.15f)
                    paint.typeface = MEDIUM
                    paint.textAlign = Paint.Align.CENTER
                    val label = when (kind) { "raw" -> "Raw"; "ai" -> "AI"; else -> "Live" }
                    c.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
                }
                "mic", "finish", "wait" -> {
                    val radius = 17f * density
                    if (kind == "finish" && meter > 0f) {
                        paint.color = HALO; paint.alpha = (60 + meter * 120).toInt().coerceAtMost(255)
                        c.drawCircle(cx, cy, radius + (2f + meter * 4f) * density, paint)
                        paint.alpha = 255
                    }
                    paint.color = if (kind == "wait") SURFACE else ACCENT
                    c.drawCircle(cx, cy, radius, paint)
                    if (kind != "wait") ring(c, cx, cy, radius)
                    when (kind) {
                        "mic" -> icon(app.odicto.mobile.R.drawable.odicto_ic_mic, 20f, ON_ACCENT)
                        "finish" -> icon(app.odicto.mobile.R.drawable.odicto_ic_stop, 20f, ON_ACCENT)
                        else -> {
                            val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
                            val angle = if (animate) (android.os.SystemClock.uptimeMillis() % 1000) * 0.36f else -90f
                            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density; paint.strokeCap = Paint.Cap.ROUND; paint.color = HALO
                            val r = 8f * density
                            c.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 270f, false, paint)
                            if (animate && isShown) postInvalidateOnAnimation()
                        }
                    }
                }
                "settings" -> icon(app.odicto.mobile.R.drawable.odicto_ic_settings, 20f, MUTED)
                "copy" -> icon(app.odicto.mobile.R.drawable.odicto_ic_copy, 20f, MUTED)
                "lock" -> icon(app.odicto.mobile.R.drawable.odicto_ic_lock, 20f, ICON)
                "cancel" -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, 20f, ICON)
                "done" -> icon(app.odicto.mobile.R.drawable.odicto_ic_check, 20f, HALO)
                "error" -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, 20f, DANGER)
                else -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, 20f, MUTED)
            }
        }

        /**
         * Floating bubble: the keyboard's icons and palette on a filled disc, since the bubble sits
         * over any app's content. Sizes, positions and touch handling are unchanged.
         */
        private fun drawOverlay(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val radius = minOf(width, height) / 2f - density
            fun icon(id: Int, color: Int) {
                val half = (minOf(width, height) * 0.21f).toInt()
                glyph(id)?.apply { setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt()); setTint(color); draw(c) }
            }
            paint.reset(); paint.isAntiAlias = true
            val (fill, ink) = when {
                kind == "done" -> SUCCESS to INK_DARK
                kind == "error" -> DANGER to INK_DARK
                kind == "mic" || kind == "finish" || highlighted -> ACCENT to ON_ACCENT
                else -> BUBBLE to ICON
            }
            val listening = (kind == "finish" || kind == "mic") && meter > 0f
            if (listening) {
                paint.color = HALO; paint.alpha = (60 + meter * 120).toInt().coerceAtMost(255)
                c.drawCircle(cx, cy, radius, paint)
                paint.alpha = 255
            }
            paint.color = fill
            // While listening the disc breathes with the input level inside a soft halo.
            val discRadius = if (listening) radius - (1f - meter) * 3f * density - 2f * density else radius
            c.drawCircle(cx, cy, discRadius, paint)
            if (fill == ACCENT) ring(c, cx, cy, discRadius)
            if (fill == BUBBLE) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = HAIRLINE
                c.drawCircle(cx, cy, radius - density / 2f, paint)
                paint.style = Paint.Style.FILL
            }
            when (kind) {
                "mic" -> icon(app.odicto.mobile.R.drawable.odicto_ic_mic, ink)
                "finish" -> icon(app.odicto.mobile.R.drawable.odicto_ic_stop, ink)
                "lock" -> icon(app.odicto.mobile.R.drawable.odicto_ic_lock, ink)
                "cancel", "error" -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, ink)
                "copy" -> icon(app.odicto.mobile.R.drawable.odicto_ic_copy, ink)
                "settings" -> icon(app.odicto.mobile.R.drawable.odicto_ic_settings, ink)
                "done" -> icon(app.odicto.mobile.R.drawable.odicto_ic_check, ink)
                "raw", "ai", "live" -> {
                    paint.color = ink; paint.typeface = MEDIUM; paint.textAlign = Paint.Align.CENTER
                    paint.textSize = minOf(width, height) * 0.3f
                    val label = when (kind) { "raw" -> "Raw"; "ai" -> "AI"; else -> "Live" }
                    c.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
                }
                "back" -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.75f * density
                    paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = ink
                    val r = minOf(width, height) * 0.12f
                    c.drawLines(floatArrayOf(cx + r * 0.5f, cy - r, cx - r * 0.5f, cy, cx - r * 0.5f, cy, cx + r * 0.5f, cy + r), paint)
                }
                "wait" -> {
                    val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
                    val angle = if (animate) (android.os.SystemClock.uptimeMillis() % 1000) * 0.36f else -90f
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density; paint.strokeCap = Paint.Cap.ROUND; paint.color = HALO
                    val r = minOf(width, height) * 0.2f
                    c.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 270f, false, paint)
                    if (animate && isShown) postInvalidateOnAnimation()
                }
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (compactSurface) { drawKeyboard(c); return }
            drawOverlay(c)
        }
        override fun onDetachedFromWindow() { meterAnimation?.cancel(); super.onDetachedFromWindow() }

        private companion object {
            const val ACCENT = 0xFF2A745D.toInt()
            /** Pale jade: level halo and spinner on the dark surface. */
            const val HALO = 0xFF8EDCBB.toInt()
            const val ON_ACCENT = 0xFFF2F2EC.toInt()
            const val INK_DARK = 0xFF17191C.toInt()
            const val SURFACE = 0xFF282B2E.toInt()
            const val ICON = 0xFFE7E5DF.toInt()
            const val MUTED = 0xFFB2B5B5.toInt()
            const val BUBBLE = 0xF2212326.toInt()
            const val HAIRLINE = 0x1FFFFFFF
            /** Done is the pale jade disc, so it never looks like the idle (jade) mic. */
            const val SUCCESS = 0xFF8EDCBB.toInt()
            const val DANGER = 0xFFF2A39B.toInt()
            /** Created once: onDraw runs every frame of the recording meter. */
            val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
}
