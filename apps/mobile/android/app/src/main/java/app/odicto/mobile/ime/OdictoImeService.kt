package app.odicto.mobile.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import app.odicto.mobile.dictation.*
import app.odicto.mobile.overlay.*
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.*

class OdictoImeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var control: VoiceControlView? = null
    private var session: Long? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    private var liveInsertion: LiveInsertion? = null
    private var liveEditor: Long? = null
    private var editingRow: LinearLayout? = null
    private var backspaceKey: RepeatKeyButton? = null
    override fun onCreate() {
        super.onCreate(); VoicePreferences.initialize(this); DictationCoordinator.registerInserter(::insertIfCurrent)
        DictationCoordinator.registerSelectionReader(::readSelection)
        DictationCoordinator.registerLive(::beginLive, ::writeLive, ::endLive)
        scope.launch { VoiceSession.state.collect { state ->
            if (DictationCoordinator.keyboardVisible.value) control?.render(state)
            editingRow?.let { row ->
                if (row.isEnabled == state.busy) {
                    row.isEnabled = !state.busy
                    if (state.busy) backspaceKey?.stopRepeating()
                    for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = !state.busy
                }
            }
        } }
        scope.launch { VoicePreferences.state.collect { if (DictationCoordinator.keyboardVisible.value) control?.render(VoiceSession.state.value) } }
    }
    override fun onDestroy() { DictationCoordinator.keyboardVisible.value = false; DictationCoordinator.unregisterInserter(); scope.cancel(); super.onDestroy() }
    override fun onWindowShown() { super.onWindowShown(); DictationCoordinator.keyboardVisible.value = true; control?.render(VoiceSession.state.value) }
    override fun onWindowHidden() { DictationCoordinator.keyboardVisible.value = false; backspaceKey?.stopRepeating(); super.onWindowHidden() }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View {
        backspaceKey?.stopRepeating()
        val column = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(640)), View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        control = VoiceControlView(this, true).also {
            column.addView(it, LinearLayout.LayoutParams(it.requiredWidth, it.requiredHeight))
            it.resize = { width, height -> it.layoutParams = LinearLayout.LayoutParams(width, height) }
            it.visibility = if (session != null) View.VISIBLE else View.GONE
        }
        editingRow = LinearLayout(this).also { row ->
            row.isEnabled = !VoiceSession.state.value.busy
            // Mixed font sizes must not shift key backgrounds to align text baselines.
            row.isBaselineAligned = false
            row.setPadding(dp(12), dp(8), dp(12), dp(6))
            listOf("⌫" to "Backspace", "," to "Comma", "Space" to "Space", "." to "Full stop", "↵" to "New line").forEach { (label, description) ->
                val key = when (description) {
                    "Backspace" -> RepeatKeyButton(this).also { backspaceKey = it }
                    "New line" -> EditingKeyButton(this, "enter")
                    else -> Button(this)
                }
                row.addView(key.apply {
                    text = if (key is EditingKeyButton) "" else label
                    contentDescription = description; minWidth = 0; minimumWidth = 0
                    minHeight = 0; minimumHeight = 0; includeFontPadding = false
                    gravity = android.view.Gravity.CENTER
                    isAllCaps = false; textSize = if (description == "Space") 16f else 22f
                    setTextColor(0xFFF3EFFB.toInt()); stateListAnimator = null
                    backgroundTintList = null
                    background = keyBackground()
                    setPadding(0, 0, 0, 0); isEnabled = !VoiceSession.state.value.busy
                    setOnClickListener { feedback(it); edit(description) }
                }, LinearLayout.LayoutParams(0, dp(50), if (description == "Space") 2f else 1f).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                })
            }
            row.visibility = if (session != null) View.VISIBLE else View.GONE
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(Button(this).apply {
            text = "Switch keyboard"; isAllCaps = false; textSize = 13f
            setTextColor(0xFFBDB5CE.toInt()); backgroundTintList = null
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            setOnClickListener { feedback(it); backspaceKey?.stopRepeating(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        return FrameLayout(this).apply {
            setBackgroundColor(0xFF111116.toInt())
            addView(column, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER_HORIZONTAL))
        }
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        backspaceKey?.stopRepeating()
        liveInsertion?.stop(); liveInsertion = null; liveEditor = null
        selectionStart = attribute?.initialSelStart ?: -1; selectionEnd = attribute?.initialSelEnd ?: -1
        if (EditorPolicy.permits(attribute) && currentInputConnection != null) session = DictationCoordinator.editorReady()
        else { session = null; DictationCoordinator.editorClosed(true); if (VoiceSession.state.value.active) VoiceSession.cancel(this) }
        control?.visibility = if (session != null) View.VISIBLE else View.GONE
        editingRow?.visibility = if (session != null) View.VISIBLE else View.GONE
        control?.render(VoiceSession.state.value)
        try { startService(Intent(this, VoiceOverlayService::class.java).setAction(VoiceOverlayService.ACTION_SHOW)) } catch (_: Exception) {}
    }
    override fun onFinishInput() {
        backspaceKey?.stopRepeating()
        endLive()
        session = null; DictationCoordinator.editorClosed(); control?.visibility = View.GONE
        editingRow?.visibility = View.GONE
        if (VoiceSession.state.value.active) VoiceSession.finish(this)
        super.onFinishInput()
    }
    fun insertIfCurrent(editorSession: Long, text: String): Boolean {
        if (session != editorSession || !DictationCoordinator.canInsert(editorSession) || currentInputConnection == null || !EditorPolicy.permits(currentInputEditorInfo)) return false
        // Finish composing first so commitText replaces the selection, not an old composing span.
        val target = DictationCoordinator.aiSelection
        if (target != null && !currentInputConnection.finishComposingText()) return false
        if (target != null && !target.matches(readSelection())) return false
        return currentInputConnection.commitText(text, 1)
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart; selectionEnd = newSelEnd
        DictationCoordinator.selectionUpdated(newSelStart, newSelEnd)
    }
    private fun readSelection(): AiSelection? {
        if (session == null || !EditorPolicy.permits(currentInputEditorInfo)) return null
        val connection = currentInputConnection ?: return null
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val selected = connection.getSurroundingText(0, 0, 0)
                if (selected == null || selected.offset < 0) {
                    return AiSelection(selectionStart, selectionEnd, connection.getSelectedText(0)?.toString().orEmpty()).takeIf { it.valid }
                }
                val start = selected.selectionStart; val end = selected.selectionEnd
                AiSelection(selected.offset + start, selected.offset + end,
                    selected.text.subSequence(minOf(start, end), maxOf(start, end)).toString()).takeIf { it.valid }
            } else {
                AiSelection(selectionStart, selectionEnd, connection.getSelectedText(0)?.toString().orEmpty()).takeIf { it.valid }
            }
        } catch (_: Exception) { null }
    }
    private fun beginLive(): Boolean {
        val initial = readSelection() ?: return false
        if (currentInputConnection?.finishComposingText() != true) return false
        liveInsertion = LiveInsertion(initial); liveEditor = session
        return true
    }
    private fun writeLive(text: String, finish: Boolean): Boolean {
        if (session == null || session != liveEditor || !EditorPolicy.permits(currentInputEditorInfo)) return false
        val writer = liveInsertion ?: return false
        val connection = currentInputConnection ?: return false
        return try {
            val preceding = if (writer.text.isEmpty()) "" else connection.getTextBeforeCursor(writer.text.length, 0)?.toString().orEmpty()
            val ok = writer.update(readSelection(), preceding, text) { connection.setComposingText(it, 1) }
            ok && (!finish || connection.finishComposingText())
        } catch (_: Exception) { writer.stop(); false }
    }
    private fun endLive() {
        if (liveInsertion != null && session != null && session == liveEditor) {
            try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
        }
        liveInsertion?.stop(); liveInsertion = null; liveEditor = null
    }
    private fun edit(key: String) {
        if (session == null || VoiceSession.state.value.busy || !EditorPolicy.permits(currentInputEditorInfo)) return
        val connection = currentInputConnection ?: return
        try {
            if (!connection.finishComposingText()) return
            when (key) {
                "Backspace" -> {
                    if (!connection.getSelectedText(0).isNullOrEmpty()) connection.commitText("", 1)
                    else if (android.os.Build.VERSION.SDK_INT >= 24) connection.deleteSurroundingTextInCodePoints(1, 0)
                    else {
                        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                        val count = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
                        connection.deleteSurroundingText(count, 0)
                    }
                }
                "Space" -> connection.commitText(" ", 1)
                "Comma" -> connection.commitText(",", 1)
                "Full stop" -> connection.commitText(".", 1)
                "New line" -> connection.commitText("\n", 1)
            }
        } catch (_: Exception) { Toast.makeText(this, "Editor unavailable", Toast.LENGTH_SHORT).show() }
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        backspaceKey?.stopRepeating(); super.onFinishInputView(finishingInput)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun feedback(view: View) { if (VoicePreferences.state.value.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    private fun keyBackground(): android.graphics.drawable.Drawable {
        fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF403A4C.toInt())
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(0xFF19171F.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF55466F.toInt()))
            addState(intArrayOf(), shape(0xFF292530.toInt()))
        }
    }
}
