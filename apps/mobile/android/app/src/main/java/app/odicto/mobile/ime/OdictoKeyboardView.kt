package app.odicto.mobile.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import app.odicto.mobile.BuildConfig
import app.odicto.mobile.R
import app.odicto.mobile.storage.KeySettings
import app.odicto.mobile.storage.VoicePreferences
import kotlin.math.abs
import kotlin.math.min
import androidx.core.content.ContextCompat

/**
 * The Odicto key grid, built in code rather than from a platform `Keyboard` XML resource.
 *
 * A hand-drawn grid is used deliberately: the platform XML format addresses keys by `keyCode`, and
 * those constants collide with printable characters, so a layout-driven keyboard cannot tell a
 * backspace from the letter "c" without a side table. Owning the keys also lets the spacebar carry a
 * control gesture and lets every key be sized proportionally to the screen instead of to fixed dp.
 *
 * Non-character keys draw a vector icon rather than a text glyph. Arrow and enter symbols rendered as
 * text were unreadable at key size and were corrupted by encoding on the way into the build.
 */
class OdictoKeyboardView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    fun interface OnKey {
        fun onKey(spec: KeySpec)
    }

    enum class Key {
        SHIFT, BACKSPACE, ENTER, SWITCH, LETTERS, NUMBERS, SYMBOLS, SPACE,
        CHARACTER,
    }

    /**
     * [weight] is the key's share of its row. Rows are normalised, so a row may not total exactly
     * 10 units: the bottom row is intentionally lighter on the left to leave room for the spacebar.
     */
    data class KeySpec(
        val key: Key,
        val label: String,
        val output: String,
        val weight: Float,
        val icon: Int = 0,
        /** Mode keys (123, ABC, #+=) draw a little smaller than the letters. */
        val control: Boolean = false,
        /**
         * Which deletion this is in a held backspace. 0 is the touch-down.
         * The controller uses it to switch from letters to words. Other keys leave it at 0.
         */
        val repeatOrdinal: Int = 0,
        val touchAtNanos: Long = 0L,
        val hapticAtNanos: Long = 0L,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var geometryWidth = -1
    private var geometryHeight = -1
    private var split = false
    private var spaceHold: Runnable? = null
    private var uppercaseCapture: ((KeySpec) -> (() -> Boolean)?)? = null
    private var uppercaseAccept: (() -> Boolean)? = null
    private var uppercaseShow: Runnable? = null
    private var uppercaseBounds: Rect? = null
    private var uppercaseVisible = false
    private var uppercaseSelected = false
    private var spaceGesture: SpaceGesture? = null
    private var repeating = false
    private var repeatRunnable: Runnable? = null
    private var onKey: OnKey = OnKey { }
    private var onBackspaceReleased: (() -> Unit)? = null
    private var dragListener: ((Int, Int) -> Unit)? = null
    private var dragAllowed: () -> Boolean = { true }
    private val cells = mutableListOf<Cell>()
    private var activeCell: Cell? = null
    private var primaryId = -1
    private var secondaryId = -1
    private var secondaryCell: Cell? = null
    private var repeatOwnerId = -1
    private var oneShot = false
    private var capsLocked = false
    private var lastShiftTap = 0L
    /** Drawn in [dispatchDraw] so the letter is visible on every row, including the top one. */
    private var magnifierText: String? = null
    private var magnifierAnchor: Rect? = null
    private var punctual: ActivePunct? = null
    private var punctualShow: Runnable? = null
    private var enterKind = ImeActions.Kind.NEWLINE
    private var enterLabel: String? = null
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE03C3C42.toInt() }
    private val bubbleShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE4E4E8.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val stripFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF02A2A2E.toInt() }
    private val stripSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF05A5A62.toInt() }

    init {
        orientation = VERTICAL
        isClickable = true
        Haptics.prepare(context)
        buildRows()
    }

    fun setUppercaseChoiceListener(capture: (KeySpec) -> (() -> Boolean)?) { uppercaseCapture = capture }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) cancelTouch()
        super.onWindowFocusChanged(hasWindowFocus)
    }

    fun setOnKeyListener(listener: OnKey) { onKey = listener }
    private var backspaceRepeatGate: () -> Boolean = { true }
    fun setBackspaceRepeatGate(gate: () -> Boolean) { backspaceRepeatGate = gate }
    fun setBackspaceReleaseListener(listener: () -> Unit) { onBackspaceReleased = listener }
    fun setDragListener(listener: (Int, Int) -> Unit, allowed: () -> Boolean) { dragListener = listener; dragAllowed = allowed }

    /** Stops a held backspace. Does not cancel other work posted on this view. */
    fun stopRepeating() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        repeating = false
    }

    fun cancelTouch() {
        spaceGesture?.cancel()
        spaceGesture = null
        releaseActive()
        clearSecondary()
        primaryId = -1
    }

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }

    internal fun enterAction(): ImeActions.Kind = enterKind

    internal fun setEnterAction(kind: ImeActions.Kind, label: String? = kind.label) {
        enterKind = kind
        enterLabel = label
        applyEnterFace()
    }

    internal fun keyGapDp(): Float = KEY_GAP_DP

    /** The character drawn above the pressed key, if a finger is down on one. */
    internal fun visiblePreview(): String? = magnifierText

    /**
     * Feeds a key press through the shared haptic setting. The keyboard produced no feedback at all
     * before, so there was no way to confirm a key had registered without watching the text appear.
     */
    private var touchEntryNanos = 0L

    private fun tapFeedback(touchAtNanos: Long = touchEntryNanos): Long {
        val level = VoicePreferences.levelOf(VoicePreferences.state.value)
        if (level <= 0) return 0L
        return Haptics.tapMeasured(context, level, touchAtNanos = touchAtNanos)
    }

    fun setShifted(shifted: Boolean) {
        oneShot = shifted
        if (!shifted) capsLocked = false
        relabel()
    }

    /**
     * One tap arms the next letter. A second tap while that is armed locks capitals.
     * A tap during caps lock turns it off.
     */
    fun cycleShift() {
        val now = android.os.SystemClock.uptimeMillis()
        if (capsLocked) {
            capsLocked = false
            oneShot = false
        } else if (oneShot && now - lastShiftTap < SHIFT_DOUBLE_MS) {
            capsLocked = true
            oneShot = false
        } else {
            oneShot = !oneShot
        }
        lastShiftTap = now
        relabel()
    }

    fun currentShift(): Boolean = activePage == Page.LETTERS && (oneShot || capsLocked)

    private fun upperCase(): Boolean = capsLocked || oneShot

    private fun consumeOneShot() {
        if (!oneShot || capsLocked) return
        oneShot = false
        relabel()
    }

    private var activePage: Page = Page.LETTERS
    private var enterMatched = false

    /**
     * Rebuilds the grid for [page]. Shift and caps state are per page, so both reset here: a page
     * that inherited the letters page's shift would render every symbol as a capital.
     */
    fun showPage(page: Page) {
        cancelTouch()
        removeAllViews()
        cells.clear()
        releaseActive()
        enterMatched = false
        activePage = page
        for (row in layoutFor(page)) addRow(row)
        relabel()
        applyEnterFace()
        requestLayout()
    }

    enum class Page { LETTERS, NUMBERS, SYMBOLS }

    private fun buildRows() { showPage(Page.LETTERS) }

    private fun layoutFor(page: Page): List<List<KeySpec>> = when (page) {
        Page.LETTERS -> LAYOUT
        Page.NUMBERS -> NUMBERS_LAYOUT
        Page.SYMBOLS -> SYMBOLS_LAYOUT
    }

    private fun addRow(row: List<KeySpec>) {
        val spaceRow = row.any { it.key == Key.SPACE }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // A fixed height per row keeps the rows evenly split; weight 0 on height is what
            // previously collapsed every row onto its own line. The space row is taller so the
            // bar is thick enough for a thumb that used to land on the comma or the period.
            val height = if (spaceRow) (rowHeightPx * SPACE_ROW_SCALE).toInt() else rowHeightPx
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height, ROW_HEIGHT_WEIGHT)
        }
        // Nine letters in a ten-column grid: half a key of space on each side, so a–l match q–p.
        val centeredNine = row.size == 9 && row.all { it.key == Key.CHARACTER }
        fun addKey(spec: KeySpec, weight: Float = spec.weight) {
            val cap = KeyCap(context, spec)
            cap.setOnClickListener { cancelTouch(); press(spec) }
            if (spec.key == Key.CHARACTER && spec.output.singleOrNull()?.isLetter() == true) {
                cap.setOnLongClickListener {
                    cancelTouch()
                    press(spec.copy(label = spec.output.uppercase(), output = spec.output.uppercase()))
                    true
                }
                androidx.core.view.ViewCompat.replaceAccessibilityAction(cap,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    "Type uppercase ${spec.output.uppercase()}") { _, _ -> cap.performLongClick() }
            }
            line.addView(cap, keyParams(weight))
        }
        if (split) {
            val spaceIndex = row.indexOfFirst { it.key == Key.SPACE }
            val halves = if (spaceIndex >= 0) {
                val space = row[spaceIndex].copy(weight = row[spaceIndex].weight / 2f)
                (row.take(spaceIndex) + space) to (listOf(space) + row.drop(spaceIndex + 1))
            } else row.take((row.size + 1) / 2) to row.drop((row.size + 1) / 2)
            val leftMargins = halves.first.size * gapPx() * 2
            val rightMargins = halves.second.size * gapPx() * 2
            val sideWidth = ((geometryWidth - paddingLeft - paddingRight - 48f * density) / 2f)
            fun addHalf(half: List<KeySpec>, margins: Int) {
                val total = half.sumOf { it.weight.toDouble() }.toFloat()
                half.forEach { addKey(it, it.weight / total * (sideWidth - margins).coerceAtLeast(1f)) }
            }
            addHalf(halves.first, leftMargins)
            line.addView(gapView(), LayoutParams((48f * density).toInt(), LayoutParams.MATCH_PARENT))
            addHalf(halves.second, rightMargins)
        } else {
            if (centeredNine) line.addView(gapView(), keyParams(0.5f))
            row.forEach { addKey(it) }
            if (centeredNine) line.addView(gapView(), keyParams(0.5f))
        }
        addView(line)
    }

    private fun gapView(): View = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun keyParams(weight: Float): LayoutParams {
        val gap = gapPx()
        return LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = gap
            marginEnd = gap
            topMargin = gap
            bottomMargin = gap
        }
    }

    /**
     * Registers one key: haptic feedback and the key's output fire together on touch-down, because
     * waiting for the release (the platform `click` path) made every keystroke and its buzz land
     * late. [startRepeat] is deliberately not called here: it belongs to the touch lifecycle, or an
     * accessibility activation would start a repeat that never stops.
     */
    private fun press(spec: KeySpec, touchAtNanos: Long = 0L): KeySpec {
        val hapticAtNanos = tapFeedback(touchAtNanos)
        if (spec.key == Key.SPACE) {
            if (touchAtNanos == 0L) onKey.onKey(spec)
            return spec
        }
        if (spec.key == Key.CHARACTER) {
            val letter = spec.output.length == 1 && spec.output[0].isLetter()
            val text = if (letter && upperCase() && activePage == Page.LETTERS) spec.output.uppercase() else spec.output
            val dispatched = spec.copy(label = text, output = text, touchAtNanos = touchAtNanos, hapticAtNanos = hapticAtNanos)
            onKey.onKey(dispatched)
            if (letter) consumeOneShot()
            return dispatched
        }
        val dispatched = spec.copy(touchAtNanos = touchAtNanos, hapticAtNanos = hapticAtNanos)
        onKey.onKey(dispatched)
        return dispatched
    }

    private fun startRepeat(ownerId: Int) {
        if (repeatOwnerId != -1) return
        repeatOwnerId = ownerId
        repeating = true
        var next = 1
        var deadline = android.os.SystemClock.uptimeMillis() + BackspacePace.HOLD_MS
        val runnable = object : Runnable {
            override fun run() {
                if (!repeating || repeatRunnable !== this) return
                if (BuildConfig.DEBUG) KeyLatencyProbe.repeatDeadline(deadline, android.os.SystemClock.uptimeMillis())
                val allowed = backspaceRepeatGate()
                if (!repeating || repeatRunnable !== this) return
                if (allowed) {
                    val touched = if (BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtimeNanos() else 0L
                    val feedback = tapFeedback(touched)
                    onKey.onKey(backspaceSpec(next).copy(touchAtNanos = touched, hapticAtNanos = feedback))
                    next += 1
                }
                if (!repeating || repeatRunnable !== this) return
                val interval = if (next <= 1) BackspacePace.LETTER_INTERVAL_MS else BackspacePace.delayUntil(next)
                val now = android.os.SystemClock.uptimeMillis()
                deadline += ((now - deadline).coerceAtLeast(0L) / interval + 1L) * interval
                handler.postAtTime(this, deadline)
            }
        }
        repeatRunnable = runnable
        handler.postAtTime(runnable, deadline)
    }

    private fun releaseRepeat(ownerId: Int) {
        if (ownerId == -1 || repeatOwnerId != ownerId) return
        stopRepeating()
        repeatOwnerId = -1
        onBackspaceReleased?.invoke()
    }

    private fun backspaceSpec(ordinal: Int) = KeySpec(
        Key.BACKSPACE, "", "", BACKSPACE_WEIGHT, R.drawable.odicto_ic_backspace, repeatOrdinal = ordinal,
    )

    private fun applyEnterFace() {
        for (index in 0 until childCount) {
            val line = getChildAt(index) as? LinearLayout ?: continue
            for (keyIndex in 0 until line.childCount) {
                val cap = line.getChildAt(keyIndex) as? KeyCap ?: continue
                if (cap.spec.key == Key.ENTER) cap.setActionLabel(enterLabel)
            }
        }
    }

    private fun relabel() {
        for (index in 0 until childCount) {
            val line = getChildAt(index) as? LinearLayout ?: continue
            for (keyIndex in 0 until line.childCount) {
                val cap = line.getChildAt(keyIndex) as? KeyCap ?: continue
                cap.applyShift(upperCase() && activePage == Page.LETTERS)
                cap.setAccent(cap.spec.key == Key.SHIFT && capsLocked)
            }
        }
    }

    /** The grid owns every touch: children are visuals only, so keys register on press. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!BuildConfig.DEBUG) return handleTouch(event)
        val previous = touchEntryNanos
        val entry = android.os.SystemClock.elapsedRealtimeNanos()
        touchEntryNanos = entry
        KeyLatencyProbe.event(event.eventTime, entry, android.os.SystemClock.uptimeMillis())
        return try { handleTouch(event) } finally {
            KeyLatencyProbe.handler(entry, android.os.SystemClock.elapsedRealtimeNanos())
            touchEntryNanos = previous
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touched = touchEntryNanos
                requestUnbufferedDispatch(event)
                cancelTouch()
                primaryId = event.getPointerId(0)
                pressAt(event.x, event.y, primary = true, touchAtNanos = touched)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                abandonUppercase()
                abandonPunctuation()
                val touched = touchEntryNanos
                requestUnbufferedDispatch(event)
                val index = event.actionIndex
                if (primaryId == -1) {
                    primaryId = event.getPointerId(index)
                    pressAt(event.getX(index), event.getY(index), primary = true, touchAtNanos = touched)
                } else if (secondaryId == -1) {
                    clearSecondary()
                    secondaryId = event.getPointerId(index)
                    pressAt(event.getX(index), event.getY(index), primary = false, touchAtNanos = touched)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (secondaryCell?.spec?.key == Key.BACKSPACE) {
                    val index = event.findPointerIndex(secondaryId)
                    if (index < 0 || cellAt(event.getX(index), event.getY(index))?.cap !== secondaryCell?.cap) clearSecondary()
                }
                val pointer = event.findPointerIndex(primaryId)
                if (pointer < 0) return true
                trackUppercase(event.getX(pointer), event.getY(pointer))
                if (punctual != null) {
                    trackPunctuation(event)
                    return true
                }
                val gesture = spaceGesture
                if (gesture != null) {
                    val wasArmed = gesture.armed
                    val action = gesture.move(event.getX(pointer), event.getY(pointer))
                    if (!dragAllowed()) {
                        if (gesture.armed) gesture.cancel()
                        clearSpaceHold()
                        return true
                    }
                    if (!wasArmed && gesture.armed) cursorFeedback()
                    if (action != null) {
                        when (action) {
                            is SpaceGesture.Action.Cursor -> dragListener?.invoke(action.steps, 0)
                            is SpaceGesture.Action.Line -> dragListener?.invoke(0, action.steps)
                            else -> Unit
                        }
                    }
                    return true
                }
                // A short tap wobbles. That must not cancel the letter. Only a held backspace
                // stops when the finger has actually moved onto another key.
                if (activeCell?.spec?.key == Key.BACKSPACE) {
                    val index = event.findPointerIndex(primaryId).coerceAtLeast(0)
                    val cell = cellAt(event.getX(index), event.getY(index))
                    if (cell?.cap !== activeCell?.cap) releaseActive()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == secondaryId) clearSecondary()
                else if (id == primaryId) {
                    trackUppercase(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    releasePrimary()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) == primaryId) {
                    trackUppercase(event.x, event.y)
                    releasePrimary()
                }
                cancelTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouch()
                return true
            }
        }
        return true
    }

    private fun pressAt(x: Float, y: Float, primary: Boolean, touchAtNanos: Long) {
        val cell = cellAt(x, y) ?: return
        if (BuildConfig.DEBUG) KeyLatencyProbe.hit(touchAtNanos, android.os.SystemClock.elapsedRealtimeNanos())
        if (!primary) {
            secondaryCell = cell
            cell.cap.setPressedVisual(true)
            if (cell.spec.key == Key.BACKSPACE) startRepeat(secondaryId)
            if (cell.spec.key == Key.SPACE) {
                tapFeedback()
                onKey.onKey(KeySpec(Key.SPACE, "Space", " ", SPACE_WEIGHT))
            } else {
                press(cell.spec, touchAtNanos)
            }
            return
        }
        activeCell = cell
        cell.cap.setPressedVisual(true)
        if (cell.spec.key == Key.SPACE) {
            tapFeedback()
            val gesture = SpaceGesture((4f * density).toInt().coerceAtLeast(1), 1,
                (8f * density).toInt().coerceAtLeast(1))
            spaceGesture = gesture.also { it.down(x, y) }
            spaceHold = Runnable {
                if (spaceGesture === gesture && dragAllowed() && gesture.hold()) cursorFeedback()
            }.also { handler.postDelayed(it, 250L) }
            return
        }
        // Capture the glyph before press() consumes one-shot shift, or the bubble
        // would show the unshifted letter after the key has already committed.
        val shown = previewText(cell.spec)
        if (cell.spec.key == Key.BACKSPACE) startRepeat(primaryId)
        val dispatched = press(cell.spec, touchAtNanos)
        if (activeCell !== cell) return
        revealPreview(cell, shown)
        if (cell.spec.key != Key.BACKSPACE) {
            armPunctuation(cell)
            armUppercase(cell, dispatched)
        }
    }

    private fun cursorFeedback() {
        tapFeedback()
        activeCell?.cap?.setAccent(true)
        announceForAccessibility("Cursor control")
    }

    private fun clearSpaceHold() {
        spaceHold?.let { handler.removeCallbacks(it) }
        spaceHold = null
    }

    private fun armUppercase(cell: Cell, dispatched: KeySpec) {
        val shown = dispatched.output
        if (activeCell !== cell || activePage != Page.LETTERS || shown.singleOrNull()?.isLowerCase() != true) return
        uppercaseAccept = uppercaseCapture?.invoke(dispatched) ?: return
        uppercaseBounds = Rect(cell.rect)
        uppercaseShow = Runnable {
            if (activeCell !== cell || uppercaseAccept == null) return@Runnable
            uppercaseVisible = true
            uppercaseSelected = true
            revealPreview(cell, shown.uppercase())
            tapFeedback()
            announceForAccessibility("Uppercase ${shown.uppercase()}")
            invalidate()
        }.also { handler.postDelayed(it, 300L) }
    }

    private fun trackUppercase(x: Float, y: Float) {
        val bounds = uppercaseBounds ?: return
        if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) {
            abandonUppercase()
        }
    }

    private fun abandonUppercase() {
        if (uppercaseVisible) clearPreview()
        uppercaseShow?.let { handler.removeCallbacks(it) }
        uppercaseShow = null
        uppercaseAccept = null
        uppercaseBounds = null
        uppercaseVisible = false
        uppercaseSelected = false
        invalidate()
    }

    private fun releasePrimary() {
        val accept = if (uppercaseVisible && uppercaseSelected) uppercaseAccept else null
        abandonUppercase()
        accept?.invoke()
        finishPunctuation()
        commitSpaceIfTap()
        releaseActive()
        primaryId = -1
    }

    private fun clearSecondary() {
        val cell = secondaryCell
        secondaryCell = null
        if (cell?.cap !== activeCell?.cap) cell?.cap?.setPressedVisual(false)
        releaseRepeat(secondaryId)
        secondaryId = -1
    }

    private fun previewText(spec: KeySpec): String {
        if (spec.key != Key.CHARACTER) return spec.label
        val letter = spec.output.length == 1 && spec.output[0].isLetter()
        return if (letter && upperCase() && activePage == Page.LETTERS) spec.output.uppercase() else spec.output
    }

    /**
     * Paints the pressed character inside this view. A separate popup was clipped by the IME window,
     * so only a key whose bubble happened to land on the toolbar (Q) ever showed, and a later swap
     * turned that bubble into a different symbol.
     */
    private fun revealPreview(cell: Cell, text: String) {
        if (text.isEmpty() || cell.spec.key != Key.CHARACTER) {
            clearPreview()
            return
        }
        magnifierText = text
        magnifierAnchor = Rect(cell.rect)
        bubbleText.typeface = typefaceFor(context)
        bubbleText.textSize = (GLYPH_SP[keys.keySize] ?: 17.6f) * density * PREVIEW_TEXT_FACTOR
        invalidate()
    }

    /**
     * Comma and period insert on touch-down, same as every other key. Holding, or sliding off the
     * key, opens the neighbours. Releasing on a different symbol replaces the one just inserted.
     */
    private fun armPunctuation(cell: Cell) {
        val options = Punctuation.options(cell.spec.output) ?: return
        val home = Punctuation.homeIndex(cell.spec.output)
        punctual = ActivePunct(options, Rect(cell.rect), home, false, cell.spec.output)
        val show = Runnable {
            val active = punctual ?: return@Runnable
            if (!active.visible) {
                active.visible = true
                invalidate()
            }
        }
        punctualShow = show
        handler.postDelayed(show, PUNCT_SHOW_MS)
    }

    private fun trackPunctuation(event: MotionEvent) {
        val active = punctual ?: return
        val pointer = event.findPointerIndex(primaryId).coerceAtLeast(0)
        val x = event.getX(pointer)
        val y = event.getY(pointer)
        val dx = x - active.anchor.centerX()
        val dy = active.anchor.centerY() - y
        // A fast tap always drifts a few pixels. Only a deliberate slide opens the strip,
        // otherwise the release deletes the mark and types a different one.
        val slide = PUNCT_SLIDE_DP * density
        val opened = !active.visible && (abs(dx) > slide || dy > slide)
        if (opened) active.visible = true
        if (!active.visible) return
        val next = Punctuation.indexFromLeft(active.options.size, stripLeft(active), x, punctCellPx())
        val moved = next != active.index
        if (moved) {
            active.index = next
            tapFeedback()
        }
        if (opened || moved) invalidate()
    }

    private fun finishPunctuation() {
        val active = punctual ?: return
        punctual = null
        clearPunctualShow()
        if (!active.visible) return
        val chosen = active.options[active.index]
        if (chosen == active.inserted) return
        onKey.onKey(backspaceSpec(0))
        onKey.onKey(KeySpec(Key.CHARACTER, chosen, chosen, 1f))
    }

    private fun abandonPunctuation() {
        punctual = null
        clearPunctualShow()
    }

    private fun clearPunctualShow() {
        punctualShow?.let { handler.removeCallbacks(it) }
        punctualShow = null
    }

    private fun punctCellPx(): Float = PUNCT_CELL_DP * density

    private fun clearPreview() {
        if (magnifierText == null && magnifierAnchor == null) return
        magnifierText = null
        magnifierAnchor = null
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val choice = uppercaseBounds
        if (uppercaseVisible && choice != null) {
            canvas.drawRoundRect(choice.left.toFloat(), choice.top.toFloat(), choice.right.toFloat(), choice.bottom.toFloat(), 8f * density, 8f * density, stripSelected)
            bubbleText.typeface = typefaceFor(context)
            bubbleText.textSize = (GLYPH_SP[keys.keySize] ?: 17.6f) * resources.displayMetrics.scaledDensity
            val baseline = choice.exactCenterY() - (bubbleText.descent() + bubbleText.ascent()) / 2f
            canvas.drawText(activeCell?.spec?.output?.uppercase().orEmpty(), choice.exactCenterX(), baseline, bubbleText)
        }
        val strip = punctual
        if (strip != null && strip.visible) {
            drawPunctuation(canvas, strip)
            return
        }
        val text = magnifierText ?: return
        val anchor = magnifierAnchor ?: return
        val density = this.density
        bubbleText.textSize = (GLYPH_SP[keys.keySize] ?: 17.6f) * density * PREVIEW_TEXT_FACTOR
        val bubbleW = (anchor.width() * PREVIEW_WIDTH_FACTOR).coerceAtLeast(PREVIEW_MIN_DP * density)
        val bubbleH = anchor.height() * PREVIEW_HEIGHT_FACTOR
        var top = anchor.top - bubbleH + 4f * density
        // The top row has no keys above it. Sit the bubble on the key so it stays inside the IME.
        if (top < 2f * density) top = anchor.top + 2f * density
        if (top + bubbleH > height - 2f * density) top = (height - bubbleH - 2f * density).coerceAtLeast(0f)
        var left = anchor.centerX() - bubbleW / 2f
        val maxLeft = (width - bubbleW - 2f * density).coerceAtLeast(2f * density)
        left = left.coerceIn(2f * density, maxLeft)
        val radius = 10f * density
        canvas.drawRoundRect(left + density, top + density, left + bubbleW + density, top + bubbleH + density, radius, radius, bubbleShade)
        canvas.drawRoundRect(left, top, left + bubbleW, top + bubbleH, radius, radius, bubbleFill)
        val baseline = top + bubbleH / 2f - (bubbleText.descent() + bubbleText.ascent()) / 2f
        canvas.drawText(text, left + bubbleW / 2f, baseline, bubbleText)
    }

    private fun stripLeft(strip: ActivePunct): Float =
        Punctuation.stripLeft(strip.anchor.left.toFloat(), punctCellPx() * strip.options.size, width.toFloat(), 2f * density)

    private fun drawPunctuation(canvas: Canvas, strip: ActivePunct) {
        val density = this.density
        val cell = punctCellPx()
        val width = cell * strip.options.size
        val height = cell * 1.15f
        val left = stripLeft(strip)
        var top = strip.anchor.top - height - 4f * density
        if (top < 2f * density) top = 2f * density
        val radius = 10f * density
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, stripFill)
        val selectedLeft = left + cell * strip.index
        canvas.drawRoundRect(selectedLeft + 2f, top + 2f, selectedLeft + cell - 2f, top + height - 2f, radius, radius, stripSelected)
        bubbleText.typeface = typefaceFor(context)
        bubbleText.textSize = (GLYPH_SP[keys.keySize] ?: 17.6f) * density * 0.95f
        val baseline = top + height / 2f - (bubbleText.descent() + bubbleText.ascent()) / 2f
        strip.options.forEachIndexed { index, symbol ->
            canvas.drawText(symbol, left + cell * index + cell / 2f, baseline, bubbleText)
        }
    }

    private fun commitSpaceIfTap() {
        val gesture = spaceGesture ?: return
        spaceGesture = null
        if (gesture.up() == SpaceGesture.Action.Tap) {
            onKey.onKey(KeySpec(Key.SPACE, "Space", " ", SPACE_WEIGHT))
        }
    }

    private fun releaseActive() {
        clearSpaceHold()
        abandonUppercase()
        if (activeCell?.spec?.key == Key.SPACE) activeCell?.cap?.setAccent(false)
        abandonPunctuation()
        clearPreview()
        val cell = activeCell
        activeCell = null
        if (cell?.cap !== secondaryCell?.cap) cell?.cap?.setPressedVisual(false)
        releaseRepeat(primaryId)
    }

    private fun gapPx(): Int = (KEY_GAP_DP * density).toInt().coerceAtLeast(1)

    private fun cellAt(x: Float, y: Float): Cell? = cells.firstOrNull {
        x >= it.rect.left && x < it.rect.right && y >= it.rect.top && y < it.rect.bottom
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        if (available != geometryWidth) {
            geometryWidth = available
            split = (available - paddingLeft - paddingRight) / density >= 600f
            showPage(activePage)
        }
        val naturalHeight = rowHeightPx * (childCount - 1 + SPACE_ROW_SCALE)
        val heightLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) naturalHeight
            else (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        val scale = min(1f, heightLimit / naturalHeight.coerceAtLeast(1f))
        for (index in 0 until childCount) {
            val row = getChildAt(index)
            val factor = if (index == childCount - 1) SPACE_ROW_SCALE else 1f
            val rowHeight = (rowHeightPx * factor * scale).toInt()
            if (row.layoutParams.height != rowHeight) {
                cancelTouch()
                row.layoutParams = row.layoutParams.apply { height = rowHeight }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (geometryHeight != measuredHeight) {
            cancelTouch()
            geometryHeight = measuredHeight
        }
    }

    private val density get() = resources.displayMetrics.density

    /** Key height and glyph scale follow the user's size preference, re-read whenever it changes. */
    private val keys get() = VoicePreferences.state.value.keys
    private val rowHeightPx get() = ((KEY_HEIGHT_DP[keys.keySize] ?: 48f) * density).toInt()

    /**
     * Standard weight-based measurement, with one addition: each key rectangle is recorded here so
     * the touch handler knows which pixels belong to which key. Row coordinates are relative to the
     * row, so the row's own offset is added to put every rectangle in the grid's coordinate space.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Fewer keys on the bottom row means less margin, so the same weight draws wider than
        // backspace. Pin enter to backspace's width and give the difference to the spacebar.
        // 123 and comma keep the widths they already have.
        if (alignEnterWithBackspace()) {
            measure(
                MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY),
            )
            super.onLayout(changed, left, top, right, bottom)
        }
        cells.clear()
        for (row in 0 until childCount) {
            val line = getChildAt(row) as? LinearLayout ?: continue
            for (column in 0 until line.childCount) {
                val cap = line.getChildAt(column) as? KeyCap ?: continue
                cells += Cell(
                    Rect(cap.left + line.left, cap.top + line.top, cap.right + line.left, cap.bottom + line.top),
                    cap.spec,
                    cap,
                )
            }
        }
    }

    private fun alignEnterWithBackspace(): Boolean {
        if (enterMatched) return false
        val backspace = findCap(Key.BACKSPACE) ?: return false
        val enter = findCap(Key.ENTER) ?: return false
        val enterRow = enter.parent as? LinearLayout ?: return false
        val space = (0 until enterRow.childCount).mapNotNull { enterRow.getChildAt(it) as? KeyCap }
            .lastOrNull { it.spec.key == Key.SPACE } ?: return false
        val delta = enter.width - backspace.width
        enterMatched = true
        if (backspace.width <= 0 || delta == 0) return false
        val row = enter.parent as? LinearLayout ?: return false
        for (index in 0 until row.childCount) {
            val cap = row.getChildAt(index) as? KeyCap ?: continue
            val width = when (cap.spec.key) {
                Key.ENTER -> backspace.width
                Key.SPACE -> if (cap === space) cap.width + delta else cap.width
                else -> cap.width
            }
            val params = cap.layoutParams as LayoutParams
            params.width = width.coerceAtLeast(1)
            params.weight = 0f
            cap.layoutParams = params
        }
        return true
    }

    private fun findCap(key: Key): KeyCap? {
        for (row in 0 until childCount) {
            val line = getChildAt(row) as? LinearLayout ?: continue
            for (column in 0 until line.childCount) {
                val cap = line.getChildAt(column) as? KeyCap ?: continue
                if (cap.spec.key == key) return cap
            }
        }
        return null
    }

    private class Cell(val rect: Rect, val spec: KeySpec, val cap: KeyCap)

    private class ActivePunct(
        val options: List<String>,
        val anchor: Rect,
        var index: Int,
        var visible: Boolean,
        val inserted: String,
    )

    private class KeyCap(context: Context, val spec: KeySpec) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL
            textAlign = Paint.Align.CENTER
        }
        private val icon: Drawable? = if (spec.icon != 0) ContextCompat.getDrawable(context, spec.icon) else null
        private var label: String = spec.label
        private var actionLabel: String? = null
        private var pressed = false
        private var accent = false

        init {
            isClickable = true
            isFocusable = true
            contentDescription = spec.label
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        /**
         * A short dip and settle while the finger is down. Colour alone is a weak signal on a dark
         * keyboard, and it is invisible to anyone who cannot distinguish the pressed fill from the
         * resting one. Driven by the grid's touch handling, not by this view's own events.
         */
        fun setPressedVisual(down: Boolean) {
            if (pressed == down) return
            pressed = down
            isPressed = down
            // A colour change on contact. A scale animation read as lag between the finger and the key.
            invalidate()
        }

        override fun onDetachedFromWindow() {
            animate().cancel()
            super.onDetachedFromWindow()
        }

        fun applyShift(shifted: Boolean) {
            val letter = spec.output.length == 1 && spec.output[0].isLetter()
            label = if (spec.key == Key.CHARACTER && letter && shifted) spec.output.uppercase() else spec.label
            contentDescription = actionLabel ?: label
            invalidate()
        }

        fun setActionLabel(value: String?) {
            actionLabel = value
            contentDescription = value ?: label
            invalidate()
        }

        fun setAccent(on: Boolean) {
            if (accent == on) return
            accent = on
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val radius = 8f * density

            fill.style = Paint.Style.FILL
            fill.color = if (pressed || accent) PRESSED else if (isEnabled) NORMAL else DISABLED
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)

            if (spec.key == Key.SPACE) {
                val tickW = width * 0.16f
                val tickH = 3f * density
                val left = (width - tickW) / 2f
                val top = (height - tickH) / 2f
                fill.color = if (pressed) 0xFFFFFFFF.toInt() else 0xFF9A9AA3.toInt()
                canvas.drawRoundRect(left, top, left + tickW, top + tickH, tickH, tickH, fill)
                return
            }

            val action = actionLabel
            if (action != null) {
                fill.style = Paint.Style.FILL
                fill.color = if (isEnabled) LABEL else LABEL_DISABLED
                val scale = if (action.length > 4) 0.58f else 0.72f
                text.textSize = glyphSp() * density * scale
                text.typeface = typefaceFor(context)
                val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
                canvas.drawText(action, width / 2f, baseline, text)
                return
            }

            val drawable = icon
            if (drawable != null) {
                val size = min(width, height) * 0.42f
                val left = (width - size) / 2f
                val top = (height - size) / 2f
                drawable.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
                // Tint the vector to the label colour so it matches the text keys.
                drawable.setTint(if (isEnabled) LABEL else LABEL_DISABLED)
                drawable.draw(canvas)
                return
            }

            fill.style = Paint.Style.FILL
            fill.color = if (isEnabled) LABEL else LABEL_DISABLED
            // Size and typeface follow the user's key preference rather than a fixed value baked in.
            val scale = if (spec.control) 0.78f else 1f
            text.textSize = glyphSp() * density * scale
            text.typeface = typefaceFor(context)
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, width / 2f, baseline, text)
        }

        private fun glyphSp(): Float = GLYPH_SP[VoicePreferences.state.value.keys.keySize] ?: 17.6f
        private companion object {
            const val NORMAL = 0xA63C3C41.toInt()
            const val PRESSED = 0xF07A7A84.toInt()
            const val DISABLED = 0x663C3C41.toInt()
            const val LABEL = 0xFFFFFFFF.toInt()
            const val LABEL_DISABLED = 0xFF8E8E93.toInt()
        }
    }

    /**
     * Re-measures and repaints when the size or font preference changes, so a settings change is
     * visible on the next keyboard open instead of after a restart.
     */
    fun refreshAppearance() {
        showPage(activePage)
        invalidate()
    }

    companion object {
        private val KEY_HEIGHT_DP = mapOf(
            KeySettings.SIZE_SMALL to 46f,
            KeySettings.SIZE_MEDIUM to 52f,
            KeySettings.SIZE_LARGE to 58f,
        )
        /** Ten percent above the previous 14 / 16 / 18sp key labels. */
        private val GLYPH_SP = mapOf(
            KeySettings.SIZE_SMALL to 15.4f,
            KeySettings.SIZE_MEDIUM to 17.6f,
            KeySettings.SIZE_LARGE to 19.8f,
        )
        private var cachedFlex: android.graphics.Typeface? = null
        private val SYSTEM_TYPEFACE: android.graphics.Typeface = android.graphics.Typeface.create("sans-serif-medium", 0)

        /**
         * The typeface every keyboard surface draws with. Google Sans Flex is the default.
         * System is the only other choice. One place so keys and panels never disagree.
         */
        fun typefaceFor(context: Context): android.graphics.Typeface {
            val choice = VoicePreferences.state.value.keys.keyFont
            return if (choice == KeySettings.FONT_SYSTEM) SYSTEM_TYPEFACE else flexTypeface(context)
        }

        /**
         * Google Sans Flex, bundled under the SIL Open Font License 1.1. Loaded once and cached.
         *
         * Regular weight and optical size 18. A heavier weight reads as bold at key size.
         * The system sans is chained afterwards so a symbol the file does not contain still draws.
         */
        fun flexTypeface(context: Context): android.graphics.Typeface {
            cachedFlex?.let { return it }
            val resolved = loadFlex(context)
            cachedFlex = resolved
            return resolved
        }

        private fun loadFlex(context: Context): android.graphics.Typeface {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val chained = runCatching {
                    val font = android.graphics.fonts.Font.Builder(context.assets, FLEX_ASSET)
                        .setFontVariationSettings("'wght' 400, 'opsz' 18, 'wdth' 100")
                        .build()
                    val family = android.graphics.fonts.FontFamily.Builder(font).build()
                    android.graphics.Typeface.CustomFallbackBuilder(family)
                        .setSystemFallback("sans-serif")
                        .build()
                }.getOrNull()
                if (chained != null) return chained
            }
            return runCatching { android.graphics.Typeface.createFromAsset(context.assets, FLEX_ASSET) }.getOrNull()
                ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        /** Variable Google Sans Flex from google/fonts. The OFL file sits beside it. */
        const val FLEX_ASSET = "fonts/GoogleSansFlex.ttf"

        private const val ROW_HEIGHT_WEIGHT = 0f
        private const val SHIFT_DOUBLE_MS = 400L
        private const val KEY_GAP_DP = 2f
        private const val PUNCT_SHOW_MS = 170L
        private const val PUNCT_SLIDE_DP = 28f
        private const val PUNCT_CELL_DP = 34f
        /** Twenty percent under the previous 1.35 / 1.08 / 48dp / 1.45 preview. */
        private const val PREVIEW_WIDTH_FACTOR = 1.08f
        private const val PREVIEW_HEIGHT_FACTOR = 0.864f
        private const val PREVIEW_MIN_DP = 38.4f
        private const val PREVIEW_TEXT_FACTOR = 1.16f
        private const val BACKSPACE_WEIGHT = 1.4f
        /** Five percent under the previous 6.45, after the period key left the row. */
        private const val SPACE_WEIGHT = 6.13f
        /** The bottom row is the letter-key height times 1.2. Q and the spacebar share that rule. */
        private const val SPACE_ROW_SCALE = 1.2f

        private fun char(c: String) = KeySpec(Key.CHARACTER, c, c, 1f)
        private fun icon(key: Key, description: String, weight: Float, drawable: Int) =
            KeySpec(key, description, "", weight, drawable)
        private fun control(key: Key, label: String, weight: Float) = KeySpec(key, label, "", weight, control = true)

        // Row weights are relative, so each row is normalised by LinearLayout. A light first key
        // inset on the middle letter rows is what a real keyboard does, not an accident.
        private val LAYOUT: List<List<KeySpec>> = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { char(it) },
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { char(it) },
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { char(it) },
            listOf(
                icon(Key.SHIFT, "Shift", 1.5f, R.drawable.odicto_ic_shift),
                char("z"), char("x"), char("c"), char("v"), char("b"), char("n"), char("m"),
                icon(Key.BACKSPACE, "Backspace", 1.5f, R.drawable.odicto_ic_backspace),
            ),
            listOf(
                control(Key.NUMBERS, "123", 1.15f),
                KeySpec(Key.CHARACTER, ",", ",", 1f),
                KeySpec(Key.SPACE, "Space", " ", 6.35f),
                icon(Key.ENTER, "Enter", 1.5f, R.drawable.odicto_ic_enter),
            ),
        )

        private val NUMBERS_LAYOUT: List<List<KeySpec>> = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { char(it) },
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@").map { char(it) },
            listOf(
                control(Key.SYMBOLS, "#+=", 1.5f), char("."), char(","), char("?"), char("!"),
                icon(Key.BACKSPACE, "Backspace", 1.5f, R.drawable.odicto_ic_backspace),
            ),
            bottomRow(Key.LETTERS, "ABC", "'", "+"),
        )

        // The user asked for currency, bullets, and arrows, so they are first on the page rather
        // than buried: ₹ and • are the first entries a writer looks for on a symbols keyboard.
        private val SYMBOLS_LAYOUT: List<List<KeySpec>> = listOf(
            listOf("₹", "€", "£", "¥", "¢", "•", "▪", "‣", "⁃", "◦").map { char(it) },
            listOf("→", "←", "↑", "↓", "↔", "↕", "▶", "◀", "▲", "▼").map { char(it) },
            listOf(
                control(Key.NUMBERS, "123", 1.5f), char("`"), char("¡"), char("𝕏"), char("™"),
                icon(Key.BACKSPACE, "Backspace", 1.5f, R.drawable.odicto_ic_backspace),
            ),
            listOf(
                control(Key.LETTERS, "ABC", 1.2f),
                KeySpec(Key.CHARACTER, "X", "X", 0.72f),
                KeySpec(Key.CHARACTER, "©", "©", 0.72f),
                KeySpec(Key.SPACE, "Space", " ", 6.06f),
                KeySpec(Key.CHARACTER, "°", "°", 0.6f),
                icon(Key.ENTER, "Enter", 0.7f, R.drawable.odicto_ic_enter),
            ),
        )

        /**
         * Bottom row for the symbol pages, which still keep a mark on each side of the spacebar.
         * Weights: 1.35 + 0.9 + 6.13 + 0.62 + 1.0 = 10.
         */
        private fun bottomRow(mode: Key, modeLabel: String, before: String, after: String) = listOf(
            control(mode, modeLabel, 1.35f),
            KeySpec(Key.CHARACTER, before, before, 0.9f),
            KeySpec(Key.SPACE, "Space", " ", SPACE_WEIGHT),
            KeySpec(Key.CHARACTER, after, after, 0.62f),
            icon(Key.ENTER, "Enter", 1f, R.drawable.odicto_ic_enter),
        )
    }
}
