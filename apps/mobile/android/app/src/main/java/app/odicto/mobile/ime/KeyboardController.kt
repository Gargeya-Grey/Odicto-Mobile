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
    private val editorTail = EditorTail()
    private var enterKind = ImeActions.Kind.NEWLINE
    private var deleteBurst = false
    private var deleteConnection: InputConnection? = null
    private val editorSession = EditorSessionState()
    private val deleteRepeat = DeleteRepeatCoordinator()
    /** Null until a cursor move. A typing run then needs no read of the field. */
    private var sentenceCap: Boolean? = null
    private val hideNoticeTask = Runnable { hideNotice() }
    private var appearance: Triple<String, String, Int>? = null
    private var typingExpanded = false
    private var spellingWord: SpellingWord? = null
    private var keepOriginal: SpellingWord? = null
    private data class CorrectionUndo(val original: String, val replacement: SpellingWord)
    private var correctionUndo: CorrectionUndo? = null
    private val spellingStrip = SpellingStrip(root.findViewById(R.id.ime_suggestions), ::chooseSpelling,
        keep = ::keepTyped, undo = { feedback(); undoSpelling() }, covered = ::onAssistanceCovered)
    private val assistance: View = root.findViewById(R.id.ime_assistance)

    fun refreshAppearance() {
        refreshSpelling()
        val settings = VoicePreferences.state.value
        val next = Triple(settings.keys.keySize, settings.keys.keyFont, VoicePreferences.toleranceOf(settings))
        if (appearance == next) return
        appearance = next
        cancelTouch()
        keyboard.refreshAppearance()
    }

    init {
        keyboard.setOnKeyListener(::onKey)
        keyboard.setUppercaseChoiceListener(::uppercaseChoice)
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) keyboard.cancelTouch()
        }
        keyboard.setBackspaceReleaseListener(::finishDeleteBurst)
        keyboard.setBackspaceRepeatGate(::prepareDeleteRepeat)
        keyboard.setDragListener(::onSpaceDrag) { gestureAllowed() }
        functionRow.findViewById<ImageButton>(R.id.function_mic).setOnClickListener { toggleVoice() }
        functionRow.findViewById<ImageButton>(R.id.function_emoji).setOnClickListener { toggleEmoji() }
        functionRow.findViewById<ImageButton>(R.id.function_clipboard).setOnClickListener { toggleClipboard() }
        functionRow.findViewById<ImageButton>(R.id.function_switch).setOnClickListener { switchKeyboard() }
        show(OdictoKeyboardView.Page.LETTERS)
    }

    fun attachVoiceControl(view: VoiceControlView) {
        voiceControl = view
        if (view.parent == null) {
            root.findViewById<android.widget.FrameLayout>(R.id.ime_top_row).addView(view, 0,
                android.widget.FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight, android.view.Gravity.START))
        }
        (assistance.parent as View).addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) assistance.post { layoutAssistance() }
        }
        layoutAssistance()
        syncMicButton()
    }

    /** The voice strip already has a microphone. A second one in the toolbar is the duplicate. */
    private fun syncMicButton() {
        val mic = functionRow.findViewById<View>(R.id.function_mic) ?: return
        val stripVisible = voiceControl?.visibility == View.VISIBLE
        mic.visibility = if (stripVisible) View.GONE else View.VISIBLE
    }

    fun onStartInput(info: EditorInfo?) {
        clearSpelling()
        refreshAppearance()
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
        refreshSpelling()
    }

    fun onFinishInput() {
        editorInfo = null
        clearSpelling()
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
        // Fields without typing assistance (Odicto's own fields, number and phone fields) cannot be
        // read, so their cursor report is the only sign a held backspace's delete landed. Password
        // and no-learning fields keep their bounded hold.
        if (!EditorPolicy.typingAssistPermits(editorInfo) && EditorPolicy.manualPermits(editorInfo) &&
            editorSession.acknowledgeBlindDelete(oldStart, oldEnd, start, end)) {
            selectionStart = start
            selectionEnd = end
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
    fun dispose() { onFinishInput(); spellingStrip.dispose(); voiceControl = null }

    fun onExternalEdit(start: Int, end: Int) {
        clearSpelling()
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
        KeyLatencyProbe.outcome(when (spec.key) {
            OdictoKeyboardView.Key.CHARACTER -> KeyLatencyProbe.Outcome.CHARACTER_DISPATCH
            OdictoKeyboardView.Key.SPACE -> KeyLatencyProbe.Outcome.SPACE_DISPATCH
            else -> KeyLatencyProbe.Outcome.OTHER_DISPATCH
        })
        invalidateHeldLetter()
        resetCursorWindow()
        val connection = connectionProvider()
        if (spec.key == OdictoKeyboardView.Key.BACKSPACE && spec.repeatOrdinal > 0 &&
            (!deleteBurst || connection == null || connection !== deleteConnection)) {
            keyboard.cancelTouch()
            finishDeleteBurst()
            return
        }
        if (connection == null) {
            KeyLatencyProbe.outcome(KeyLatencyProbe.Outcome.NO_CONNECTION)
            return
        }
        if (editorSession.connection !== connection) {
            onExternalEdit(-1, -1)
        }
        when (spec.key) {
            OdictoKeyboardView.Key.CHARACTER, OdictoKeyboardView.Key.SPACE,
            OdictoKeyboardView.Key.ENTER, OdictoKeyboardView.Key.BACKSPACE -> service.cancelFieldPolish()
            else -> Unit
        }
        val epoch = editorSession.epoch
        if (spec.key == OdictoKeyboardView.Key.SPACE && editorSession.ambiguous &&
            spellingWord != null && spellingEligible()) {
            // An older typing callback can arrive before the latest one and leave the cursor
            // uncertain. Reconcile with the host before discarding our word. The replacement
            // still checks the original word and exact range; a callback alone is not evidence.
            refreshEditor(connection)
            if (connectionProvider() !== connection || editorSession.epoch != epoch) return
        }
        if (editorSession.ambiguous && spec.key != OdictoKeyboardView.Key.BACKSPACE) {
            editorTail.clear()
            selectionStart = -1
            selectionEnd = -1
            sentenceCap = false
            lastWasSpace = false
        }
        val assist = EditorPolicy.typingAssistPermits(editorInfo) && !editorSession.ambiguous
        if (spec.key != OdictoKeyboardView.Key.BACKSPACE) correctionUndo = null
        if (spec.key == OdictoKeyboardView.Key.CHARACTER || spec.key == OdictoKeyboardView.Key.ENTER ||
            spec.key == OdictoKeyboardView.Key.BACKSPACE) keepOriginal = null
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
                write(connection, text, spec, epoch)
                sentenceCap = if (assist) when {
                    text.endsWith("\n") || text.endsWith(". ") || text.endsWith("! ") || text.endsWith("? ") -> true
                    text.lastOrNull()?.let { it.isLetter() || it.isDigit() } == true -> false
                    else -> null
                } else false
                lastWasSpace = false
                updateSpellingWord()
            }
            OdictoKeyboardView.Key.SPACE -> {
                val word = spellingWord
                val replacement = word?.takeIf { it != keepOriginal && spellingAllowed() && !strippedOfView() }?.let(::autocorrectFor)
                keepOriginal = null
                if (word != null && replacement != null && replaceSpelling(connection, word, "$replacement ") != SpellingEdit.SKIPPED) {
                    lastWasSpace = true
                    sentenceCap = false
                    refreshSpelling()
                    return
                }
                clearSpelling(updateView = false)
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
                clearSpelling(updateView = false)
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
                if (spec.repeatOrdinal == 0 && undoSpelling()) {
                    // Undo consumes only the initial press. A continuing hold still owns repeat.
                    if (keepOriginal != null && connectionProvider() === connection && spellingAllowed()) {
                        deleteConnection = connection
                        deleteBurst = true
                        deleteRepeat.start(editorSession.epoch)
                    }
                    return
                }
                clearSpelling(updateView = false)
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
        refreshSpelling()
    }

    fun onKeyboardPresentationChanged(expanded: Boolean) {
        typingExpanded = expanded
        if (!expanded) clearSpelling()
        refreshSpelling()
    }

    fun refreshSpelling() {
        val allowed = spellingAllowed()
        if (!allowed) {
            spellingWord = null; correctionUndo = null; keepOriginal = null
        }
        val word = spellingWord
        spellingStrip.render(allowed && keyboard.visibility == View.VISIBLE, word?.text, correctionUndo?.original,
            word != null && startsSentence(word), word != null && word == keepOriginal)
        layoutAssistance()
    }

    /** The replacement the spacebar applies; the curated table covers the moment before the lexicon loads. */
    private fun autocorrectFor(word: SpellingWord): String? {
        val start = startsSentence(word)
        val engine = EnglishSpelling.loaded() ?: return EnglishSpelling.builtinCorrection(word.text, start)
        return engine.autocorrect(word.text, start)
    }

    /** True only on evidence: the field start, or a sentence end before the word. */
    private fun startsSentence(word: SpellingWord): Boolean {
        val tail = editorTail.knownSuffix() ?: return false
        if (!tail.endsWith(word.text)) return false
        val before = tail.dropLast(word.text.length)
        if (before.endsWith('\n')) return true
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return word.end == tail.length
        return trimmed.last() in ".!?"
    }

    /** Keep the typed word: no correction for it, then the same space a spacebar tap gives. */
    private fun keepTyped() {
        val word = spellingWord ?: return
        if (!spellingAllowed()) { clearSpelling(); return }
        feedback()
        keepOriginal = word
        onKey(OdictoKeyboardView.KeySpec(OdictoKeyboardView.Key.SPACE, "Space", " ", 1f))
    }

    private fun onAssistanceCovered(covered: Boolean) {
        voiceControl?.setKeyboardCovered(covered)
        layoutAssistance()
    }

    /**
     * The slot covers the tool cells and nothing else. Idle: everything between the keyboard toggle
     * and the mic, minus the Copy cell while a result can be copied (explicit Copy recovery must stay
     * reachable). During voice: only the three mode cells, so cancel and lock stay reachable.
     * The top row is laid out left to right in every locale (see ime_keyboard.xml), so these are
     * absolute left/right margins.
     */
    private fun layoutAssistance() {
        val row = assistance.parent as? View ?: return
        val cell = voiceControl?.keyboardCellPx ?: dp(48)
        val voice = VoiceSession.state.value
        val copyable = !voice.busy && (voice.finalText.isNotBlank() || voice.interim.isNotBlank())
        val left = if (voice.busy && row.width > 0) (row.width - cell * 6).coerceAtLeast(cell * 2) else cell
        val right = if (voice.busy || copyable) cell * 3 else cell * 2
        val params = assistance.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        if (params.leftMargin == left && params.rightMargin == right) return
        params.leftMargin = left; params.rightMargin = right
        assistance.layoutParams = params
    }

    /** True while a status line hides the strip: Space then must not apply an unseen correction. */
    private fun strippedOfView(): Boolean = spellingStrip.statusShown

    fun setSpellingStatusVisible(visible: Boolean) = spellingStrip.setStatusVisible(visible)

    private fun spellingEligible(): Boolean = typingExpanded && VoicePreferences.state.value.keys.autocorrectEnabled &&
        EditorPolicy.autocorrectPermits(service.currentInputEditorInfo ?: editorInfo) &&
        editorSession.composingStart < 0 && gestureAllowed()

    private fun spellingAllowed(): Boolean = spellingEligible() && !editorSession.ambiguous

    private fun clearSpelling(updateView: Boolean = true) {
        spellingWord = null; correctionUndo = null; keepOriginal = null
        if (updateView) spellingStrip.render(false, null, null)
    }

    private fun updateSpellingWord() {
        spellingWord = if (spellingAllowed() && selectionStart == selectionEnd)
            SpellingWord.atCursor(editorTail.knownSuffix(), selectionStart, editorSession.epoch) else null
    }

    private fun chooseSpelling(replacement: String) {
        val word = spellingWord ?: return
        val connection = connectionProvider() ?: return
        if (!spellingAllowed()) { clearSpelling(); return }
        feedback()
        // A picked word ends the word with a space, unless text already follows (mid-sentence edit).
        val atEnd = runCatching { connection.getTextAfterCursor(1, 0)?.toString() }.getOrNull()?.isEmpty() == true
        if (replaceSpelling(connection, word, if (atEnd) "$replacement " else replacement) == SpellingEdit.APPLIED) {
            lastWasSpace = atEnd
            sentenceCap = false
        }
        refreshSpelling()
    }

    private fun undoSpelling(): Boolean {
        val undo = correctionUndo ?: return false
        val connection = connectionProvider() ?: return false
        val result = replaceSpelling(connection, undo.replacement, undo.original, remember = false)
        if (result == SpellingEdit.APPLIED) {
            keepOriginal = SpellingWord(undo.original, selectionStart, editorSession.epoch)
            spellingWord = keepOriginal
            lastWasSpace = false
            sentenceCap = false
        }
        correctionUndo = null
        refreshSpelling()
        return result != SpellingEdit.SKIPPED
    }

    private enum class SpellingEdit { SKIPPED, APPLIED, UNVERIFIED }

    /**
     * How a fresh read placed the word. ABSOLUTE: the host gave the cursor's position in the field.
     * RELATIVE: the host gave only the text around a collapsed cursor (the platform's default
     * [InputConnection.getSurroundingText] has no offset, and many editors keep that default).
     */
    private enum class SpellingRange { NONE, ABSOLUTE, RELATIVE }

    /** Only a fresh, bounded host read can authorize a replacement; the cached tail is not proof. */
    private fun spellingRange(connection: InputConnection, word: SpellingWord): SpellingRange {
        if (!spellingAllowed() || connectionProvider() !== connection || editorSession.connection !== connection ||
            word.epoch != editorSession.epoch || selectionStart != word.end || selectionEnd != word.end) return SpellingRange.NONE
        val range = runCatching {
            val before: String
            val after: String
            var start = -1
            var end = -1
            if (Build.VERSION.SDK_INT >= 31) {
                val read = connection.getSurroundingText(word.text.length + 1, 1, 0) ?: return@runCatching SpellingRange.NONE
                if (read.selectionStart !in 0..read.text.length || read.selectionEnd !in 0..read.text.length ||
                    read.selectionStart != read.selectionEnd) return@runCatching SpellingRange.NONE
                if (read.offset >= 0) { start = read.offset + read.selectionStart; end = read.offset + read.selectionEnd }
                before = read.text.take(read.selectionStart).toString()
                after = read.text.drop(read.selectionEnd).toString()
            } else {
                val read = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 96 }, 0)
                if (read != null && read.startOffset >= 0) { start = read.startOffset + read.selectionStart; end = read.startOffset + read.selectionEnd }
                else if (!connection.getSelectedText(0).isNullOrEmpty()) return@runCatching SpellingRange.NONE
                before = connection.getTextBeforeCursor(word.text.length + 1, 0)?.toString() ?: return@runCatching SpellingRange.NONE
                after = connection.getTextAfterCursor(1, 0)?.toString() ?: return@runCatching SpellingRange.NONE
            }
            val absolute = start >= 0
            val prefix = before.removeSuffix(word.text)
            // Without an offset, the tracked cursor is the only evidence that the word starts the field.
            val fieldStart = if (absolute) start == word.text.length else prefix.isEmpty() && word.end == word.text.length
            val matches = (!absolute || start == word.end && end == start) && before.endsWith(word.text) &&
                (prefix.isNotEmpty() && (prefix.last().isWhitespace() || prefix.last() in "(\"“") || fieldStart) &&
                (after.isEmpty() || !after[0].isLetterOrDigit() && after[0] !in "_@/")
            when {
                !matches -> SpellingRange.NONE
                absolute -> SpellingRange.ABSOLUTE
                else -> SpellingRange.RELATIVE
            }
        }.getOrDefault(SpellingRange.NONE)
        return if (spellingAllowed() && connectionProvider() === connection && editorSession.epoch == word.epoch) range else SpellingRange.NONE
    }

    private fun replaceSpelling(connection: InputConnection, word: SpellingWord, replacement: String, remember: Boolean = true): SpellingEdit {
        val range = spellingRange(connection, word)
        if (range == SpellingRange.NONE) { clearSpelling(); return SpellingEdit.SKIPPED }
        invalidateHeldLetter()
        finishDeleteBurst()
        var began = false
        var selected = false
        val accepted = try {
            began = connection.beginBatchEdit()
            spellingAllowed() && connectionProvider() === connection && editorSession.epoch == word.epoch &&
                if (range == SpellingRange.ABSOLUTE) {
                    connection.setSelection(word.end - word.text.length, word.end).also { selected = it } &&
                        spellingAllowed() && connectionProvider() === connection && editorSession.epoch == word.epoch &&
                        connection.commitText(replacement, 1)
                } else {
                    // No offset to select by: the read proved the word ends at a collapsed cursor.
                    // A refused commit is reported below, never re-sent: its delivery is unknown.
                    connection.deleteSurroundingText(word.text.length, 0) && connection.commitText(replacement, 1)
                }
        } catch (_: Exception) { false }
        finally { if (began) runCatching { connection.endBatchEdit() } }
        // Each caller renders the final correction/Undo state in this same dispatch. Publishing
        // an empty row here would briefly resize the IME between two populated states.
        clearSpelling(updateView = false)
        if (!accepted || connectionProvider() !== connection || editorSession.epoch != word.epoch) {
            // A rejected commit may leave our temporary range selected. Collapse it only if fresh
            // host evidence still shows that exact original range, never move a changed user cursor.
            if (selected && connectionProvider() === connection && editorSession.epoch == word.epoch &&
                spellingAllowed()) runCatching {
                val stillSelected = if (Build.VERSION.SDK_INT >= 31) {
                    val current = connection.getSurroundingText(0, 0, 0)
                    current != null && current.offset >= 0 &&
                        current.offset + current.selectionStart == word.end - word.text.length &&
                        current.offset + current.selectionEnd == word.end && current.text.toString() == word.text
                } else {
                    val current = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 96 }, 0)
                    current != null && current.startOffset >= 0 &&
                        current.startOffset + current.selectionStart == word.end - word.text.length &&
                        current.startOffset + current.selectionEnd == word.end && connection.getSelectedText(0)?.toString() == word.text
                }
                if (stillSelected &&
                    connectionProvider() === connection && editorSession.epoch == word.epoch && spellingAllowed()) {
                    connection.setSelection(word.end, word.end)
                }
            }
            editorTail.clear()
            editorSession.ambiguous = true
            notice("Couldn't confirm the correction. Check the word.")
            return SpellingEdit.UNVERIFIED
        }
        editorTail.afterDelete(word.end, word.text.length)
        val cursor = word.end - word.text.length + replacement.length
        editorTail.afterInsert(word.end - word.text.length, replacement)
        selectionStart = cursor; selectionEnd = cursor
        editorSession.submitted(EditorSessionState.Snapshot(cursor, cursor, editorTail.knownSuffix(), editorTail.complete), 0, false, SystemClock.uptimeMillis())
        val replaced = SpellingWord(replacement, cursor, word.epoch)
        if (spellingRange(connection, replaced) == SpellingRange.NONE) {
            editorTail.clear()
            editorSession.ambiguous = true
            notice("Couldn't confirm the correction. Check the word.")
            return SpellingEdit.UNVERIFIED
        }
        if (remember) correctionUndo = CorrectionUndo(word.text, replaced)
        return SpellingEdit.APPLIED
    }

    private fun write(connection: InputConnection, text: String, spec: OdictoKeyboardView.KeySpec? = null, epoch: Long = editorSession.epoch) {
        if (connectionProvider() !== connection || editorSession.connection !== connection || editorSession.epoch != epoch) {
            KeyLatencyProbe.outcome(KeyLatencyProbe.Outcome.STALE_SESSION)
            return
        }
        if (editorSession.ambiguous) {
            editorTail.clear()
            selectionStart = -1
            selectionEnd = -1
        }
        // A voice composing span must be closed or this commit replaces the dictated word.
        // Ordinary typing has no span, and asking the field to finish one is a round trip per letter.
        val replacing = selectionStart >= 0 && selectionEnd >= 0 && selectionEnd != selectionStart
        val at = if (replacing) minOf(selectionStart, selectionEnd) else selectionStart
        if (VoiceSession.state.value.busy || editorSession.composingStart >= 0) {
            runCatching { connection.finishComposingText() }
            editorSession.composingStart = -1
            editorSession.composingEnd = -1
        }
        if (connectionProvider() !== connection || editorSession.connection !== connection || editorSession.epoch != epoch) {
            KeyLatencyProbe.outcome(KeyLatencyProbe.Outcome.STALE_SESSION)
            return
        }
        val measure = BuildConfig.DEBUG && spec?.key == OdictoKeyboardView.Key.CHARACTER && spec.touchAtNanos > 0
        KeyLatencyProbe.outcome(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT)
        val commitStart = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        val result = runCatching { connection.commitText(text, 1) }
        val commitEnd = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        val accepted = result.getOrDefault(false)
        KeyLatencyProbe.outcome(when {
            result.isFailure -> KeyLatencyProbe.Outcome.COMMIT_EXCEPTION
            accepted -> KeyLatencyProbe.Outcome.COMMIT_ACCEPTED
            else -> KeyLatencyProbe.Outcome.COMMIT_REJECTED
        })
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
        clearSpelling()
        if (editorSession.connection !== connection || selectionStart < 0 || selectionEnd < 0) return
        var epoch = editorSession.epoch
        service.cancelFieldPolish()
        val selected = selectionStart != selectionEnd
        val window = if (!selected && horizontal != 0) surroundingText(connection) ?: return else null
        val steps = if (selected || horizontal == 0) 1 else abs(horizontal).coerceAtMost(SURROUNDING * 2)
        repeat(steps) {
            if (connectionProvider() !== connection || editorSession.epoch != epoch || !gestureAllowed()) return
            val result = if (selected) {
                val edge = if (horizontal < 0 || vertical < 0) minOf(selectionStart, selectionEnd) else maxOf(selectionStart, selectionEnd)
                edge to edge
            } else if (window != null) {
                CursorNavigator.characterInWindow(window.first, window.second, selectionStart, if (horizontal < 0) -1 else 1)
            } else {
                val extracted = runCatching { connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_DOCUMENT }, 0) }.getOrNull()
                val text = extracted?.text?.toString()
                if (extracted == null || extracted.startOffset != 0 || extracted.partialStartOffset != -1 || text == null || text.length > MAX_DOCUMENT) {
                    notice("This field does not support cursor jumping.")
                    return
                }
                val edge = if (vertical > 0) text.length else 0
                edge to edge
            } ?: return
            if (result.first == selectionStart && result.second == selectionEnd) return
            if (connectionProvider() !== connection || editorSession.epoch != epoch || !gestureAllowed()) return
            val started = SystemClock.elapsedRealtimeNanos()
            if (!runCatching { connection.setSelection(result.first, result.second) }.getOrDefault(false)) {
                resetCursorWindow()
                return
            }
            if (connectionProvider() !== connection || editorSession.epoch != epoch) return
            val composingStart = editorSession.composingStart
            val composingEnd = editorSession.composingEnd
            selectionStart = result.first
            selectionEnd = result.second
            editorSession.reset(connection, selectionStart, selectionEnd)
            editorSession.composingStart = composingStart
            editorSession.composingEnd = composingEnd
            epoch = editorSession.epoch
            cursorMovedAt = SystemClock.uptimeMillis()
            cursorAcknowledgments.addLast(result.first)
            while (cursorAcknowledgments.size > 64) cursorAcknowledgments.removeFirst()
            editorTail.clear()
            sentenceCap = null
            lastWasSpace = false
            invalidateHeldLetter()
            feedback()
            if (BuildConfig.DEBUG) KeyLatencyProbe.cursor(started, SystemClock.elapsedRealtimeNanos())
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
        clearSpelling()
        keyboard.stopRepeating()
        keyboard.showPage(next)
        refreshSpelling()
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
        clearSpelling()
        val slot = root.findViewById<View>(R.id.ime_content)
        val height = keyboard.height.coerceAtLeast(keyboard.measuredHeight)
        // A match_parent panel inside a wrap_content slot measures as nothing, so the clipboard
        // and emoji views were in the tree and still invisible. Pin the slot to the key grid.
        if (height > 0 && slot.layoutParams != null) {
            slot.layoutParams = slot.layoutParams.apply { this.height = height }
        }
        keyboard.visibility = View.INVISIBLE
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        // Crossfade by alpha only: size and position never animate, and the system animator
        // setting (which "remove animations" turns off) is honoured.
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(140L).withLayer().start()
        } else {
            panel.alpha = 1f
        }
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
        // Closing is immediate. Stop any fade still running so the next open starts clean.
        emojiPanel.animate().cancel()
        emojiPanel.alpha = 1f
        emojiPanel.visibility = View.GONE
        emojiPanel.removeAllViews()
        clipboardPanel.animate().cancel()
        clipboardPanel.alpha = 1f
        clipboardPanel.visibility = View.GONE
        clipboardPanel.removeAllViews()
        clipboard.close()
        refreshSpelling()
    }

    /**
     * Builds the emoji panel with a persistent favourites row on top.
     *
     * A favourites row that only appears on long-press would be invisible until discovered and easy
     * to trigger by accident, so the five pins are always shown and long-press is used only to pin
     * or unpin.
     */
    private fun buildEmojiPanel() {
        val scroll = ScrollView(context).apply { contentDescription = "Emoji list" }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        emojiPanel.addView(emojiFilters(), LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        if (emojiPage == PAGE_RECENT) {
            val keys = VoicePreferences.state.value.keys
            if (keys.pinnedEmoji.isNotEmpty()) {
                column.addView(sectionLabel("Pinned"))
                column.addView(emojiRow(keys.pinnedEmoji))
                column.addView(divider())
            }
            column.addView(sectionLabel("Recent"))
            val recent = (keys.recentEmoji + EmojiCatalog.recent).distinct().take(EmojiCatalog.PAGE_SIZE)
            column.addView(emojiRow(recent))
        } else {
            val page = EmojiCatalog.categories.firstOrNull { it.label == emojiPage }
            if (page != null) column.addView(emojiRow(page.emoji))
        }
        scroll.addView(column)
        emojiPanel.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1f,
        ))
    }

    private fun sectionLabel(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 12f
        typeface = OdictoKeyboardView.typefaceFor(context)
        setTextColor(context.getColor(R.color.odicto_text_muted))
        setPadding(dp(8), dp(8), dp(8), dp(4))
    }

    /** Category selector: a scrolling row of pills. Each pill is 32dp tall inside a 48dp touch row. */
    private fun emojiFilters(): View {
        val row = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "Emoji categories"
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val base = OdictoKeyboardView.typefaceFor(context)
        val medium = if (Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(base, 500, false) else base
        val radius = dp(16).toFloat()
        // The 8dp vertical inset leaves a 32dp pill; the full 48dp row stays tappable.
        fun pill(fill: Int) = android.graphics.drawable.InsetDrawable(
            android.graphics.drawable.GradientDrawable().apply { cornerRadius = radius; setColor(fill) },
            0, dp(8), 0, dp(8),
        )
        val choices = listOf(PAGE_RECENT) + EmojiCatalog.categories.map { it.label }
        for (choice in choices) {
            val selected = choice == emojiPage
            line.addView(TextView(context).apply {
                text = choice
                contentDescription = "Emoji category $choice"
                isSelected = selected
                textSize = 13f
                typeface = if (selected) medium else base
                setTextColor(context.getColor(if (selected) R.color.odicto_text_strong else R.color.odicto_text_muted))
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x26FFFFFF),
                    pill(if (selected) context.getColor(R.color.odicto_panel_pill_selected) else 0),
                    pill(0xFFFFFFFF.toInt()),
                )
                // After the background: assigning a background replaces the view's padding.
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    val scrollX = row.scrollX
                    emojiPage = choice
                    emojiPanel.removeAllViews()
                    buildEmojiPanel()
                    (emojiPanel.getChildAt(0) as? android.widget.HorizontalScrollView)?.let { filters ->
                        filters.post { filters.scrollTo(scrollX, 0) }
                    }
                }
            }, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        row.addView(line)
        return row
    }

    /** The gap between two sections. Sections are separated by spacing only; no rule is drawn. */
    private fun divider(): View = View(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
    }

    private fun emojiRow(entries: List<String>): View {
        val grid = GridLayout(context).apply { columnCount = GRID_COLUMNS }
        for (entry in entries) grid.addView(emojiCell(entry))
        return grid
    }

    private fun emojiCell(entry: String): View = TextView(context).apply {
        text = entry
        // Emoji are pictures: a fixed size keeps them inside the 48dp cell at any font scale.
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 26f)
        // The key face has no emoji; forcing it here would draw blank boxes.
        typeface = android.graphics.Typeface.DEFAULT
        gravity = android.view.Gravity.CENTER
        setTextColor(context.getColor(R.color.odicto_toolbar_icon))
        // A round ripple on press is the only feedback, so a tap never animates the grid.
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x26FFFFFF), null, null,
        ).apply { radius = dp(22) }
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
        // Rebuilding the panel must not throw away where the user was looking.
        val filtersX = (emojiPanel.getChildAt(0) as? android.widget.HorizontalScrollView)?.scrollX ?: 0
        val listY = (emojiPanel.getChildAt(1) as? ScrollView)?.scrollY ?: 0
        emojiPanel.removeAllViews()
        buildEmojiPanel()
        (emojiPanel.getChildAt(0) as? android.widget.HorizontalScrollView)?.let { it.post { it.scrollTo(filtersX, 0) } }
        (emojiPanel.getChildAt(1) as? ScrollView)?.let { it.post { it.scrollTo(0, listY) } }
    }

    private fun buildClipboardPanel() {
        clipboard.mount(clipboardPanel,
            canPaste = { EditorPolicy.clipboardPermits(editorInfo) && connectionProvider() != null },
            onPaste = { commit(it) },
        )
    }

    fun showClipboardNotice(message: String) = notice(message)

    private fun commitEmoji(text: String) {
        val info = service.currentInputEditorInfo ?: editorInfo
        val reason = EditorPolicy.emojiRefusal(info)
        if (reason != null) { notice(reason); return }
        val connection = connectionProvider() ?: return
        service.cancelFieldPolish()
        onExternalEdit(selectionStart, selectionEnd)
        write(connection, text)
        // Retain category, scroll position and tile identity so consecutive taps stay predictable.
        VoicePreferences.update(context) { settings ->
            settings.copy(keys = VoicePreferences.rememberEmoji(text, settings.keys))
        }
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

    private companion object {
        const val GRID_COLUMNS = 8
        const val SURROUNDING = 2048
        const val MAX_DOCUMENT = 100_000
        const val TAIL = 96
        const val NOTICE_MS = 2500L
        const val DIMMED_ALPHA = 0.35f
        const val PAGE_RECENT = "Recent"
    }
}
