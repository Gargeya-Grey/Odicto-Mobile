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
import app.odicto.mobile.ime.PressSurface
import androidx.core.graphics.ColorUtils
import app.odicto.mobile.R
import app.odicto.mobile.storage.VoicePreferences
import kotlin.math.*

/** Native touch surface. No WebView, network work, or layout animation on pointer moves. */
class VoiceControlView(context: Context, private val keyboard: Boolean = false) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    // From this view's own context, which is built for the day or night palette; a theme change rebuilds the view.
    private val cardFill = context.getColor(R.color.odicto_panel_card)
    private val cardStroke = context.getColor(R.color.odicto_card_stroke)
    private val raisedFill = context.getColor(R.color.odicto_surface_raised)
    private val jadeFill = context.getColor(R.color.odicto_key_accent)
    private val accentInk = context.getColor(R.color.odicto_accent)
    private val strongInk = context.getColor(R.color.odicto_text_strong)
    /** (window width px, window height px, microphone offset from the window's left edge px). */
    var resize: ((Int, Int, Int) -> Unit)? = null
    // Keyboard fails closed until the owning IME supplies its tap-time editor guard.
    var keyboardCopyAllowed: () -> Boolean = { false }
    var keyboardNotice: (String) -> Unit = {}
    var dragStart: (() -> Unit)? = null
    /** Total finger travel in px since the touch went down, not a step: the owner places the microphone from it directly. */
    var drag: ((Float, Float) -> Unit)? = null
    var dragEnd: (() -> Unit)? = null
    // Where the microphone is on the screen, so open controls can choose a side that fits. Until the owner
    // says, assume a roomy screen with the microphone at the right edge.
    private var micLeftDp = 347f
    private var screenWidthDp = 411f
    private var preferRight = false
    private var micOffsetDp = OverlayGeometry.INSET_DP
    private var placementMatters = false
    private class Pending(val view: View, val x: Int, val y: Int, val w: Int, val h: Int)
    private val pending = ArrayList<Pending>()
    /** The microphone's left edge inside the window, in px. The owner positions the window so this lands on the anchor. */
    val micOffsetPx get() = dp(micOffsetDp)

    /** Called by the owner when the microphone or the screen changes size or place. */
    fun setPlacement(micLeftPx: Int, screenWidthPx: Int) {
        val left = micLeftPx / density
        val width = screenWidthPx / density
        if (kotlin.math.abs(left - micLeftDp) < 0.05f && kotlin.math.abs(width - screenWidthDp) < 0.05f) return
        micLeftDp = left; screenWidthDp = width
        // A bare microphone does not depend on either, and is dragged continuously.
        if (!keyboard && placementMatters) { lastLayout = ""; render(ui) }
    }    private val collapseTimer = Runnable { render(ui) }
    private var expanded = keyboard
    private fun openOptions() { OverlayPresentation.reopen(); render(ui) }
    private var compactKeyboard = false
    private var keyboardCellDp = 48
    private var overlayHeightDp = Int.MAX_VALUE
    private var ui = VoiceSession.state.value
    private var held = false
    private var moving = false
    private var downX = 0f; private var downY = 0f
    private val tapSequence = VoiceGestureTiming()
    private var target = ""
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var surfaceWidth = 72; private var surfaceHeight = 72
    val requiredWidth get() = dp(surfaceWidth)
    val requiredHeight get() = dp(surfaceHeight)
    private val gestureRect = android.graphics.Rect()

    /**
     * Asks the system not to read a swipe that starts on the microphone as Back, so it can sit right at the
     * screen edge. Only the microphone is excluded, and only where gesture navigation exists (Android 10+);
     * the owner keeps it clear of the strips if the system declines.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (keyboard || micDetached || Build.VERSION.SDK_INT < 29) return
        if (gestureRect.left == mic.left && gestureRect.top == mic.top && gestureRect.right == mic.right && gestureRect.bottom == mic.bottom) return
        gestureRect.set(mic.left, mic.top, mic.right, mic.bottom)
        systemGestureExclusionRects = listOf(android.graphics.Rect(gestureRect))
    }
    private var lastLayout = ""
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = round(cardFill, 20f)
    }
    // The card has a fixed height, so its text follows the user's font size only up to 1.3x: beyond that it would
    // clip. The keyboard header's status line has no such limit and keeps the full scale.
    private val cardScale = resources.configuration.fontScale.coerceIn(1f, 1.3f)
    private fun cardText(sp: Float) = sp * density * cardScale
    private val status = TextView(context).apply { setTextColor(accentInk); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardText(12f)); typeface = Typeface.create("sans-serif-medium", 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val preview = TextView(context).apply { setTextColor(strongInk); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardText(15f)); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) }
    private val mic = Icon(context, "mic", "Hold to speak. Double tap for options.")
    private var micDetached = false
    private var micFade: ValueAnimator? = null
    private var lastOptionsShown = false
    private var resizeJob: Runnable? = null
    private var windowW = 0
    private var windowH = 0

    /**
     * Takes the microphone out of this view so the owner can give it a window of its own that never changes size. A window that
     * grows toward the left also moves its left edge, and for a frame or two the screen shows the old picture at the new place: an
     * icon in that window flashes or jumps. Alone in a fixed window it cannot. This view is then only the drawer and its card, which
     * start out empty, so a late frame shows nothing wrong. The keyboard header keeps its microphone inside.
     */
    fun detachMic(): View {
        if (!micDetached && !keyboard) { removeView(mic); micDetached = true }
        return mic
    }
    private val lock = Icon(context, "lock", "Lock recording hands-free")
    private val raw = Icon(context, "raw", "Raw transcription with Groq")
    private val ai = Icon(context, "ai", "AI answer")
    private val live = Icon(context, "live", "Live transcription")
    private val settings = Icon(context, "settings", "Open Odicto settings")
    private val back = Icon(context, "back", "Collapse voice controls")
    private val cancel = Icon(context, "cancel", "Cancel recording")
    private val copy = Icon(context, "copy", "Copy available text")
    private val pill = PillView(context, cardFill, cardStroke, dp(1).toFloat())
    private var pillReveal: ValueAnimator? = null
    /** Keyboard header only: the jade chip behind the active mode. It glides to a new mode instead of jumping. */
    private val indicator = View(context).apply { background = round(jadeFill, 13f); visibility = GONE }
    private var indicatorIndex = -1
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
            else VoiceSession.notice("Voice types through the Odicto keyboard. Tap a text field and switch to it to dictate here.")
            render(VoiceSession.state.value)
        }
    }
    init {
        if (!keyboard) OverlayPresentation.start(OverlayPresentation.recommendedTimeout(context))
        clipChildren = false; clipToPadding = false
        // Children are placed by absolute left margins; a right-to-left locale must not mirror them.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        panel.addView(status); panel.addView(preview)
        addView(pill); addView(panel); addView(indicator)
        listOf(back, settings, raw, ai, live, lock, cancel, copy, mic).forEach { it.compactSurface = keyboard; addView(it) }
        // Every control but the microphone (which animates itself while held) presses in a little and springs back.
        listOf(back, settings, raw, ai, live, lock, cancel, copy).forEach { PressSurface.pressIn(it) }
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
    private fun round(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), cardStroke) }
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
        val left = dp(x)
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
        val panelHeightWanted = if (shortWindow) 164 else 184
        val optionsShown = expanded && !value.busy
        // How far left of the microphone the pieces that sit beside it reach, in the left-side frame.
        var reach = 0
        if (optionsShown) reach = OverlayGeometry.OPTIONS_REACH_DP
        if (value.busy) reach = maxOf(reach, 112)
        if (!value.busy && hasMessage) reach = maxOf(reach, if (retained && !expanded) 112 else 56)
        if (compactStatus) reach = maxOf(reach, 232)
        val fitSide = if (reach == 0) null else OverlayGeometry.chooseSide(micLeftDp, screenWidthDp, reach, preferRight)
        // Controls that fit on neither side stack above the microphone, so the microphone itself never moves.
        val stacked = optionsShown && fitSide == null &&
            overlayHeightDp >= OverlayGeometry.MIC_DP + 16 + OverlayGeometry.STACKED_ROW_DP
        val side = when {
            reach == 0 -> if (preferRight) OverlayGeometry.Side.RIGHT else OverlayGeometry.Side.LEFT
            fitSide != null -> fitSide
            stacked -> OverlayGeometry.Side.ABOVE
            else -> OverlayGeometry.roomierSide(micLeftDp, screenWidthDp)
        }
        if (reach > 0 && side != OverlayGeometry.Side.ABOVE) preferRight = side == OverlayGeometry.Side.RIGHT
        val extra = if (side == OverlayGeometry.Side.ABOVE) OverlayGeometry.STACKED_ROW_DP else 0
        val hasPanelSpace = overlayHeightDp >= panelHeightWanted + extra
        val placementKey = if (expanded || hasMessage || value.busy) "${micLeftDp.toInt()}/${screenWidthDp.toInt()}" else "-"
        placementMatters = placementKey != "-"
        val signature = "${value.phase}:${value.locked}:${value.mode}:$expanded:$hasMessage:$retained:$side:$compactKeyboard:${DictationCoordinator.activeEditorSession}:${VoicePreferences.state.value.mode}:$showPreview:$shortWindow:$hasPanelSpace:$placementKey"
        if (lastLayout == signature) {
            if (!compactKeyboard && hasPanelSpace && showPreview && hasMessage && (value.finalText != previous.finalText || value.interim != previous.interim)) updatePreview(value)
            if (!value.active && value.message != previous.message) {
                status.text = statusLine(value, explained, showPreview)
                if (!compactKeyboard && hasPanelSpace && showPreview && hasMessage) updatePreview(value)
            }
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
        val baseHeight = if (!hasPanelSpace) 72 else if (expanded && hasMessage) panelHeightWanted else if (!hasMessage || !showStatus || compactStatus) 72 else panelHeightWanted
        val newHeight = baseHeight + extra
        val row = newHeight - 64
        val pieces = listOf(pill, panel, back, settings, raw, ai, live, lock, cancel, copy)
        val wasShown = pieces.filter { it.visibility == View.VISIBLE }.toSet()
        pieces.forEach { it.visibility = View.GONE }
        pending.clear()
        // Placed in the left-side frame (the microphone at 0), then mirrored when the controls open to the right.
        fun beside(view: View, x: Int, y: Int, w: Int, h: Int) {
            pending += Pending(view, if (side == OverlayGeometry.Side.RIGHT) OverlayGeometry.mirror(x, w) else x, y, w, h)
        }
        fun fixed(view: View, x: Int, y: Int, w: Int, h: Int) { pending += Pending(view, x, y, w, h) }
        val copyInRow = stacked && !value.busy && hasMessage && retained
        // Even when the microphone lives in its own window its slot still counts: it is what the window is sized around.
        fixed(mic, 0, row, OverlayGeometry.MIC_DP, 56)
        if (optionsShown) {
            val controls = listOf(back, settings, raw, ai, live)
            pill.visibility = View.VISIBLE; controls.forEach { it.visibility = View.VISIBLE }
            if (stacked) {
                val count = if (copyInRow) 6 else 5
                val pitch = OverlayGeometry.stackedPitch(count, screenWidthDp)
                val width = OverlayGeometry.stackedRowWidth(count, pitch)
                val left = OverlayGeometry.slideOnScreen(OverlayGeometry.Side.ABOVE, micLeftDp, screenWidthDp, width)
                val band = row - OverlayGeometry.STACKED_ROW_DP
                fixed(pill, left, band, width, 56)
                controls.forEachIndexed { i, button -> fixed(button, left + 8 + pitch * i, band + 8, 40, 40) }
            } else {
                // The pill ends exactly at the microphone's far edge, concentric with it; the five controls sit with equal
                // space before the first and between the last and the disc.
                beside(pill, -OverlayGeometry.OPTIONS_REACH_DP, row, OverlayGeometry.OPTIONS_REACH_DP + OverlayGeometry.MIC_DP, OverlayGeometry.OPTIONS_HEIGHT_DP)
                val first = -OverlayGeometry.OPTIONS_REACH_DP + (OverlayGeometry.OPTIONS_REACH_DP - (4 * OverlayGeometry.OPTION_PITCH_DP + 40)) / 2
                controls.forEachIndexed { i, button -> beside(button, first + i * OverlayGeometry.OPTION_PITCH_DP, row + 8, 40, 40) }
            }
            settings.isEnabled = !value.busy
        }
        if (value.active && !compactKeyboard) {
            pill.visibility = View.GONE; back.visibility = View.GONE; settings.visibility = View.GONE
            raw.visibility = View.GONE; ai.visibility = View.GONE; live.visibility = View.GONE
            lock.visibility = View.VISIBLE
            if (value.locked) lock.visibility = View.GONE
            beside(lock, -56, row + 4, 48, 48)
        }
        if (value.busy && !compactKeyboard) {
            cancel.visibility = View.VISIBLE; beside(cancel, -112, row + 4, 48, 48)
        }
        if (hasMessage && !compactKeyboard && hasPanelSpace) {
            panel.visibility = if (showStatus) View.VISIBLE else View.GONE
            // Without a transcript, status shares the controls row instead of reserving a card.
            panel.background = if (compactStatus) null else round(cardFill, 20f)
            panel.setPadding(dp(if (compactStatus) 4 else 16), dp(if (compactStatus) 0 else 12), dp(if (compactStatus) 4 else 16), dp(if (compactStatus) 0 else 12))
            if (compactStatus) {
                // Beside the controls, shrinking to the room that side has instead of pushing the microphone.
                val roomDp = if (side == OverlayGeometry.Side.RIGHT) screenWidthDp - OverlayGeometry.SCREEN_MARGIN_DP - micLeftDp - 176
                    else micLeftDp - OverlayGeometry.SCREEN_MARGIN_DP - 120
                val width = minOf(112, roomDp.toInt())
                if (width >= 40) beside(panel, -120 - width, row + 4, width, 48) else panel.visibility = View.GONE
            } else {
                val width = OverlayGeometry.cardWidth(if (expanded && retained) 224 else OverlayGeometry.CARD_WIDTH_DP, screenWidthDp)
                fixed(panel, OverlayGeometry.slideOnScreen(side, micLeftDp, screenWidthDp, width), 8, width, newHeight - 76 - extra)
            }
            panel.gravity = if (compactStatus) Gravity.CENTER_VERTICAL else Gravity.TOP
            preview.visibility = if (showPreview) View.VISIBLE else View.GONE
            preview.maxLines = if (shortWindow) 2 else 3
            status.text = when { value.locked -> if (compactStatus) "Hands-free" else "Hands-free · Tap mic to finish"; value.phase == "recording" -> "${when (value.mode) { "ai" -> "AI"; "raw" -> "Raw"; else -> "Live" }} · Listening"; else -> statusLine(value, explained, showPreview) }
            if (showStatus) {
                if (showPreview) updatePreview(value)
                status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            } else {
                status.visibility = View.GONE
            }
        }
        if ((!showPreview || compactKeyboard || !hasPanelSpace) && preview.text.isNotEmpty()) preview.text = ""
        if (!value.busy && hasMessage && retained) {
            copy.visibility = View.VISIBLE
            when {
                copyInRow -> {
                    val pitch = OverlayGeometry.stackedPitch(6, screenWidthDp)
                    val left = OverlayGeometry.slideOnScreen(OverlayGeometry.Side.ABOVE, micLeftDp, screenWidthDp, OverlayGeometry.stackedRowWidth(6, pitch))
                    fixed(copy, left + 8 + pitch * 5, row - OverlayGeometry.STACKED_ROW_DP + 8, 40, 40)
                }
                expanded && !hasPanelSpace -> { settings.visibility = View.GONE; beside(copy, -188, row + 8, 40, 40) }
                expanded -> beside(copy, 0, row - 56, 48, 48)
                else -> beside(copy, -112, row + 4, 48, 48)
            }
        }
        if (!value.busy && hasMessage && !expanded) { back.visibility = View.VISIBLE; beside(back, -56, row + 4, 48, 48) }
        // The window is the box around everything placed, so the microphone's own position never depends on
        // what is open beside it.
        val box = OverlayGeometry.bounds(pending.minOf { it.x }, pending.maxOf { it.x + it.w })
        val changed = surfaceWidth != box.width || surfaceHeight != newHeight || micOffsetDp != box.micOffset
        surfaceWidth = box.width; surfaceHeight = newHeight; micOffsetDp = box.micOffset
        // The window grows around the microphone but the microphone must not move, and the system applies the window's new frame and
        // this layout in separate frames. Every piece is therefore placed from the window edges that stay a fixed distance from the
        // microphone: the bottom always (the window grows upward), and the right edge when it grows leftward or the left edge when it
        // grows rightward. Measured from those edges, a piece is in the right place whichever of the old and new window frames is
        // on screen, so the disc cannot be drawn displaced for a frame. The left margin is kept for anything that reads the position.
        val fromRight = box.width - (box.micOffset + OverlayGeometry.MIC_DP) == OverlayGeometry.INSET_DP && box.micOffset != OverlayGeometry.INSET_DP
        val windowWidthPx = dp(box.width); val windowHeightPx = dp(newHeight)
        for (item in pending) {
            if (micDetached && item.view === mic) continue
            item.view.layoutParams = LayoutParams(dp(item.w), dp(item.h)).apply {
                leftMargin = dp(item.x + box.micOffset); topMargin = dp(item.y)
                bottomMargin = windowHeightPx - topMargin - height
                if (fromRight) {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = windowWidthPx - leftMargin - width
                } else {
                    gravity = Gravity.BOTTOM or Gravity.START
                }
            }
            if (item.view === panel || item.view === pill) {
                // Origin-aware: it opens from the edge nearest the microphone.
                item.view.pivotX = dp(when (side) { OverlayGeometry.Side.LEFT -> item.w; OverlayGeometry.Side.RIGHT -> 0; OverlayGeometry.Side.ABOVE -> item.w / 2 }).toFloat()
                item.view.pivotY = dp(if (side == OverlayGeometry.Side.ABOVE || item.view === panel) item.h else item.h / 2).toFloat()
            }
        }
        if (changed) {
            val w = dp(surfaceWidth); val h = dp(surfaceHeight); val off = dp(micOffsetDp)
            resizeJob?.let { removeCallbacks(it) }; resizeJob = null
            val apply = Runnable { resizeJob = null; windowW = w; windowH = h; resize?.invoke(w, h, off) }
            // Growing happens at once: the new area is empty until the drawer animates into it. Shrinking waits a few frames, so the
            // drawer is already gone from the window when its frame jumps; otherwise the old picture would be shown at the new place.
            val shrinking = micDetached && isAttachedToWindow && windowW > 0 && w <= windowW && h <= windowH && (w < windowW || h < windowH)
            if (shrinking) { resizeJob = apply; postDelayed(apply, 64) } else apply.run()
        }
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
        // Without a field the mic is faded (half transparent). Over the open drawer it is dimmed by colour instead, because a see-through
        // disc would show the drawer's end behind it; the change between the two eases rather than switching.
        val noField = !value.busy && DictationCoordinator.activeEditorSession == null
        fadeMic(if (noField && !optionsShown) 0.5f else 1f, animate = optionsShown != lastOptionsShown)
        lastOptionsShown = optionsShown
        mic.dimmed = noField && optionsShown
        mic.contentDescription = when {
            keyboard && compactKeyboard && value.busy && !value.active -> "Cancel voice processing"
            value.active -> "Finish recording"
            value.phase == "processing" -> "Processing"
            DictationCoordinator.activeEditorSession == null -> "Voice unavailable. Focus a supported text field, or enable voice typing in settings. Double tap for settings."
            keyboard && compactKeyboard -> "Hold to speak. Double tap to open voice settings."
            else -> "Hold to speak. Double tap for options."
        }
        if (!keyboard && PressSurface.canAnimate(this)) {
            // transitions.dev dropdown/modal open: what appears fades and scales in on the smooth-out curve, cards
            // barely and buttons a little more, with a 40ms stagger so a row unfolds instead of popping.
            var stagger = 0
            pillReveal?.cancel(); pill.reveal = 1f; pill.translationY = 0f
            // The drawer comes out of the microphone: the microphone itself never moves.
            val drawer = optionsShown
            val rowControls = listOf(back, settings, raw, ai, live)
            val micCentreX = dp(box.micOffset) + dp(OverlayGeometry.MIC_DP) / 2
            val micCentreY = dp(row) + dp(OverlayGeometry.MIC_DP) / 2
            for (piece in pieces.filter { it.visibility == View.VISIBLE && it !in wasShown }) {
                val card = piece === panel || piece === pill
                piece.animate().cancel()
                if (drawer && piece === pill) { growPill(micCentreX, micCentreY); continue }
                if (drawer && piece in rowControls) { emerge(piece, rowControls.size - 1 - rowControls.indexOf(piece), micCentreX, micCentreY); continue }
                piece.alpha = 0f
                piece.scaleX = if (card) 0.97f else 0.85f; piece.scaleY = piece.scaleX
                piece.translationY = if (piece === panel) dp(8).toFloat() else 0f
                piece.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(if (card) 0L else 40L * (stagger++).coerceAtMost(4)).setDuration(250L)
                    .setInterpolator(PressSurface.SMOOTH_OUT).start()
            }
            // transitions.dev error shake, on the card that explains it, or on the microphone when there is none.
            if (value.phase == "error" && previous.phase != "error") PressSurface.shake(if (panel.visibility == View.VISIBLE) panel else mic, 120L)
        }
        mic.invalidate()
    }
    /**
     * The drawer's body starts hidden behind the microphone and grows outward from its centre to full size, keeping rounded ends
     * the whole way. When it sits above the microphone it also rises out from behind it. Nothing fades: it is the same shape
     * getting bigger, so it reads as coming from inside the icon.
     */
    private fun growPill(micCentreX: Int, micCentreY: Int) {
        val lp = pill.layoutParams as LayoutParams
        pill.anchor = (micCentreX - lp.leftMargin).toFloat().coerceIn(0f, lp.width.toFloat())
        val pillCentreY = lp.topMargin + lp.height / 2
        val rise = (micCentreY - pillCentreY).toFloat()
        pill.alpha = 1f; pill.scaleX = 1f; pill.scaleY = 1f
        pill.reveal = 0f; pill.translationY = rise
        pillReveal = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L; interpolator = PressSurface.SMOOTH_OUT
            addUpdateListener { pill.reveal = it.animatedValue as Float; pill.translationY = rise * (1f - it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { pill.reveal = 1f; pill.translationY = 0f }
                override fun onAnimationCancel(a: android.animation.Animator) { pill.reveal = 1f; pill.translationY = 0f }
            })
            start()
        }
    }

    /** A control slides out from the microphone's centre to its place, the nearest first, growing and fading in as it goes. */
    private fun emerge(piece: View, rank: Int, micCentreX: Int, micCentreY: Int) {
        val lp = piece.layoutParams as LayoutParams
        val dx = micCentreX - (lp.leftMargin + lp.width / 2)
        val dy = micCentreY - (lp.topMargin + lp.height / 2)
        piece.alpha = 0f; piece.scaleX = 0.4f; piece.scaleY = 0.4f; piece.translationX = dx.toFloat(); piece.translationY = dy.toFloat()
        piece.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setStartDelay(30L * rank).setDuration(380L).setInterpolator(PressSurface.SMOOTH_OUT).start()
    }

    /** The microphone's fade eases when the drawer opens or closes, so the icon never switches abruptly between its faded and dimmed looks. */
    private fun fadeMic(target: Float, animate: Boolean) {
        micFade?.cancel(); micFade = null
        if (mic.alpha == target) return
        if (!animate || !PressSurface.canAnimate(this)) { mic.alpha = target; return }
        micFade = ValueAnimator.ofFloat(mic.alpha, target).apply {
            duration = 260L; interpolator = PressSurface.SMOOTH_OUT
            addUpdateListener { mic.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationCancel(a: android.animation.Animator) { mic.alpha = target } })
            start()
        }
    }

    /** A touch on the open options keeps them open a while longer. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!keyboard && e.actionMasked == MotionEvent.ACTION_DOWN && OverlayPresentation.options) {
            OverlayPresentation.touch()
            removeCallbacks(collapseTimer)
            OverlayPresentation.remaining()?.let { postDelayed(collapseTimer, it.coerceAtLeast(1)) }
        }
        return super.dispatchTouchEvent(e)
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
        if (changed) resize?.invoke(requiredWidth, requiredHeight, 0)
        listOf(pill, indicator, panel, back, settings, raw, ai, live, lock, cancel, copy).forEach { it.visibility = View.GONE }
        preview.text = ""
        settings.visibility = if (value.busy || keyboardCovered) View.GONE else View.VISIBLE
        settings.isEnabled = !value.busy
        place(settings, 0, 0, cell, 48)
        val mode = if (value.busy) value.mode else VoicePreferences.state.value.mode
        val modes = if (keyboardCovered) View.GONE else View.VISIBLE
        pill.visibility = modes
        pill.background = round(raisedFill, 16f)
        place(pill, cell + 2, 8, cell * 3 - 4, 32)
        val modeIndex = listOf("raw", "ai", "live").indexOf(mode)
        indicator.visibility = if (modes == View.VISIBLE && modeIndex >= 0) View.VISIBLE else View.GONE
        place(indicator, cell + 3, 11, cell - 6, 26)
        indicator.alpha = if (value.busy) 0.45f else 1f
        val slideTo = dp(cell) * modeIndex.coerceAtLeast(0).toFloat()
        indicator.animate().cancel()
        if (indicatorIndex >= 0 && modeIndex >= 0 && indicatorIndex != modeIndex && isAttachedToWindow &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            // transitions.dev tabs sliding: 250ms on the smooth-out curve.
            indicator.animate().translationX(slideTo).setDuration(250).setInterpolator(PressSurface.SMOOTH_OUT).start()
        } else indicator.translationX = slideTo
        indicatorIndex = modeIndex
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

    /**
     * A message is printed once. With the preview on, an explanation already fills the preview line, so the
     * caption above it stays empty instead of repeating it word for word.
     */
    private fun statusLine(value: VoiceUi, explained: Boolean, showPreview: Boolean): String = when {
        explained && showPreview && !value.busy -> ""
        // Reopened after its moment passed: the old verdict is stale, but the text is still the last result.
        value.phase == "done" && !OverlayPresentation.visible() -> "Last result"
        else -> value.message
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
                downX = e.rawX; downY = e.rawY; moving = false; held = false; target = ""
                mic.scaleX = 0.94f; mic.scaleY = 0.94f
                if (!ui.busy) postDelayed(beginHold, VoiceGestureTiming.HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = hypot(e.rawX - downX, e.rawY - downY)
                if (!held && !ui.busy && distance > slop && !keyboard) {
                    if (!moving) {
                        moving = true
                        removeCallbacks(beginHold)
                        // A card wider than the room beside the microphone cannot follow it across the
                        // screen without the microphone jumping. Dragging moves the idle bubble only:
                        // fold the card away first, keeping the microphone exactly where it is. A retained
                        // result stays available; a double tap reopens it.
                        dragStart?.invoke()
                        OverlayPresentation.collapse()
                        render(ui)
                    }
                    drag?.invoke(e.rawX - downX, e.rawY - downY)
                }
                if (held && ui.active && !ui.locked) {
                    val next = listOf("lock" to lock).firstOrNull { (_, v) ->
                        val p = IntArray(2); v.getLocationOnScreen(p); hypot(e.rawX - p[0] - v.width / 2f, e.rawY - p[1] - v.height / 2f) < dp(32)
                    }?.first ?: ""
                    if (next.isNotEmpty() && next != target) {
                        target = next
                        if (next == "lock") { VoiceSession.lock(); feedback(HapticFeedbackConstants.LONG_PRESS) } else choose(next)
                    } else if (next.isEmpty()) target = ""
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(beginHold); mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                when {
                    moving -> {
                        tapSequence.reset()
                        // The place the finger lifted is part of the drag: the last move may stop short of it.
                        drag?.invoke(e.rawX - downX, e.rawY - downY)
                        dragEnd?.invoke()
                    }
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
                // The system took the gesture mid-drag. The microphone is already where the user left it,
                // so that is what gets saved; otherwise it would jump back on the next show.
                if (moving) dragEnd?.invoke()
                held = false; moving = false; return true
            }
        }
        return true
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!keyboard) render(ui) }
    override fun onDetachedFromWindow() { removeCallbacks(collapseTimer); removeCallbacks(beginHold); resizeJob?.let { removeCallbacks(it) }; resizeJob = null; super.onDetachedFromWindow() }

    /**
     * The options drawer's body. [reveal] 0..1 grows it outward from [anchor] (the microphone's centre along the pill) to its full
     * width, with half-circle ends at every size. With a background set (the keyboard header's mode strip) it just draws that.
     */
    private class PillView(context: Context, private val fill: Int, private val stroke: Int, private val strokePx: Float) : View(context) {
        var reveal = 1f
            set(value) { field = value; invalidate() }
        var anchor = 0f
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (background != null) return
            val w = width.toFloat(); val h = height.toFloat()
            val a = anchor.coerceIn(0f, w)
            rect.set(a - reveal * a, 0f, a + reveal * (w - a), h)
            if (rect.width() <= 0f) return
            val half = strokePx / 2f
            rect.inset(half, half)
            val radius = minOf(rect.height(), rect.width()) / 2f
            paint.style = Paint.Style.FILL; paint.color = fill
            c.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = strokePx; paint.color = stroke
            c.drawRoundRect(rect, radius, radius, paint)
        }
    }
    private class Icon(context: Context, kind: String, description: String) : View(context) {
        private val palette = InkColors(context)
        var compactSurface = false
        var kind = kind
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }
        var highlighted = false
            set(value) { field = value; isSelected = value; invalidate() }
        /** Drawn in muted colours instead of with alpha, for a disc that sits over something it must not show through. */
        var dimmed = false
            set(value) { if (field == value) return; field = value; invalidate() }
        private var meter = 0f
        private var meterAnimation: ValueAnimator? = null
        var level = 0f
            set(value) {
                if (field == value) return
                field = value; meterAnimation?.cancel()
                if (!isShown || !isAttachedToWindow || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) { meter = value; invalidate(); return }
                // One animator for the whole session, retargeted on each level (ten a second while recording).
                val animator = meterAnimation ?: ValueAnimator().apply {
                    duration = 100; addUpdateListener { meter = it.animatedValue as Float; invalidate() }
                }.also { meterAnimation = it }
                animator.setFloatValues(meter, value); animator.start()
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
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = palette.halo; paint.alpha = 150
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
                    // The jade chip behind the active mode is the sliding indicator; only the label changes here.
                    paint.color = if (highlighted) palette.onAccent else palette.muted
                    paint.textSize = 13f * resources.displayMetrics.scaledDensity.coerceAtMost(density * 1.15f)
                    paint.typeface = MEDIUM
                    paint.textAlign = Paint.Align.CENTER
                    val label = when (kind) { "raw" -> "Raw"; "ai" -> "AI"; else -> "Live" }
                    c.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
                }
                "mic", "finish", "wait" -> {
                    // 17dp, but never wider than its cell: on a very narrow screen the cell is 32dp and a 34dp disc
                    // was clipped on its right edge by the Polish button beside it.
                    val radius = minOf(17f * density, width / 2f - density)
                    if (kind == "finish" && meter > 0f) {
                        paint.color = palette.halo; paint.alpha = (60 + meter * 120).toInt().coerceAtMost(255)
                        c.drawCircle(cx, cy, radius + (2f + meter * 4f) * density, paint)
                        paint.alpha = 255
                    }
                    paint.color = if (kind == "wait") palette.surface else palette.accent
                    c.drawCircle(cx, cy, radius, paint)
                    if (kind != "wait") ring(c, cx, cy, radius)
                    when (kind) {
                        "mic" -> icon(app.odicto.mobile.R.drawable.odicto_ic_mic, 20f, palette.onAccent)
                        "finish" -> icon(app.odicto.mobile.R.drawable.odicto_ic_stop, 20f, palette.onAccent)
                        else -> {
                            val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
                            val angle = if (animate) (android.os.SystemClock.uptimeMillis() % 1000) * 0.36f else -90f
                            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density; paint.strokeCap = Paint.Cap.ROUND; paint.color = palette.halo
                            val r = 8f * density
                            c.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 270f, false, paint)
                            if (animate && isShown) postInvalidateOnAnimation()
                        }
                    }
                }
                "settings" -> icon(app.odicto.mobile.R.drawable.odicto_ic_settings, 20f, palette.muted)
                "copy" -> icon(app.odicto.mobile.R.drawable.odicto_ic_copy, 20f, palette.muted)
                "lock" -> icon(app.odicto.mobile.R.drawable.odicto_ic_lock, 20f, palette.icon)
                "cancel" -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, 20f, palette.icon)
                "done" -> icon(app.odicto.mobile.R.drawable.odicto_ic_check, 20f, palette.halo)
                "error" -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, 20f, palette.danger)
                else -> icon(app.odicto.mobile.R.drawable.odicto_ic_close, 20f, palette.muted)
            }
        }

        /**
         * Floating bubble: the keyboard's icons and palette on a filled disc, since the bubble sits
         * over any app's content. Sizes, positions and touch handling are unchanged.
         */
        private fun drawOverlay(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val radius = minOf(width, height) / 2f - density
            fun icon(id: Int, color: Int, size: Float = 0.21f) {
                val half = (minOf(width, height) * size).toInt()
                glyph(id)?.apply { setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt()); setTint(color); draw(c) }
            }
            paint.reset(); paint.isAntiAlias = true
            val (baseFill, baseInk) = when {
                kind == "done" -> palette.success to palette.onSuccess
                kind == "error" -> palette.danger to palette.onDanger
                kind == "mic" || kind == "finish" || highlighted -> palette.accent to palette.onAccent
                else -> palette.bubble to palette.icon
            }
            val fill = if (dimmed) ColorUtils.blendARGB(baseFill, palette.bubble, 0.5f) else baseFill
            val ink = if (dimmed) ColorUtils.blendARGB(baseInk, fill, 0.35f) else baseInk
            val listening = (kind == "finish" || kind == "mic") && meter > 0f
            if (listening) {
                paint.color = palette.halo; paint.alpha = (60 + meter * 120).toInt().coerceAtMost(255)
                c.drawCircle(cx, cy, radius, paint)
                paint.alpha = 255
            }
            paint.color = fill
            // While listening the disc breathes with the input level inside a soft halo.
            val discRadius = if (listening) radius - (1f - meter) * 3f * density - 2f * density else radius
            c.drawCircle(cx, cy, discRadius, paint)
            if (baseFill == palette.accent && !dimmed) ring(c, cx, cy, discRadius)
            if (baseFill == palette.bubble) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = palette.hairline
                c.drawCircle(cx, cy, radius - density / 2f, paint)
                paint.style = Paint.Style.FILL
            }
            when (kind) {
                // The idle bubble wears the Odicto mark, not a microphone; the mark is wider than tall, so it is drawn larger.
                "mic" -> icon(app.odicto.mobile.R.drawable.odicto_ic_logo, ink, 0.29f)
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
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density; paint.strokeCap = Paint.Cap.ROUND; paint.color = palette.halo
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

        /** The icon and disc colours, read once from the day or night palette of the context this view was built with. */
        private class InkColors(context: Context) {
            val accent = context.getColor(R.color.odicto_key_accent)
            val halo = context.getColor(R.color.odicto_accent)
            val onAccent = context.getColor(R.color.odicto_on_accent)
            val surface = context.getColor(R.color.odicto_surface_raised)
            val icon = context.getColor(R.color.odicto_toolbar_icon)
            val muted = context.getColor(R.color.odicto_text_muted)
            val bubble = context.getColor(R.color.odicto_panel_card)
            val hairline = context.getColor(R.color.odicto_hairline)
            val success = context.getColor(R.color.odicto_success)
            val onSuccess = context.getColor(R.color.odicto_on_success)
            val danger = context.getColor(R.color.odicto_danger)
            val onDanger = context.getColor(R.color.odicto_on_danger)
        }
        private companion object {
            /** Created once: onDraw runs every frame of the recording meter. */
            val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
}
