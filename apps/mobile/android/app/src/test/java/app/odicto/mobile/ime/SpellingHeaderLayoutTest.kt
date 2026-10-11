package app.odicto.mobile.ime

import android.graphics.Rect
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.R
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SpellingHeaderLayoutTest {
    private data class Geometry(val height: Int, val grid: Rect, val firstKey: Rect)

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun measure(root: View, width: Int = 412): Geometry {
        val density = root.resources.displayMetrics.density
        repeat(3) {
            // This tree is intentionally detached from a Window. Recompute the descendants instead
            // of relying on ViewRootImpl to propagate an asynchronous candidate's layout request.
            descendants(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
            (root as ViewGroup).offsetDescendantRectToMyCoords(view, it)
            it.offset(0, -root.height)
        }
        val grid = root.findViewById<View>(R.id.ime_keyboard)
        val firstKey = descendants(grid).first { it.contentDescription == "1" }
        return Geometry(root.height, bounds(grid), bounds(firstKey))
    }

    private fun waitForCandidate(row: LinearLayout): View {
        repeat(200) {
            descendants(row).firstOrNull { it.contentDescription == "Suggestion because" }?.let { return it }
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        throw AssertionError("The bundled local dictionary did not produce its known candidate")
    }

    private fun lookup(strip: SpellingStrip): Job? = strip.javaClass.getDeclaredField("request")
        .apply { isAccessible = true }.get(strip) as? Job

    private fun complete(job: Job?) {
        repeat(200) {
            if (job == null || job.isCompleted) return
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        throw AssertionError("Spelling lookup did not complete or cancel")
    }

    private fun sameKeys(before: Geometry, after: Geometry) {
        assertEquals("grid stays anchored to the keyboard bottom", before.grid, after.grid)
        assertEquals("key dimensions and bottom-relative placement stay fixed", before.firstKey, after.firstKey)
    }

    private class Fixture(enabled: Boolean = true) : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        private val previous = VoicePreferences.state.value
        private val previousVoice = VoiceSession.state.value
        private val owner: org.robolectric.android.controller.ServiceController<OdictoImeService>
        val service: OdictoImeService
        val root: View
        val controller: KeyboardController
        val row: LinearLayout
        val status: TextView
        val strip: SpellingStrip get() = controller.javaClass.getDeclaredField("spellingStrip")
            .apply { isAccessible = true }.get(controller) as SpellingStrip
        init {
            runBlocking { VoicePreferences.save(context, previous.copy(keys = previous.keys.copy(autocorrectEnabled = enabled))) }
            VoiceSession.update { VoiceUi() }
            owner = Robolectric.buildService(OdictoImeService::class.java).create()
            service = owner.get()
            root = service.onCreateInputView()
            controller = service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(service) as KeyboardController
            row = root.findViewById(R.id.ime_suggestions)
            status = root.findViewById(R.id.ime_polish_status)
            controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 })
            root.findViewById<View>(R.id.ime_keyboard_toggle).performClick()
            service.onWindowShown()
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun message(text: String, busy: Boolean = false) {
            service.javaClass.getDeclaredMethod("showPolishStatus", String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, text, busy)
        }
        fun enabled(value: Boolean) {
            runBlocking { VoicePreferences.save(context, VoicePreferences.state.value.copy(keys = VoicePreferences.state.value.keys.copy(autocorrectEnabled = value))) }
            controller.refreshAppearance()
        }
        /** Synthetic presentation inputs, owned by the controller so service refreshes retain them. */
        fun spelling(word: String? = null, original: String? = null) {
            val session = controller.javaClass.getDeclaredField("editorSession").apply { isAccessible = true }
                .get(controller) as EditorSessionState
            controller.javaClass.getDeclaredField("spellingWord").apply { isAccessible = true }
                .set(controller, word?.let { SpellingWord(it, it.length, session.epoch) })
            val undoField = controller.javaClass.getDeclaredField("correctionUndo").apply { isAccessible = true }
            val undo = original?.let {
                undoField.type.getDeclaredConstructor(String::class.java, SpellingWord::class.java)
                    .apply { isAccessible = true }.newInstance(it, SpellingWord("the ", 4, session.epoch))
            }
            undoField.set(controller, undo)
            controller.refreshSpelling()
        }
        override fun close() {
            owner.destroy()
            VoiceSession.update { previousVoice }
            runBlocking { VoicePreferences.save(context, previous) }
        }
    }

    @Test fun emptyAssistanceDoesNotReserveAnyHeight() {
        Fixture().use { f ->
            measure(f.root)
            assertEquals("no spelling choices means no suggestion row", View.GONE, f.row.visibility)
            assertEquals("an empty status must not leave its own black spacer", View.GONE,
                f.root.findViewById<View>(R.id.ime_assistance).visibility)
            assertEquals(f.root.findViewById<View>(R.id.ime_top_row).height,
                f.root.findViewById<View>(R.id.ime_top_controls).height)
        }
    }

    @Test fun contentOnlyHeaderKeepsKeysAnchoredAndPopulatedRowsEqual() {
        Fixture().use { f ->
            assertSame("status and spelling must share a single height reservation", f.status.parent, f.row.parent)
            val empty = measure(f.root)
            val compactHeight = (36 * f.root.resources.displayMetrics.density).toInt()
            val xmlRow = LayoutInflater.from(f.root.context).inflate(R.layout.ime_keyboard, null, false)
                .findViewById<LinearLayout>(R.id.ime_suggestions)
            assertEquals("the authored compact baseline remains 36dp", compactHeight, xmlRow.layoutParams.height)
            f.spelling(word = "becuase")
            val candidate = waitForCandidate(f.row) as TextView
            assertSame("candidate must still belong to the live strip before layout", f.row, candidate.parent)
            val populated = measure(f.root)
            sameKeys(empty, populated)
            assertEquals((f.row.parent as View).height, populated.height - empty.height)
            assertTrue(populated.height > empty.height)
            assertSame("geometry checks must never use a retired candidate", f.row, candidate.parent)
            assertSame(candidate, descendants(f.row).single { it.contentDescription == "Suggestion because" })
            // Robolectric 4.14.1's ShadowPaint uses top=-32/bottom=9 in REALISTIC text mode,
            // regardless of text size. Real fonts and accessibility sizes also have their own
            // metrics: validate against the actual rendered label, not a guessed line height.
            val font = candidate.paint.fontMetricsInt
            assertEquals("only a taller rendered font line may exceed the compact baseline",
                maxOf(compactHeight, font.bottom - font.top), f.row.height)
            assertTrue(candidate.isClickable && candidate.isFocusable)
            assertEquals("the whole compact row remains a touch target", f.row.height, candidate.height)
            f.spelling(original = "teh")
            assertEquals(populated, measure(f.root))
            val undo = descendants(f.row).single { it.contentDescription == "Undo autocorrect" }
            assertEquals(f.row.height, undo.height)
            for (message in listOf("Copied", "Field changed. Try Polish again.", "Polishing")) {
                f.message(message, message == "Polishing")
                assertEquals(message, f.status.text.toString())
                assertEquals(View.VISIBLE, f.status.visibility)
                assertEquals(View.INVISIBLE, f.row.visibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, f.row.importantForAccessibility)
                assertEquals(populated, measure(f.root))
            }
            f.message("")
            assertEquals(View.INVISIBLE, f.status.visibility)
            assertEquals(View.VISIBLE, f.row.visibility)
            assertEquals(populated, measure(f.root))

            f.spelling()
            assertEquals(empty, measure(f.root))
            assertEquals(View.GONE, (f.row.parent as View).visibility)

            VoiceSession.update { VoiceUi(phase = "processing", message = "Processing voice") }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, f.row.visibility)
            val busy = measure(f.root)
            assertTrue(busy.height > empty.height)
            sameKeys(empty, busy)
            assertEquals(View.VISIBLE, f.status.visibility)
        }
    }

    @Test fun sharedHeaderDoesNotAddSpaceToCollapsedOrDisabledKeyboard() {
        Fixture(false).use { f ->
            val off = measure(f.root)
            assertEquals(View.GONE, f.row.visibility)
            f.enabled(true)
            val on = measure(f.root)
            assertEquals("enabling the feature alone must not create a blank row", off, on)
            f.root.findViewById<View>(R.id.ime_keyboard_toggle).performClick()
            val collapsedOn = measure(f.root)
            assertEquals(View.GONE, f.row.visibility)
            f.enabled(false)
            assertEquals(collapsedOn, measure(f.root))
            f.message("Copied")
            assertTrue(measure(f.root).height > collapsedOn.height)
            f.message("")
            assertEquals(collapsedOn, measure(f.root))
        }
    }

    @Test fun messageOcclusionBlocksCandidateAndUndoActionsAndRestoresThemAfterwards() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = LayoutInflater.from(context).inflate(R.layout.ime_keyboard, null, false)
        val row = root.findViewById<LinearLayout>(R.id.ime_suggestions)
        var choices = 0
        var undos = 0
        val strip = SpellingStrip(row, { choices++ }, { undos++ })
        try {
            strip.render(true, "becuase", null)
            val candidate = waitForCandidate(row)
            val baseline = measure(root)
            strip.setStatusVisible(true)
            candidate.performClick()
            assertEquals(0, choices)
            assertEquals(baseline, measure(root))
            strip.setStatusVisible(false)
            candidate.performClick()
            assertEquals(1, choices)
            strip.render(true, null, "teh")
            val undo = descendants(row).single { it.contentDescription == "Undo autocorrect" }
            strip.setStatusVisible(true)
            undo.performClick()
            assertEquals(0, undos)
            assertEquals(baseline, measure(root))
            strip.setStatusVisible(false)
            undo.performClick()
            assertEquals(1, undos)
            strip.render(false, null, null)
            undo.performClick()
            assertEquals("a collapsed/retired action stays inert", 1, undos)
            val empty = measure(root)
            assertTrue(empty.height < baseline.height)
            sameKeys(baseline, empty)
            assertEquals(View.GONE, (row.parent as View).visibility)
        } finally { strip.dispose() }
    }

    @Test fun noResultReleasesTheHeaderAndOldChoicesAreInertDuringLookup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = LayoutInflater.from(context).inflate(R.layout.ime_keyboard, null, false)
        val row = root.findViewById<LinearLayout>(R.id.ime_suggestions)
        var choices = 0
        val strip = SpellingStrip(row, { choices++ }, {})
        try {
            val empty = measure(root)
            strip.render(true, "becuase", null)
            val old = waitForCandidate(row)
            val populated = measure(root)
            strip.render(true, "because", null)
            assertSame("retain last choices only while the next lookup runs", row, old.parent)
            old.performClick()
            assertEquals("retained choices cannot edit a newer word", 0, choices)
            assertEquals(populated, measure(root))
            complete(lookup(strip))
            assertEquals(0, row.childCount)
            assertEquals(View.GONE, row.visibility)
            assertEquals(View.GONE, (row.parent as View).visibility)
            assertEquals(empty, measure(root))
        } finally { strip.dispose() }
    }

    @Test fun clearedCollapsedAndChangedEditorsCannotResurrectPendingChoices() {
        Fixture().use { f ->
            val empty = measure(f.root)
            for (clear in listOf<() -> Unit>({ f.spelling() }, { f.controller.onExternalEdit(0, 0) },
                { f.controller.onKeyboardPresentationChanged(false) })) {
                f.controller.onKeyboardPresentationChanged(true)
                f.spelling(word = "becuase")
                val pending = lookup(f.strip)
                clear()
                complete(pending)
                assertEquals(View.GONE, f.row.visibility)
                assertEquals(0, f.row.childCount)
                assertEquals(View.GONE, (f.row.parent as View).visibility)
                assertEquals(empty, measure(f.root))
            }
            f.controller.onKeyboardPresentationChanged(true)
            f.spelling(word = "becuase")
            val pending = lookup(f.strip)
            f.root.findViewById<View>(R.id.function_emoji).performClick()
            complete(pending)
            assertEquals(View.VISIBLE, f.root.findViewById<View>(R.id.emoji_panel).visibility)
            assertEquals(View.GONE, f.row.visibility)
            assertEquals(View.GONE, (f.row.parent as View).visibility)
            f.root.findViewById<View>(R.id.function_emoji).performClick()
            complete(lookup(f.strip))
            assertEquals(0, f.row.childCount)
            assertEquals(empty, measure(f.root))
        }
    }
}
