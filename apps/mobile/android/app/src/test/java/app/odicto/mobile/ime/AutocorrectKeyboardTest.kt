package app.odicto.mobile.ime

import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import app.odicto.mobile.R
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutocorrectKeyboardTest {
    private class Host(view: View) : BaseInputConnection(view, true) {
        var reads = 0
        var readable = true
        var beforeRead: (() -> Unit)? = null
        var rejectCommit = false
        var batches = 0
        override fun beginBatchEdit(): Boolean { batches++; return true }
        override fun endBatchEdit(): Boolean { batches--; return true }
        override fun commitText(text: CharSequence?, cursor: Int): Boolean = !rejectCommit && super.commitText(text, cursor)
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? { reads++; return if (readable) super.getTextBeforeCursor(length, flags) else null }
        override fun getSurroundingText(before: Int, after: Int, flags: Int): SurroundingText? {
            reads++
            beforeRead?.also { beforeRead = null; it() }
            if (!readable) return null
            val start = Selection.getSelectionStart(editable)
            val end = Selection.getSelectionEnd(editable)
            val from = (minOf(start, end) - before).coerceAtLeast(0)
            val to = (maxOf(start, end) + after).coerceAtMost(editable!!.length)
            return SurroundingText(editable!!.subSequence(from, to), start - from, end - from, from)
        }
        fun seed(text: String) { editable!!.replace(0, editable!!.length, text); Selection.setSelection(editable, text.length) }
        fun text() = editable.toString()
    }

    private class Fixture(enabled: Boolean = true, type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) : AutoCloseable {
        @Suppress("UNCHECKED_CAST")
        private val preferences = VoicePreferences::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(null) as MutableStateFlow<VoiceSettings>
        private val previous = preferences.value
        private val previousVoice = VoiceSession.state.value
        private val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        val root = LayoutInflater.from(owner.get()).inflate(R.layout.ime_keyboard, null, false)
        val host = Host(View(owner.get()))
        var active: Host = host
        val controller: KeyboardController
        val info = EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 5; initialSelEnd = 5 }
        init {
            VoiceSession.update { VoiceUi() }
            preferences.value = previous.copy(keys = previous.keys.copy(autocorrectEnabled = enabled))
            host.seed("seed ")
            controller = KeyboardController(owner.get(), root) { active }
            controller.onStartInput(info)
            controller.onKeyboardPresentationChanged(true)
        }
        fun enabled(value: Boolean) { preferences.value = preferences.value.copy(keys = preferences.value.keys.copy(autocorrectEnabled = value)); controller.refreshAppearance() }
        fun tap(label: String, notify: Boolean = true) {
            val oldStart = Selection.getSelectionStart(active.editable)
            val oldEnd = Selection.getSelectionEnd(active.editable)
            val key = descendants(root.findViewById(R.id.ime_keyboard)).single { it.contentDescription == label }
            assertTrue("accessible key $label", key.performClick())
            if (notify) controller.onSelectionChanged(oldStart, oldEnd, Selection.getSelectionStart(active.editable), Selection.getSelectionEnd(active.editable), -1, -1)
        }
        fun type(text: String, notify: Boolean = true) { text.forEach { tap(if (it == ' ') "Space" else it.toString(), notify) } }
        fun restart() { info.initialSelStart = Selection.getSelectionStart(host.editable); info.initialSelEnd = info.initialSelStart; controller.onStartInput(info) }
        fun button(label: String): View? = descendants(root.findViewById(R.id.ime_suggestions)).firstOrNull { it.contentDescription == label }
        override fun close() { controller.dispose(); owner.destroy(); preferences.value = previous; VoiceSession.update { previousVoice } }
    }

    @Test fun spaceCorrectsAndImmediateBackspaceRestoresWordWithoutReapplyingOnSpace() {
        Fixture().use { f ->
            f.type("teh ")
            assertEquals("seed the ", f.host.text())
            assertNotNull(f.button("Undo autocorrect"))
            f.tap("Backspace")
            assertEquals("seed teh", f.host.text())
            f.tap("Space")
            assertEquals("seed teh ", f.host.text())
            f.type("recieve ")
            assertEquals("seed teh receive ", f.host.text())
            assertEquals(0, f.host.batches)
        }
    }

    @Test fun googleSearchFlagsAllowOptedInCorrectionAndUndo() {
        // Metadata observed on the S24 Ultra Google app: ordinary multiline text, no suggestions,
        // Search action, and no fullscreen extract UI. No user query is retained in the fixture.
        val googleType = 0xa0001
        val googleOptions = 0x10000003
        Fixture(type = googleType, options = googleOptions).use { f ->
            f.type("teh")
            assertEquals("seed teh", f.host.text())
            f.tap("Space")
            assertEquals("search must honor the user's enabled local autocorrect", "seed the ", f.host.text())
            f.tap("Backspace")
            assertEquals("seed teh", f.host.text())
            f.tap("Space")
            assertEquals("seed teh ", f.host.text())
            assertEquals(0, f.host.batches)
        }
        Fixture(enabled = false, type = googleType, options = googleOptions).use { f ->
            f.type("teh ")
            assertEquals("the user opt-out still wins in search", "seed teh ", f.host.text())
            assertEquals(View.GONE, f.root.findViewById<View>(R.id.ime_suggestions).visibility)
        }
    }

    @Test fun searchActionDoesNotOverrideProtectedOrIdentifierEditors() {
        val guardedTypes = listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        for (type in guardedTypes) {
            Fixture(type = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                options = EditorInfo.IME_ACTION_SEARCH).use { f ->
                f.type("teh ")
                assertEquals("guarded search type $type", "seed teh ", f.host.text())
                assertNull(f.button("Undo autocorrect"))
            }
        }
        Fixture(type = 0xa0001, options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).use { f ->
            f.type("teh ")
            assertEquals("seed teh ", f.host.text())
            assertEquals(0, f.host.reads)
        }
    }

    @Test fun freshEmptyAndSentenceStartFieldsCorrectWithCapitalization() {
        for (prefix in listOf("", "hello. ")) {
            Fixture().use { f ->
                f.host.seed(prefix)
                f.restart()
                f.type("teh ")
                assertEquals(prefix + "The ", f.host.text())
                assertEquals((prefix + "The ").length, Selection.getSelectionStart(f.host.editable))
                assertEquals(0, f.host.batches)
            }
        }
    }

    @Test fun rapidWordCorrectsWithDelayedOrCoalescedSelectionNotifications() {
        for (notifications in listOf("none", "latestOnly", "olderThenLatest")) {
            Fixture().use { f ->
                // The host applied these commits, but Android has not delivered their callbacks.
                f.type("teh", notify = false)
                when (notifications) {
                    "latestOnly" -> f.controller.onSelectionChanged(5, 5, 8, 8, -1, -1)
                    "olderThenLatest" -> {
                        f.controller.onSelectionChanged(5, 5, 6, 6, -1, -1)
                        f.controller.onSelectionChanged(6, 6, 8, 8, -1, -1)
                    }
                }
                f.tap("Space", notify = false)
                assertEquals("selection delivery: $notifications", "seed the ", f.host.text())
                assertEquals(9, Selection.getSelectionStart(f.host.editable))
                assertEquals(0, f.host.batches)
            }
        }
    }

    @Test fun delayedCursorRecoveryStillRejectsUnreadableChangedWordsAndChangedEditors() {
        for (change in listOf("unreadable", "changedWord", "changedConnection", "changedEpoch")) {
            Fixture().use { f ->
                f.type("teh", notify = false)
                f.controller.onSelectionChanged(5, 5, 6, 6, -1, -1)
                f.controller.onSelectionChanged(6, 6, 8, 8, -1, -1)
                val other = Host(View(f.root.context)).apply { seed("other ") }
                when (change) {
                    "unreadable" -> f.host.readable = false
                    "changedWord" -> f.host.editable!!.replace(5, 8, "xyz")
                    "changedConnection" -> f.host.beforeRead = { f.active = other }
                    "changedEpoch" -> f.host.beforeRead = { f.restart() }
                }
                f.tap("Space", notify = false)
                val expected = when (change) {
                    "unreadable" -> "seed teh "
                    "changedWord" -> "seed xyz "
                    else -> "seed teh"
                }
                assertEquals(change, expected, f.host.text())
                assertEquals("a newly focused editor must not receive the old key", "other ", other.text())
                assertEquals(0, f.host.batches)
                assertEquals(0, other.batches)
            }
        }
    }

    @Test fun undoKeepsTheHeaderVisibleUntilItsSuggestionsReplaceTheRetiredButton() {
        Fixture().use { f ->
            f.type("teh ")
            val undo = f.button("Undo autocorrect")!!
            val header = f.root.findViewById<View>(R.id.ime_assistance)
            assertEquals(View.VISIBLE, header.visibility)
            f.tap("Backspace")
            assertEquals("seed teh", f.host.text())
            assertEquals("a populated-to-populated update must not resize the IME", View.VISIBLE, header.visibility)
            undo.performClick()
            assertEquals("the retained old button must be inert", "seed teh", f.host.text())
            f.tap("Space")
            assertEquals("seed teh ", f.host.text())
            assertEquals("ordinary Space really has no content to reserve", View.GONE, header.visibility)
        }
    }

    @Test fun visibleUndoUsesTheSameOriginalAndDoesNotRecorrectIt() {
        Fixture().use { f ->
            f.type("becuase ")
            assertEquals("seed because ", f.host.text())
            f.button("Undo autocorrect")!!.performClick()
            assertEquals("seed becuase", f.host.text())
            f.tap("Space")
            assertEquals("seed becuase ", f.host.text())
        }
    }

    @Test fun optOutAndCollapseRemoveSuggestionsAndDoNotRewrite() {
        Fixture(false).use { f ->
            f.type("teh ")
            assertEquals("seed teh ", f.host.text())
            assertEquals(View.GONE, f.root.findViewById<View>(R.id.ime_suggestions).visibility)
            f.enabled(true)
            assertEquals("enabling without a current word must not add an empty row", View.GONE,
                f.root.findViewById<View>(R.id.ime_suggestions).visibility)
            f.controller.onKeyboardPresentationChanged(false)
            assertEquals(View.GONE, f.root.findViewById<View>(R.id.ime_suggestions).visibility)
        }
    }

    @Test fun changedCursorContentAndUnavailableReadRefuseAutomaticReplacement() {
        Fixture().use { f ->
            f.type("teh")
            Selection.setSelection(f.host.editable, 5)
            f.tap("Space")
            assertEquals("seed  teh", f.host.text())
        }
        Fixture().use { f ->
            f.type("teh")
            f.host.editable!!.replace(5, 8, "xyz")
            f.tap("Space")
            assertEquals("seed xyz ", f.host.text())
        }
        Fixture().use { f ->
            f.type("teh")
            f.host.readable = false
            f.tap("Space")
            assertEquals("seed teh ", f.host.text())
        }
    }

    @Test fun focusRestartAndCursorMovementInvalidateUndo() {
        Fixture().use { f ->
            f.type("teh ")
            f.restart()
            f.tap("Backspace")
            assertEquals("seed the", f.host.text())
        }
        Fixture().use { f ->
            f.type("teh ")
            f.controller.onExternalEdit(0, 0)
            Selection.setSelection(f.host.editable, 0)
            f.tap("Backspace")
            assertEquals("seed the ", f.host.text())
        }
    }

    @Test fun staleConnectionDuringGuardReadCannotReplaceEitherField() {
        Fixture().use { f ->
            f.type("teh")
            val other = Host(View(f.root.context)).apply { seed("other") }
            f.host.beforeRead = { f.active = other; f.controller.onExternalEdit(5, 5) }
            f.tap("Space")
            assertEquals("seed teh", f.host.text())
            assertEquals("other", other.text())
        }
    }

    @Test fun failedCompoundCommitDoesNotDeleteOriginalOrInsertFallbackSpaceOverIt() {
        Fixture().use { f ->
            f.type("teh")
            f.host.rejectCommit = true
            f.tap("Space")
            assertEquals("seed teh", f.host.text())
            assertEquals(0, f.host.batches)
            assertNull(f.button("Undo autocorrect"))
            f.host.rejectCommit = false
            f.tap("x")
            assertEquals("seed tehx", f.host.text())
        }
    }

    @Test fun shiftDoesNotRevokeTheChoiceToKeepAnUndoneWord() {
        Fixture().use { f ->
            f.type("teh "); f.tap("Backspace"); f.tap("Shift"); f.tap("Space")
            assertEquals("seed teh ", f.host.text())
        }
    }

    @Test fun holdingBackspaceAfterCorrectionContinuesDeletingAfterTheInitialUndo() {
        Fixture().use { f ->
            f.type("teh ")
            val keyboard = f.root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
            val key = descendants(keyboard).single { it.contentDescription == "Backspace" }
            val bounds = android.graphics.Rect()
            key.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(key, bounds)
            val at = android.os.SystemClock.uptimeMillis()
            fun event(action: Int) {
                val event = android.view.MotionEvent.obtain(at, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { keyboard.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            event(android.view.MotionEvent.ACTION_DOWN)
            assertEquals("seed teh", f.host.text())
            f.controller.onSelectionChanged(9, 9, 8, 8, -1, -1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            event(android.view.MotionEvent.ACTION_UP)
            assertTrue("hold must continue beyond undo", f.host.text().length < 8)
            assertEquals(0, f.host.batches)
        }
    }

    @Test fun aWordInTheMiddleOfAnExistingTokenIsNotAutocorrected() {
        Fixture().use { f ->
            f.host.seed("seed suffix")
            Selection.setSelection(f.host.editable, 5)
            f.restart()
            f.type("teh ")
            assertEquals("seed teh suffix", f.host.text())
        }
    }

    @Test fun protectedAndIdentifierEditorsHaveNoSuggestionsOrAutomaticCorrection() {
        val types = listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        types.forEach { type ->
            Fixture(type = type).use { f ->
                f.type("teh ")
                assertEquals("seed teh ", f.host.text())
                assertEquals(View.GONE, f.root.findViewById<View>(R.id.ime_suggestions).visibility)
                if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT || type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD) assertEquals(0, f.host.reads)
            }
        }
        Fixture(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).use { f ->
            f.type("teh ")
            assertEquals("seed teh ", f.host.text())
            assertEquals(0, f.host.reads)
        }
    }

    @Test fun dictionarySuggestionReplacesOnlyTheCurrentWordAndLateResultsCannotEditNewWord() {
        Fixture().use { f ->
            f.type("becuase")
            repeat(150) {
                if (f.button("Suggestion because") == null) {
                    Thread.sleep(10)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                }
            }
            val choice = f.button("Suggestion because")
            assertNotNull("bundled dictionary supplies local spelling choice", choice)
            choice!!.performClick()
            assertEquals("seed because", f.host.text())
            f.type(" xyz")
            choice.performClick()
            assertEquals("seed because xyz", f.host.text())
        }
    }

    @Test fun wordExtractionRefusesTruncatedAndIdentifierFragments() {
        listOf("user@teh", "https://teh", "name_teh", "abc123teh", "can't", "नमteh").forEach {
            assertNull(SpellingWord.atCursor(it, it.length, 1))
        }
        assertNull(SpellingWord.atCursor("teh", 99, 1))
        assertEquals("teh", SpellingWord.atCursor("hello teh", 9, 1)?.text)
    }

    companion object {
        private fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
