package app.odicto.mobile.ime

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.odicto.mobile.BuildConfig
import app.odicto.mobile.R
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.overlay.VoiceControlView
import app.odicto.mobile.storage.VoicePreferences
import kotlin.math.abs

/**
 * Owns the Odicto keyboard: which page is showing, what each key does to the editor, and when a
 * capability is allowed to act.
 *
 * All editor mutation goes through here so one place finishes composing text before a write. Cursor
 * gestures are refused while a voice request is running or an AI selection is captured: moving the
 * cursor mid-request would invalidate the selection the answer is meant to replace and would leave a
 * stale composing range behind during Live typing.
 */
class KeyboardController(
    private val service: OdictoImeService,
    private val root: View,
    private val connectionProvider: () -> InputConnection? = { service.currentInputConnection },
) {
    private val context: Context = root.context
    private val keyboard: OdictoKeyboardView = root.findViewById(R.id.ime_keyboard)
    private val functionRow: LinearLayout = root.findViewById(R.id.function_row)
    private val emojiPanel: LinearLayout = root.findViewById(R.id.emoji_panel)
    private val clipboardPanel: LinearLayout = root.findViewById(R.id.clipboard_panel)
    private val noticeBanner: TextView = root.findViewById(R.id.ime_notice)
    private val handler = Handler(Looper.getMainLooper())
    private val clipboard = ClipboardPanel(context)
    private var editGeneration = 0L
    private var heldLetter: HeldLetter? = null
    private data class HeldLetter(val spec: OdictoKeyboardView.KeySpec, val connection: InputConnection, val end: Int, val text: String, val generation: Long)

    private var editorInfo: EditorInfo? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    private var cursorWindow: Pair<String, Int>? = null
    private var cursorConnection: InputConnection? = null
    private var cursorMovedAt = 0L
    private val cursorAcknowledgments = ArrayDeque<Int>()
    private var voiceControl: VoiceControlView? = null
    private var lastNotice = 0L
    private var lastWasSpace = false
    private var emojiPage: String = PAGE_RECENT
    private var layoutMode = MODE_DOCKED
    private var floatOffset = 0f
    private val editorTail = EditorTail()
    private var enterKind = ImeActions.Kind.NEWLINE
    private var deleteBurst = false
    private var deleteConnection: InputConnection? = null
    private val editorSession = EditorSessionState()
    private val deleteRepeat = DeleteRepeatCoordinator()
    /** Null until a cursor move. A typing run then needs no read of the field. */
    private var sentenceCap: Boolean? = null
    private val hideNoticeTask = Runnable { hideNotice() }

    init {
        keyboard.setOnKeyListener(::onKey)
        keyboard.setUppercaseChoiceListener(::uppercaseChoice)
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                keyboard.cancelTouch()
                applyLayout()
            }
        }
        keyboard.setBackspaceReleaseListener(::finishDeleteBurst)
        keyboard.setBackspaceRepeatGate(::prepareDeleteRepeat)
        keyboard.setDragListener(::onSpaceDrag) { gestureAllowed() }
        functionRow.findViewById<ImageButton>(R.id.function_mic).setOnClickListener { toggleVoice() }
        functionRow.findViewById<ImageButton>(R.id.function_emoji).setOnClickListener { toggleEmoji() }
        functionRow.findViewById<ImageButton>(R.id.function_clipboard).setOnClickListener { toggleClipboard() }
        functionRow.findViewById<ImageButton>(R.id.function_switch).setOnClickListener { switchKeyboard() }
        functionRow.findViewById<ImageButton>(R.id.function_layout).setOnClickListener { cycleLayout() }
        functionRow.findViewById<View>(R.id.function_grip).setOnTouchListener { _, event -> dragFloating(event) }
        show(OdictoKeyboardView.Page.LETTERS)
    }

    fun attachVoiceControl(view: VoiceControlView) {
        voiceControl = view
        if (view.parent == null) {
            root.findViewById<android.widget.FrameLayout>(R.id.ime_top_row).addView(view, 0,
                android.widget.FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight, android.view.Gravity.START))
        }
        syncMicButton()
    }

    /** The voice strip already has a microphone. A second one in the toolbar is the duplicate. */
    private fun syncMicButton() {
        val mic = functionRow.findViewById<View>(R.id.function_mic) ?: return
        val stripVisible = voiceControl?.visibility == View.VISIBLE
        mic.visibility = if (stripVisible) View.GONE else View.VISIBLE
    }

    fun onStartInput(info: EditorInfo?) {
        keyboard.cancelTouch()
        resetCursorWindow()
        invalidateHeldLetter()
        deleteBurst = false
        editorInfo = info
        selectionStart = info?.initialSelStart ?: -1
        selectionEnd = info?.initialSelEnd ?: -1
        editorSession.reset(connectionProvider(), selectionStart, selectionEnd)
        // A protected field keeps the whole function row, dimmed where a capability is refused. A
        // hidden or disabled button that does nothing when tapped was reported as a broken feature;
        // a dimmed one explains itself in the banner instead.
        capability(functionRow.findViewById(R.id.function_mic), EditorPolicy.permits(info))
        capability(functionRow.findViewById(R.id.function_emoji), EditorPolicy.emojiPermits(info))
        capability(functionRow.findViewById(R.id.function_clipboard), EditorPolicy.clipboardPermits(info))
        keyboard.setShifted(false)
        editorTail.clear()
        sentenceCap = if (EditorPolicy.typingAssistPermits(info)) null else false
        enterKind = ImeActions.resolve(info)
        keyboard.setEnterAction(enterKind, ImeActions.displayLabel(info, enterKind))
        show(OdictoKeyboardView.Page.LETTERS)
        closePanels()
        hideNotice()
        syncMicButton()
    }

    fun onFinishInput() {
        editorInfo = null
        keyboard.cancelTouch()
        finishDeleteBurst()
        editorSession.reset(null, -1, -1)
        handler.removeCallbacks(hideNoticeTask)
        resetCursorWindow()
        invalidateHeldLetter()
        deleteBurst = false
        editorTail.clear()
        sentenceCap = null
        closePanels()
    }

    fun onSelectionChanged(start: Int, end: Int) = onSelectionChanged(-1, -1, start, end, -1, -1)

    fun onSelectionChanged(oldStart: Int, oldEnd: Int, start: Int, end: Int, composingStart: Int, composingEnd: Int) {
        editorSession.composingStart = composingStart
        editorSession.composingEnd = composingEnd
        if (start == end && cursorAcknowledgments.contains(start) && SystemClock.uptimeMillis() - cursorMovedAt <= 500) {
            while (cursorAcknowledgments.isNotEmpty()) {
                if (cursorAcknowledgments.removeFirst() == start) break
            }
            return
        }
        resetCursorWindow()
        if (start != end) {
            onExternalEdit(start, end)
            return
        }
        if (editorSession.locateUnknownDelete(start, end)) {
            selectionStart = start
            selectionEnd = end
            return
        }
        if (editorSession.acknowledgeTyping(oldStart, oldEnd, start, end)) return
        if (editorSession.plausible(start, end)) {
            if (start != selectionStart || end != selectionEnd ||
                oldStart >= 0 && !editorSession.plausible(oldStart, oldEnd)) editorSession.ambiguous = true
            return
        }
        onExternalEdit(start, end)
    }

    fun stopRepeating() { keyboard.stopRepeating(); finishDeleteBurst() }
    fun cancelTouch() { keyboard.cancelTouch(); finishDeleteBurst() }
    fun dispose() { onFinishInput(); voiceControl = null }

    fun onExternalEdit(start: Int, end: Int) {
        cancelTouch()
        editorSession.reset(connectionProvider(), start, end)
        invalidateHeldLetter()
        resetCursorWindow()
        editorTail.clear()
        sentenceCap = null
        selectionStart = start
        selectionEnd = end
    }

    private fun onKey(spec: OdictoKeyboardView.KeySpec) {
        invalidateHeldLetter()
        resetCursorWindow()
        val connection = connectionProvider()
        if (spec.key == OdictoKeyboardView.Key.BACKSPACE && spec.repeatOrdinal > 0 &&
            (!deleteBurst || connection == null || connection !== deleteConnection)) {
            keyboard.cancelTouch()
            finishDeleteBurst()
            return
        }
        if (connection == null) return
        if (editorSession.connection !== connection) {
            onExternalEdit(-1, -1)
        }
        when (spec.key) {
            OdictoKeyboardView.Key.CHARACTER, OdictoKeyboardView.Key.SPACE,
            OdictoKeyboardView.Key.ENTER, OdictoKeyboardView.Key.BACKSPACE -> service.cancelFieldPolish()
            else -> Unit
        }
        val assist = EditorPolicy.typingAssistPermits(editorInfo)
        if (spec.key != OdictoKeyboardView.Key.BACKSPACE && deleteBurst) deleteRepeat.waitForEvidence()
        when (spec.key) {
            OdictoKeyboardView.Key.CHARACTER -> {
                var text = spec.output
                if (assist && text.length == 1 && text[0].isLowerCase()) {
                    val cap = sentenceCap ?: TypingAssist.shouldAutoCap(
                        editorTail.textBefore(16) { readBefore(connection, TAIL) },
                    )
                    if (cap) text = text.uppercase()
                }
                write(connection, text, spec)
                sentenceCap = if (assist) when {
                    text.endsWith("\n") || text.endsWith(". ") || text.endsWith("! ") || text.endsWith("? ") -> true
                    text.lastOrNull()?.let { it.isLetter() || it.isDigit() } == true -> false
                    else -> null
                } else false
                lastWasSpace = false
            }
            OdictoKeyboardView.Key.SPACE -> {
                val before = if (assist) editorTail.textBefore(8) { readBefore(connection, TAIL) } else ""
                if (lastWasSpace && TypingAssist.periodForSecondSpace(before)) {
                    deleteBackwards(connection)
                    write(connection, ". ")
                    sentenceCap = true
                } else {
                    write(connection, " ")
                }
                lastWasSpace = true
            }
            OdictoKeyboardView.Key.ENTER -> {
                        if (enterKind != ImeActions.Kind.NEWLINE && connection.performEditorAction(enterKind.code)) {
                    editorTail.clear()
                    sentenceCap = if (assist) null else false
                } else {
                    write(connection, "\n")
                    sentenceCap = assist
                }
                lastWasSpace = false
            }
            OdictoKeyboardView.Key.BACKSPACE -> {
                if (spec.repeatOrdinal == 0) {
                    finishDeleteBurst()
                    deleteConnection = connection
                    deleteBurst = true
                    deleteRepeat.start(editorSession.epoch)
                }
                sentenceCap = if (assist) null else false
                deleteBackwards(connection, spec.repeatOrdinal)
                lastWasSpace = false
            }
            OdictoKeyboardView.Key.SHIFT -> keyboard.cycleShift()
            OdictoKeyboardView.Key.NUMBERS -> show(OdictoKeyboardView.Page.NUMBERS)
            OdictoKeyboardView.Key.SYMBOLS -> show(OdictoKeyboardView.Page.SYMBOLS)
            OdictoKeyboardView.Key.LETTERS -> show(OdictoKeyboardView.Page.LETTERS)
            OdictoKeyboardView.Key.SWITCH -> switchKeyboard()
        }
    }

    private fun write(connection: InputConnection, text: String, spec: OdictoKeyboardView.KeySpec? = null) {
        if (editorSession.ambiguous && !refreshEditor(connection)) return
        // A voice composing span must be closed or this commit replaces the dictated word.
        // Ordinary typing has no span, and asking the field to finish one is a round trip per letter.
        val replacing = selectionStart >= 0 && selectionEnd >= 0 && selectionEnd != selectionStart
        val at = if (replacing) minOf(selectionStart, selectionEnd) else selectionStart
        if (VoiceSession.state.value.busy || editorSession.composingStart >= 0) {
            runCatching { connection.finishComposingText() }
            editorSession.composingStart = -1
            editorSession.composingEnd = -1
        }
        val measure = BuildConfig.DEBUG && spec?.key == OdictoKeyboardView.Key.CHARACTER && spec.touchAtNanos > 0
        val commitStart = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        val accepted = runCatching { connection.commitText(text, 1) }.getOrDefault(false)
        val commitEnd = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        if (!accepted) {
                editorTail.clear()
            selectionStart = -1
            selectionEnd = -1
            return
        }
        if (measure) {
            KeyLatencyProbe.commit(spec.touchAtNanos, spec.hapticAtNanos, commitStart, commitEnd)
        }
        val cursor = if (!EditorPolicy.typingAssistPermits(editorInfo)) {
            editorTail.clear()
            if (at >= 0) at + text.length else null
        } else if (replacing) editorTail.afterReplace(at, text) else editorTail.afterInsert(if (selectionStart == selectionEnd) at else -1, text)
        editorSession.submitted(EditorSessionState.Snapshot(cursor ?: -1, cursor ?: -1, editorTail.knownSuffix(), editorTail.complete), 0, false, SystemClock.uptimeMillis())
        if (cursor != null) {
            selectionStart = cursor
            selectionEnd = cursor
            if (spec?.key == OdictoKeyboardView.Key.CHARACTER && text.length == 1 && text[0].isLowerCase()) {
                heldLetter = HeldLetter(spec, connection, cursor, text, editGeneration)
            }
        }
    }

    private fun readBefore(connection: InputConnection, limit: Int): String? {
        if (editorSession.pending) return editorTail.knownSuffix()
        val epoch = editorSession.epoch
        val value = runCatching { connection.getTextBeforeCursor(limit, 0)?.toString() }.getOrNull() ?: return null
        if (connectionProvider() !== connection || editorSession.epoch != epoch) return null
        editorTail.seed(value, selectionStart)
        editorSession.seed(value, editorTail.complete)
        return value
    }

    private fun refreshEditor(connection: InputConnection): Boolean {
        if (!EditorPolicy.typingAssistPermits(editorInfo)) return false
        val epoch = editorSession.epoch
        var start = -1
        var end = -1
        var before: String? = null
        if (!editorSession.metadataUnsupported) {
            if (Build.VERSION.SDK_INT >= 31) {
                val snapshot = runCatching { connection.getSurroundingText(TAIL, 0, 0) }.getOrNull()
                if (snapshot != null && snapshot.offset >= 0) {
                    start = snapshot.offset + snapshot.selectionStart
                    end = snapshot.offset + snapshot.selectionEnd
                    before = snapshot.text.take(minOf(snapshot.selectionStart, snapshot.selectionEnd)).toString()
                }
            } else {
                val snapshot = runCatching { connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = TAIL }, 0) }.getOrNull()
                if (snapshot != null && snapshot.startOffset >= 0) {
                    start = snapshot.startOffset + snapshot.selectionStart
                    end = snapshot.startOffset + snapshot.selectionEnd
                }
            }
        }
        if (before == null) before = runCatching { connection.getTextBeforeCursor(TAIL, 0)?.toString() }.getOrNull()
        if (connectionProvider() !== connection || editorSession.epoch != epoch) {
            cancelTouch()
            return false
        }
        val value = before ?: return false
        if (start >= 0 && start != end) {
            onExternalEdit(start, end)
            return false
        }
        val result = editorSession.observe(EditorSessionState.Snapshot(start, end, value, start >= 0 && start == value.length))
        when (result) {
            EditorSessionState.Evidence.INCOMPATIBLE -> { onExternalEdit(start, end); return false }
            EditorSessionState.Evidence.WAITING -> return false
            EditorSessionState.Evidence.CURRENT -> {
                val cursor = if (start >= 0) start else selectionStart
                editorTail.seed(value, cursor)
                selectionStart = cursor
                selectionEnd = cursor
            }
            EditorSessionState.Evidence.PROGRESS -> Unit
        }
        deleteRepeat.freshProgress()
        return true
    }

    private fun prepareDeleteRepeat(): Boolean {
        val connection = connectionProvider()
        if (!deleteBurst || connection == null || connection !== deleteConnection || editorSession.epoch != deleteRepeat.epoch) {
            cancelTouch()
            return false
        }
        val now = SystemClock.uptimeMillis()
        val assist = EditorPolicy.typingAssistPermits(editorInfo)
        val word = assist && BackspacePace.removesWord(deleteRepeat.nextOrdinal)
        val suffix = editorTail.knownSuffix()
        if (!deleteRepeat.hasCredit(editorSession, word, now) || assist && (suffix.isNullOrEmpty() || !editorTail.complete && suffix.length < 32)) {
            deleteRepeat.waitForEvidence()
            if (!deleteRepeat.canRead(now) || !refreshEditor(connection)) return false
        }
        if (!deleteBurst || editorSession.epoch != deleteRepeat.epoch || !deleteRepeat.hasCredit(editorSession, word, now)) return false
        if (!assist) {
            deleteRepeat.ready(-1)
            return true
        }
        val before = editorTail.knownSuffix() ?: return false
        if (before.isEmpty()) {
            if (editorTail.complete) deleteRepeat.boundary() else deleteRepeat.waitForEvidence()
            return false
        }
        val requested = BackspacePace.charCountToDelete(before, deleteRepeat.nextOrdinal)
        val count = if (!editorTail.complete && requested == before.length) BackspacePace.charCountToDelete(before, 0) else requested
        deleteRepeat.ready(count)
        return count > 0
    }

    private fun deleteBackwards(connection: InputConnection, ordinal: Int = 0) {
        val assist = EditorPolicy.typingAssistPermits(editorInfo)
        var nativeTap = false
        if (ordinal == 0) {
            if (VoiceSession.state.value.busy || editorSession.composingStart >= 0) {
                runCatching { connection.finishComposingText() }
                editorSession.composingStart = -1
                editorSession.composingEnd = -1
            }
            val selected = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
            if (selected) {
                val cursor = minOf(selectionStart, selectionEnd)
                val accepted = runCatching { connection.commitText("", 1) }.getOrDefault(false)
                if (accepted) {
                    editorTail.afterReplace(cursor, "")
                    selectionStart = cursor; selectionEnd = cursor
                    editorSession.submitted(EditorSessionState.Snapshot(cursor, cursor, null), 1, false, SystemClock.uptimeMillis())
                } else cancelTouch()
                return
            }
            if (assist) {
                val epoch = editorSession.epoch
                val ready = if (editorSession.pending || editorSession.ambiguous) refreshEditor(connection)
                    else readBefore(connection, TAIL) != null
                if (connectionProvider() !== connection || editorSession.epoch != epoch) {
                    cancelTouch()
                    return
                }
                if (!ready || !deleteRepeat.hasCredit(editorSession, false, SystemClock.uptimeMillis()) ||
                    editorTail.knownSuffix().isNullOrEmpty() && !editorTail.complete) {
                    nativeTap = true
                }
            }
        }
        if (ordinal > 0 && deleteRepeat.preparedCount == 0) return
        val before = editorTail.knownSuffix()
        val count = if (ordinal > 0) deleteRepeat.preparedCount else if (nativeTap || before.isNullOrEmpty() && !editorTail.complete) -1 else before?.let { BackspacePace.charCountToDelete(it, 0) } ?: -1
        if (count == 0) return
        val accepted = runCatching {
            if (count < 0 && Build.VERSION.SDK_INT >= 24) connection.deleteSurroundingTextInCodePoints(1, 0)
            else connection.deleteSurroundingText(count.coerceAtLeast(1), 0)
        }.getOrDefault(false)
        if (!accepted) {
            cancelTouch()
            editorTail.clear()
            editorSession.reset(connection, -1, -1)
            selectionStart = -1; selectionEnd = -1
            return
        }
        val cursor = if (count > 0) editorTail.afterDelete(if (selectionStart == selectionEnd) selectionStart else -1, count) else null
        if (count < 0) editorTail.clear()
        selectionStart = cursor ?: -1; selectionEnd = cursor ?: -1
        editorSession.submitted(EditorSessionState.Snapshot(selectionStart, selectionEnd, editorTail.knownSuffix(), editorTail.complete), count.coerceAtLeast(1), BackspacePace.removesWord(ordinal), SystemClock.uptimeMillis())
        if (ordinal > 0) deleteRepeat.dispatched()
    }

    private fun finishDeleteBurst() {
        deleteBurst = false
        deleteConnection = null
        deleteRepeat.stop()
    }

    private fun onSpaceDrag(horizontal: Int, vertical: Int) {
        if (!gestureAllowed() || (horizontal == 0 && vertical == 0)) return
        if (!EditorPolicy.typingAssistPermits(editorInfo)) {
            notice("Cursor gestures are off in this field. You can still type.")
            return
        }
        val connection = connectionProvider() ?: return
        if (selectionStart < 0 || selectionEnd < 0) return
        service.cancelFieldPolish()
        if (selectionStart != selectionEnd) {
            val edge = if (horizontal < 0 || vertical < 0) selectionStart else selectionEnd
            if (connection.setSelection(edge, edge)) {
                selectionStart = edge
                selectionEnd = edge
                editorSession.reset(connection, edge, edge)
                editorTail.clear()
            }
            return
        }
        val result = if (horizontal != 0) {
            val (text, offset) = surroundingText(connection) ?: return
            CursorNavigator.characterInWindow(text, offset, selectionStart, horizontal)
        } else {
            val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_DOCUMENT }, 0)
            val text = extracted?.text?.toString()
            if (extracted == null || extracted.startOffset != 0 || extracted.partialStartOffset != -1 || text == null || text.length > MAX_DOCUMENT) {
                notice("This field does not support cursor jumping.")
                return
            }
            val edge = if (vertical > 0) text.length else 0
            edge to edge
        } ?: return
        val started = SystemClock.elapsedRealtimeNanos()
        if (runCatching { connection.setSelection(result.first, result.second) }.getOrDefault(false)) {
            selectionStart = result.first
            selectionEnd = result.second
            editorSession.reset(connection, selectionStart, selectionEnd)
            cursorMovedAt = SystemClock.uptimeMillis()
            cursorAcknowledgments.addLast(result.first)
            while (cursorAcknowledgments.size > 64) cursorAcknowledgments.removeFirst()
            editorTail.clear()
            sentenceCap = null
            if (BuildConfig.DEBUG) KeyLatencyProbe.cursor(started, SystemClock.elapsedRealtimeNanos())
        } else {
            resetCursorWindow()
        }
    }

    private fun gestureAllowed(): Boolean =
        !VoiceSession.state.value.busy && !service.isPolishBusy() && DictationCoordinator.aiSelection == null && EditorPolicy.typingAssistPermits(editorInfo)

    private fun invalidateHeldLetter() {
        editGeneration++
        heldLetter = null
    }

    private fun uppercaseChoice(spec: OdictoKeyboardView.KeySpec): (() -> Boolean)? {
        val letter = heldLetter?.takeIf { it.spec === spec } ?: return null
        if (!EditorPolicy.typingAssistPermits(editorInfo) || letter.text.length != 1 || !letter.text[0].isLowerCase()) return null
        return accept@{
            if (heldLetter !== letter || editGeneration != letter.generation || connectionProvider() !== letter.connection ||
                selectionStart != letter.end || selectionEnd != letter.end || !gestureAllowed()) return@accept false
                val current = runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    val read = letter.connection.getSurroundingText(1, 0, 0) ?: return@runCatching false
                    read.offset >= 0 && read.offset + read.selectionStart == letter.end && read.selectionStart == read.selectionEnd &&
                        read.text.take(read.selectionStart).endsWith(letter.text)
                } else {
                    val read = letter.connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 2 }, 0)
                    read != null && read.startOffset + read.selectionStart == letter.end && read.selectionStart == read.selectionEnd &&
                        letter.connection.getTextBeforeCursor(1, 0)?.toString() == letter.text
                }
            }.getOrDefault(false)
            if (!current) return@accept false
            invalidateHeldLetter()
            val connection = letter.connection
            val batched = connection.beginBatchEdit()
            val accepted = try {
                connection.setSelection(letter.end - 1, letter.end) && connection.commitText(letter.text.uppercase(), 1)
            } catch (_: Exception) { false }
            finally { if (batched) connection.endBatchEdit() }
            if (accepted) onExternalEdit(letter.end, letter.end)
            accepted
        }
    }

    private fun resetCursorWindow() {
        cursorWindow = null
        cursorConnection = null
        cursorAcknowledgments.clear()
    }

    private fun surroundingText(connection: InputConnection): Pair<String, Int>? {
        val cached = cursorWindow
        if (cursorConnection === connection && cached != null && SystemClock.uptimeMillis() - cursorMovedAt < 250 &&
            selectionStart > cached.second && selectionStart < cached.second + cached.first.length) return cached
        val before = runCatching { connection.getTextBeforeCursor(SURROUNDING, 0)?.toString() }.getOrNull() ?: return null
        val after = runCatching { connection.getTextAfterCursor(SURROUNDING, 0)?.toString() }.getOrNull() ?: return null
        val offset = selectionStart - before.length
        if (offset < 0) return null
        return ((before + after) to offset).also {
            cursorWindow = it
            cursorConnection = connection
        }
    }

    private fun show(next: OdictoKeyboardView.Page) {
        keyboard.stopRepeating()
        keyboard.showPage(next)
    }

    private fun toggleVoice() {
        service.cancelFieldPolish()
        val reason = EditorPolicy.voiceRefusal(editorInfo)
        if (reason != null) { notice(reason); return }
        val control = voiceControl ?: return
        if (VoiceSession.state.value.active) VoiceSession.finish(context) else if (!VoiceSession.state.value.busy) VoiceSession.start(context, true)
        control.render(VoiceSession.state.value)
    }

    private fun toggleEmoji() {
        if (emojiPanel.visibility == View.VISIBLE) { closePanels(); return }
        val info = service.currentInputEditorInfo ?: editorInfo
        editorInfo = info
        val reason = EditorPolicy.emojiRefusal(info, service.currentInputConnection != null)
        if (reason != null) { notice(reason); return }
        closePanels()
        buildEmojiPanel()
        showPanel(emojiPanel)
    }

    private fun toggleClipboard() {
        if (clipboardPanel.visibility == View.VISIBLE) { closePanels(); return }
        val reason = EditorPolicy.clipboardRefusal(editorInfo)
        if (reason != null) { notice(reason); return }
        closePanels()
        // The panel always opens, including when the clipboard is empty. Showing "nothing copied" as
        // a floating notice instead put the message somewhere the user could not connect to this button.
        buildClipboardPanel()
        showPanel(clipboardPanel)
    }

    /**
     * A panel takes the place of the letter grid instead of being stacked below it. The IME window is
     * capped near half the screen, so appending a panel put it outside the visible region and the
     * feature looked broken. The grid goes INVISIBLE rather than GONE so the slot keeps its height
     * and the panel fills exactly the space the keys occupied. The function row stays visible so its
     * buttons remain a toggle.
     */
    private fun showPanel(panel: View) {
        val slot = root.findViewById<View>(R.id.ime_content)
        val height = keyboard.height.coerceAtLeast(keyboard.measuredHeight)
        // A match_parent panel inside a wrap_content slot measures as nothing, so the clipboard
        // and emoji views were in the tree and still invisible. Pin the slot to the key grid.
        if (height > 0 && slot.layoutParams != null) {
            slot.layoutParams = slot.layoutParams.apply { this.height = height }
        }
        keyboard.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
        panel.bringToFront()
    }

    private fun closePanels() {
        val slot = root.findViewById<View>(R.id.ime_content)
        if (slot.layoutParams != null) {
            slot.layoutParams = slot.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        keyboard.visibility = View.VISIBLE
        emojiPanel.visibility = View.GONE
        emojiPanel.removeAllViews()
        clipboardPanel.visibility = View.GONE
        clipboardPanel.removeAllViews()
        clipboard.close()
    }

    /**
     * Builds the emoji panel with a persistent favourites row on top.
     *
     * A favourites row that only appears on long-press would be invisible until discovered and easy
     * to trigger by accident, so the five pins are always shown and long-press is used only to pin
     * or unpin.
     */
    private fun buildEmojiPanel() {
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        column.addView(emojiFilters())
        if (emojiPage == PAGE_RECENT) {
            val keys = VoicePreferences.state.value.keys
            if (keys.pinnedEmoji.isNotEmpty()) {
                column.addView(sectionLabel("Pinned"))
                column.addView(emojiRow(keys.pinnedEmoji))
                column.addView(divider())
            }
            column.addView(sectionLabel("Recent"))
            val recent = keys.recentEmoji.ifEmpty { EmojiCatalog.recent }.take(EmojiCatalog.PAGE_SIZE)
            column.addView(emojiRow(recent))
        } else {
            val page = EmojiCatalog.categories.firstOrNull { it.label == emojiPage }
            if (page != null) column.addView(emojiRow(page.emoji.take(EmojiCatalog.PAGE_SIZE)))
        }
        scroll.addView(column)
        emojiPanel.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun sectionLabel(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 11f
        typeface = OdictoKeyboardView.typefaceFor(context)
        setTextColor(0xFFB7B7C2.toInt())
        setPadding(dp(8), dp(8), dp(8), dp(4))
    }

    private fun emojiFilters(): View {
        val row = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val choices = listOf(PAGE_RECENT) + EmojiCatalog.categories.map { it.label }
        for (choice in choices) {
            val selected = choice == emojiPage
            line.addView(TextView(context).apply {
                text = choice
                textSize = 13f
                typeface = OdictoKeyboardView.typefaceFor(context)
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF8E8E98.toInt())
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    emojiPage = choice
                    emojiPanel.removeAllViews()
                    buildEmojiPanel()
                }
            })
        }
        row.addView(line)
        return row
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
            marginStart = dp(8)
            marginEnd = dp(8)
        }
    }

    private fun emojiRow(entries: List<String>): View {
        val grid = GridLayout(context).apply { columnCount = GRID_COLUMNS }
        for (entry in entries) grid.addView(emojiCell(entry))
        return grid
    }

    private fun emojiCell(entry: String): View = TextView(context).apply {
        text = entry
        textSize = 22f
        // Lora has no emoji. Forcing it here draws blank boxes, so the panel looked empty.
        typeface = android.graphics.Typeface.DEFAULT
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFF3EFFB.toInt())
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(48)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        contentDescription = if (VoicePreferences.state.value.keys.pinnedEmoji.contains(entry)) {
            "Emoji $entry, pinned. Long press to unpin."
        } else {
            "Emoji $entry. Long press to pin."
        }
        setOnClickListener { commitEmoji(entry) }
        setOnLongClickListener { togglePin(entry); true }
    }

    /** Adds or removes a pin, capped at five, and refreshes the panel so the row updates at once. */
    private fun togglePin(entry: String) {
        val context = this.context
        VoicePreferences.update(context) { settings ->
            settings.copy(keys = VoicePreferences.withPinned(entry, settings.keys))
        }
        emojiPanel.removeAllViews()
        buildEmojiPanel()
    }

    private fun buildClipboardPanel() {
        clipboard.mount(clipboardPanel,
            canPaste = { EditorPolicy.clipboardPermits(editorInfo) && connectionProvider() != null },
            onPaste = { commit(it) },
        )
    }

    fun showClipboardNotice(message: String) = notice(message)

    private fun commitEmoji(text: String) {
        VoicePreferences.update(context) { settings ->
            settings.copy(keys = VoicePreferences.rememberEmoji(text, settings.keys))
        }
        commit(text)
    }

    private fun commit(text: String) {
        if (!EditorPolicy.clipboardPermits(editorInfo)) return
        val connection = connectionProvider() ?: return
        service.cancelFieldPolish()
        onExternalEdit(selectionStart, selectionEnd)
        write(connection, text)
        closePanels()
    }

    private fun switchKeyboard() {
        feedback()
        // A dedicated key is the fastest route; the picker covers devices with more than two keyboards.
        if (service.shouldOfferSwitchingToNextInputMethod()) service.switchToNextInputMethod(false)
        else service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
    }

    /**
     * Explains a refusal inside the keyboard, over the key grid and next to the control that was
     * tapped. One explanation per gesture: repeated taps within the cooldown get one clear reason,
     * not a loop.
     */
    private fun notice(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotice < 1500) return
        lastNotice = now
        noticeBanner.text = message
        noticeBanner.visibility = View.VISIBLE
        handler.removeCallbacks(hideNoticeTask)
        handler.postDelayed(hideNoticeTask, NOTICE_MS)
    }

    private fun hideNotice() {
        noticeBanner.visibility = View.GONE
    }

    /** Available capabilities are full strength; refused ones are dimmed but still answer taps. */
    private fun capability(view: View, available: Boolean) {
        view.alpha = if (available) 1f else DIMMED_ALPHA
    }

    private fun feedback() {
        Haptics.tap(context, VoicePreferences.levelOf(VoicePreferences.state.value))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Docked, right hand, left hand, then a narrower keyboard you can drag. */
    private fun cycleLayout() {
        feedback()
        layoutMode = (layoutMode + 1) % 4
        root.findViewById<View>(R.id.ime_column).post { applyLayout() }
    }

    private fun applyLayout() {
        val column = root.findViewById<LinearLayout>(R.id.ime_column)
        val parent = column.parent as? View ?: return
        val full = parent.width
        if (full <= 0) return
        val lp = column.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val grip = functionRow.findViewById<View>(R.id.function_grip)
        when (layoutMode) {
            MODE_RIGHT, MODE_LEFT -> {
                lp.width = (full * 0.72f).toInt().coerceAtLeast(dp(288)).coerceAtMost(full)
                lp.gravity = if (layoutMode == MODE_LEFT) android.view.Gravity.START else android.view.Gravity.END
                column.translationX = 0f
                grip.visibility = View.GONE
            }
            MODE_FLOAT -> {
                lp.width = (full * 0.78f).toInt().coerceAtLeast(dp(288)).coerceAtMost(full)
                lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
                column.translationX = floatOffset
                grip.visibility = View.VISIBLE
            }
            else -> {
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
                column.translationX = 0f
                grip.visibility = View.GONE
            }
        }
        column.layoutParams = lp
        if (BuildConfig.DEBUG) column.postDelayed({ service.logKeyboardGeometry(root) }, 32)
    }

    private var dragOrigin = 0f
    private var dragBase = 0f

    private fun dragFloating(event: android.view.MotionEvent): Boolean {
        if (layoutMode != MODE_FLOAT) return false
        val column = root.findViewById<View>(R.id.ime_column)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragOrigin = event.rawX
                dragBase = column.translationX
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                floatOffset = dragBase + (event.rawX - dragOrigin)
                column.translationX = floatOffset
            }
        }
        return true
    }

    private companion object {
        const val GRID_COLUMNS = 8
        const val SURROUNDING = 2048
        const val MAX_DOCUMENT = 100_000
        const val TAIL = 96
        const val NOTICE_MS = 2500L
        const val DIMMED_ALPHA = 0.35f
        const val MODE_DOCKED = 0
        const val MODE_RIGHT = 1
        const val MODE_LEFT = 2
        const val MODE_FLOAT = 3
        const val PAGE_RECENT = "Recent"
    }
}
