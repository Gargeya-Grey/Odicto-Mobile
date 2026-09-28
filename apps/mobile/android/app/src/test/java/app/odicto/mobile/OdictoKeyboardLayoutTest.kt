package app.odicto.mobile

import android.text.InputType
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.BackspacePace
import app.odicto.mobile.ime.ImeActions
import app.odicto.mobile.ime.KeyboardController
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.OdictoKeyboardView
import app.odicto.mobile.overlay.VoiceControlView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.Duration

/**
 * The keyboard view is inflated from XML by `OdictoImeService.onCreateInputView()`.
 *
 * A custom view that does not offer the `(Context, AttributeSet)` constructor compiles, installs, and
 * passes every unit test, then throws `InflateException` the first time a real user taps a text field
 * and the keyboard never appears. Inflating the real layout here is the only check that catches it.
 */
@RunWith(RobolectricTestRunner::class)
class OdictoKeyboardLayoutTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun inflate(): View =
        LayoutInflater.from(context).inflate(R.layout.ime_keyboard, null, false)

    @Test fun theImeLayoutInflates() {
        val root = inflate()
        assertNotNull(root)
    }

    @Test fun theLayoutExposesEveryPartTheServiceLooksUp() {
        val root = inflate()
        assertNotNull("keyboard view", root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard))
        assertNotNull("voice column", root.findViewById<LinearLayout>(R.id.ime_column))
        assertNotNull("top control row", root.findViewById<FrameLayout>(R.id.ime_top_row))
        assertNotNull("text polish button", root.findViewById<View>(R.id.ime_polish))
        assertNotNull("polish status", root.findViewById<TextView>(R.id.ime_polish_status))
        assertNotNull("function row", root.findViewById<LinearLayout>(R.id.function_row))
        assertNotNull("emoji panel", root.findViewById<LinearLayout>(R.id.emoji_panel))
        assertNotNull("clipboard panel", root.findViewById<LinearLayout>(R.id.clipboard_panel))
        assertNotNull("mic key", root.findViewById<View>(R.id.function_mic))
        assertNotNull("emoji key", root.findViewById<View>(R.id.function_emoji))
        assertNotNull("clipboard key", root.findViewById<View>(R.id.function_clipboard))
        assertNotNull("switch key", root.findViewById<View>(R.id.function_switch))
    }

    @Test fun polishButtonAndMicFitTheNarrowTopRow() {
        val root = inflate()
        val row = root.findViewById<FrameLayout>(R.id.ime_top_row)
        val button = root.findViewById<View>(R.id.ime_polish)
        val voice = VoiceControlView(context, true)
        assertEquals("Polish all text in this field", button.contentDescription)
        assertTrue(button.layoutParams.width >= (48 * context.resources.displayMetrics.density).toInt())
        assertEquals(voice.dp(288), voice.requiredWidth)
        voice.setKeyboardCompact(true)
        assertEquals(voice.dp(240), voice.requiredWidth)
        row.addView(voice, 0, FrameLayout.LayoutParams(voice.requiredWidth, voice.requiredHeight))
        root.findViewById<LinearLayout>(R.id.ime_column).layoutParams = FrameLayout.LayoutParams(760, FrameLayout.LayoutParams.WRAP_CONTENT)
        measure(root)
        assertTrue(voice.right <= button.left)
        assertTrue(button.right <= row.width)
    }

    @Test fun oneHandedLayoutsLeaveRoomForSixCompactSlotsAndPolish() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = inflate()
            val controller = KeyboardController(service, root)
            measure(root)
            repeat(3) {
                root.findViewById<View>(R.id.function_layout).performClick()
                controller.javaClass.getDeclaredMethod("applyLayout").apply { isAccessible = true }.invoke(controller)
                measure(root)
                val column = root.findViewById<View>(R.id.ime_column)
                assertTrue("Six 40dp slots plus 48dp Polish must fit", column.width >= (288 * context.resources.displayMetrics.density).toInt())
            }
            controller.dispose()
        } finally { owner.destroy() }
    }

    @Test fun accessibilityClickOnSpaceTypesASpace() {
        val keyboard = laidOutKeyboard()
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { pressed += it.output }
        val bottom = keyboard.getChildAt(keyboard.childCount - 1) as LinearLayout
        val space = (0 until bottom.childCount).map { bottom.getChildAt(it) }
            .first { it.contentDescription == "Space" }
        space.performClick()
        assertEquals(listOf(" "), pressed)
    }

    @Test fun everyKeyOfEveryPageIsBuiltAndReachable() {
        val keyboard = OdictoKeyboardView(context)
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { spec -> pressed.add(spec.output) }

        for (page in OdictoKeyboardView.Page.entries) {
            keyboard.showPage(page)
            assertTrue("page $page rendered no rows", keyboard.childCount > 0)
            val caps = mutableListOf<View>()
            for (row in 0 until keyboard.childCount) {
                val line = keyboard.getChildAt(row) as LinearLayout
                for (column in 0 until line.childCount) caps.add(line.getChildAt(column))
            }
            assertTrue("page $page built no keys", caps.isNotEmpty())
            // The spacebar must exist on every page, because the gesture depends on it.
            assertTrue("page $page has no spacebar", caps.any { it.contentDescription == "Space" })
        }
        assertTrue(pressed.isEmpty())
    }

    @Test fun iconKeysAreDescribedInWordsRatherThanAsGlyphs() {
        // Arrow and enter symbols used to be text labels, which rendered unreadably at key size and
        // were corrupted by encoding. The description must be a word, not the symbol itself.
        val keyboard = OdictoKeyboardView(context)
        val seen = mutableSetOf<String>()
        for (page in OdictoKeyboardView.Page.entries) {
            keyboard.showPage(page)
            for (row in 0 until keyboard.childCount) {
                val line = keyboard.getChildAt(row) as LinearLayout
                for (column in 0 until line.childCount) {
                    val description = line.getChildAt(column).contentDescription?.toString().orEmpty()
                    if (description.isEmpty()) continue
                    seen += description
                    // A mojibake sequence shows up as a run of Latin-1 supplement characters.
                    val isMojibake = description.count { it.code in 0x80..0x2FF } > 1
                    assertFalse("page $page description is corrupted: '$description'", isMojibake)
                }
            }
        }
        assertTrue(seen.containsAll(listOf("Shift", "Backspace", "Enter", "Space", "123", "ABC", "#+=")))
        assertFalse(seen.contains("?123"))
        assertFalse(seen.contains("=#?"))
    }

    @Test fun theToolbarSitsBelowTheKeysAndLettersStartWithANumberRow() {
        val root = inflate()
        val column = root.findViewById<LinearLayout>(R.id.ime_column)
        val toolbar = root.findViewById<View>(R.id.function_row)
        val grid = root.findViewById<View>(R.id.ime_content)
        val toolbarIndex = (0 until column.childCount).indexOfFirst { column.getChildAt(it) === toolbar }
        val gridIndex = (0 until column.childCount).indexOfFirst { column.getChildAt(it) === grid }
        assertTrue("toolbar must sit under the keys", toolbarIndex > gridIndex)

        val keyboard = OdictoKeyboardView(context)
        keyboard.showPage(OdictoKeyboardView.Page.LETTERS)
        val top = keyboard.getChildAt(0) as LinearLayout
        val labels = (0 until top.childCount).map { top.getChildAt(it).contentDescription?.toString() }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), labels)
    }

    @Test fun panelsStartHiddenSoTheKeyboardOpensOnLetters() {
        val root = inflate()
        assertEquals(View.GONE, root.findViewById<View>(R.id.emoji_panel).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.clipboard_panel).visibility)
    }

    @Test fun everyPageFitsItsWidthAndSplitsIntoEqualRows() {
        val keyboard = OdictoKeyboardView(context)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)

        for (page in OdictoKeyboardView.Page.entries) {
            keyboard.showPage(page)
            keyboard.measure(widthSpec, heightSpec)
            keyboard.layout(0, 0, 1080, 200)

            val expectedRows = if (page == OdictoKeyboardView.Page.LETTERS) 5 else 4
            assertEquals("page $page row count", expectedRows, keyboard.childCount)
            val rowHeights = (0 until keyboard.childCount).map { keyboard.getChildAt(it).height }
            val letterRows = rowHeights.dropLast(1)
            assertEquals("page $page letter rows must share the height", 1, letterRows.distinct().size)
            assertTrue("page $page space row must be thicker", rowHeights.last() > letterRows.first())

            for (row in 0 until keyboard.childCount) {
                val line = keyboard.getChildAt(row) as LinearLayout
                for (column in 0 until line.childCount) {
                    val cap = line.getChildAt(column)
                    assertTrue("page $page key $column must stay on screen", cap.width in 1..1080)
                    assertTrue("page $page key $column must have width", cap.width > 0)
                }
                // A row whose children overflow the screen means the weights are wrong.
                val total = (0 until line.childCount).sumOf { line.getChildAt(it).width }
                assertTrue("page $page row $row overflows: $total", total <= 1080)
            }
        }
    }

    @Test fun theLettersPageStartsUnshiftedAndShiftTogglesIt() {
        val keyboard = OdictoKeyboardView(context)
        keyboard.showPage(OdictoKeyboardView.Page.LETTERS)
        assertFalse(keyboard.currentShift())
        keyboard.setShifted(true)
        assertTrue(keyboard.currentShift())
    }

    @Test fun shiftDoesNotLeakOntoTheSymbolsPage() {
        val keyboard = OdictoKeyboardView(context)
        keyboard.showPage(OdictoKeyboardView.Page.LETTERS)
        keyboard.setShifted(true)
        keyboard.showPage(OdictoKeyboardView.Page.SYMBOLS)
        assertFalse("symbols must never be affected by letters-page shift", keyboard.currentShift())
    }

    @Test fun theVoiceColumnHoldsEachChildOnlyOnce() {
        // `onCreateInputView` used to add the voice control and then hand the same instance to the
        // controller, which added it again. Android throws "already has a parent" on the second add
        // and the keyboard window never appears.
        val root = inflate()
        val column = root.findViewById<LinearLayout>(R.id.ime_column)
        val child = View(context)
        column.addView(child, 0)
        val occurrences = (0 until column.childCount).count { column.getChildAt(it) === child }
        assertEquals("a child must never be added twice", 1, occurrences)
        // Re-adding the same instance is the failure, so prove the guard's precondition is meaningful.
        var threw = false
        try {
            @Suppress("UNUSED_EXPRESSION")
            column.addView(child, 0)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Android must reject the duplicate add this bug relied on", threw)
    }

    @Test fun manualTypingInSensitiveFieldsNeverReadsEditorText() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        var reads = 0
        var writes = 0
        val connection = object : BaseInputConnection(View(service), true) {
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? { reads++; return "private" }
            override fun getSelectedText(flags: Int): CharSequence? { reads++; return "private" }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes++; return true }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean { writes++; return true }
        }
        val controller = KeyboardController(service, root) { connection }
        val keyboard = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        for (info in listOf(
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD },
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING },
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER },
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE },
        )) {
            info.initialSelStart = -1
            info.initialSelEnd = -1
            controller.onStartInput(info)
            measure(keyboard)
            val (qx, qy) = centerOf(keyboard, "q")
            dispatch(keyboard, MotionEvent.ACTION_DOWN, qx, qy)
            dispatch(keyboard, MotionEvent.ACTION_UP, qx, qy)
            val (sx, sy) = centerOf(keyboard, "Space")
            dispatch(keyboard, MotionEvent.ACTION_DOWN, sx, sy)
            dispatch(keyboard, MotionEvent.ACTION_UP, sx, sy)
            val (bx, by) = centerOf(keyboard, "Backspace")
            dispatch(keyboard, MotionEvent.ACTION_DOWN, bx, by)
            dispatch(keyboard, MotionEvent.ACTION_UP, bx, by)
        }
        assertEquals(0, reads)
        assertEquals(12, writes)
    }

    @Test fun explicitBackspaceStillDeletesWhenEditorContextIsUnavailable() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        var calls = 0
        val connection = object : BaseInputConnection(View(service), true) {
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = null
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                assertEquals(1, beforeLength)
                assertEquals(0, afterLength)
                calls++
                return true
            }
        }
        val controller = KeyboardController(service, root) { connection }
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 3; initialSelEnd = 3 })
        val keyboard = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        measure(keyboard)
        val (x, y) = centerOf(keyboard, "Backspace")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals(1, calls)
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals(2, calls)
    }

    @Test fun uncachedEmojiDeletionUsesVerifiedUtf16Width() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val text = "a😀b"
        var cursor = 3
        val connection = object : BaseInputConnection(View(service), true) {
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = text.take(cursor).takeLast(length)
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                assertEquals(2, beforeLength)
                cursor -= beforeLength
                return true
            }
        }
        val controller = KeyboardController(service, root) { connection }
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 3; initialSelEnd = 3 })
        val keyboard = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        measure(keyboard)
        val (x, y) = centerOf(keyboard, "Backspace")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        val selection = KeyboardController::class.java.getDeclaredField("selectionStart").apply { isAccessible = true }
        assertEquals(1, cursor)
        assertEquals(1, selection.getInt(controller))
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        controller.onSelectionChanged(cursor, cursor)
        assertEquals(1, selection.getInt(controller))
    }

    @Test fun cursorTapAfterBackspaceControlsTheNextSpaceDrag() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val text = "abcdefghijklmnopqrstuvwxyz".repeat(4)
        var cursor = 5
        val positions = mutableListOf<Int>()
        val connection = object : BaseInputConnection(View(service), true) {
            override fun beginBatchEdit(): Boolean = true
            override fun endBatchEdit(): Boolean = true
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                cursor -= beforeLength
                return true
            }
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = text.take(cursor).takeLast(length)
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = text.drop(cursor).take(length)
            override fun setSelection(start: Int, end: Int): Boolean {
                cursor = start
                positions += start
                return true
            }
        }
        val controller = KeyboardController(service, root) { connection }
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 5; initialSelEnd = 5 }
        controller.onStartInput(info)
        val keyboard = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        measure(keyboard)
        val (bx, by) = centerOf(keyboard, "Backspace")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, bx, by)
        dispatch(keyboard, MotionEvent.ACTION_UP, bx, by)
        cursor = 2
        controller.onSelectionChanged(2, 2)
        val (sx, sy) = centerOf(keyboard, "Space")
        val density = context.resources.displayMetrics.density
        val activation = 8f * density
        val step = 4f * density
        dispatch(keyboard, MotionEvent.ACTION_DOWN, sx, sy)
        dispatch(keyboard, MotionEvent.ACTION_MOVE, sx + activation, sy)
        dispatch(keyboard, MotionEvent.ACTION_MOVE, sx + activation + step, sy)
        dispatch(keyboard, MotionEvent.ACTION_UP, sx + activation + step, sy)
        assertEquals(3, positions.last())
    }

    @Test fun uppercaseHoldReplacesOnlyTheCommittedLetter() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val connection = object : BaseInputConnection(View(service), true) {
            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int) =
                android.view.inputmethod.SurroundingText(editable.toString(), android.text.Selection.getSelectionStart(editable), android.text.Selection.getSelectionEnd(editable), 0)
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) =
                android.view.inputmethod.ExtractedText().apply {
                    text = editable.toString()
                    startOffset = 0
                    selectionStart = android.text.Selection.getSelectionStart(editable)
                    selectionEnd = android.text.Selection.getSelectionEnd(editable)
                    partialStartOffset = -1
                    partialEndOffset = -1
                }
        }
        connection.commitText("hi ", 1)
        val controller = KeyboardController(service, root) { connection }
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            initialSelStart = 3
            initialSelEnd = 3
        })
        val keyboard = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        measure(keyboard)
        val (x, y) = centerOf(keyboard, "q")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        assertEquals("hi q", connection.editable.toString())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals("hi Q", connection.editable.toString())
        controller.onSelectionChanged(4, 4)
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        connection.setSelection(1, 1)
        controller.onSelectionChanged(1, 1)
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals("hi Qq", connection.editable.toString())
    }

    @Test fun cursorDragReusesItsWindowAndIgnoresOlderAcknowledgments() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val text = "abcdefghijklmnopqrstuvwxyz"
        var cursor = 10
        var reads = 0
        val positions = mutableListOf<Int>()
        val connection = object : BaseInputConnection(View(service), true) {
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
                reads++
                return text.take(cursor).takeLast(length)
            }
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
                reads++
                return text.drop(cursor).take(length)
            }
            override fun setSelection(start: Int, end: Int): Boolean {
                cursor = start
                positions += start
                return true
            }
        }
        val controller = KeyboardController(service, root) { connection }
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            initialSelStart = 10
            initialSelEnd = 10
        })
        val drag = KeyboardController::class.java.getDeclaredMethod("onSpaceDrag", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        drag.invoke(controller, 1, 0)
        drag.invoke(controller, 1, 0)
        controller.onSelectionChanged(11, 11)
        drag.invoke(controller, 1, 0)
        assertEquals(listOf(11, 12, 13), positions)
        assertEquals(2, reads)
        cursor = 4
        controller.onSelectionChanged(4, 4)
        drag.invoke(controller, -1, 0)
        assertEquals(3, positions.last())
        assertEquals(4, reads)
    }

    @Test fun aTapOnTheSpacebarTypesASpaceEvenWhenTheFingerDrifts() {
        val keyboard = laidOutKeyboard()
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { pressed += it.output }
        val (x, y) = centerOf(keyboard, "Space")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        dispatch(keyboard, MotionEvent.ACTION_MOVE, x + 2f, y + 2f)
        dispatch(keyboard, MotionEvent.ACTION_UP, x + 2f, y + 2f)
        assertEquals(listOf(" "), pressed)
    }

    @Test fun aCancelledSpaceTouchDoesNotInsertText() {
        val keyboard = laidOutKeyboard()
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { pressed += it.output }
        val (x, y) = centerOf(keyboard, "Space")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        dispatch(keyboard, MotionEvent.ACTION_CANCEL, x, y + 40f)
        assertTrue(pressed.isEmpty())
    }

    @Test fun aTapOnBackspaceDeletesOnceAndAHoldNumbersEachDeletion() {
        val keyboard = laidOutKeyboard()
        val (x, y) = centerOf(keyboard, "Backspace")
        val tapped = mutableListOf<Int>()
        keyboard.setOnKeyListener { if (it.key == OdictoKeyboardView.Key.BACKSPACE) tapped += it.repeatOrdinal }
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf(0), tapped)

        val held = mutableListOf<Int>()
        keyboard.setOnKeyListener { if (it.key == OdictoKeyboardView.Key.BACKSPACE) held += it.repeatOrdinal }
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        val throughLetters = BackspacePace.HOLD_MS + BackspacePace.LETTER_INTERVAL_MS * 8 + 20
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(throughLetters))
        assertEquals((0..9).toList(), held)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        assertTrue("word-sized deletes must follow the tenth letter", held.any { it >= BackspacePace.LETTERS_BEFORE_WORDS })
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        val stopped = held.size
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("releasing backspace must stop the repeat", stopped, held.size)
    }

    @Test fun everyLetterShowsItsOwnPreviewAndHoldingQDoesNotTurnItIntoOne() {
        val keyboard = laidOutKeyboard()
        val (qx, qy) = centerOf(keyboard, "q")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, qx, qy)
        assertEquals("q", keyboard.visiblePreview())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals("holding q must keep showing q", "q", keyboard.visiblePreview())
        dispatch(keyboard, MotionEvent.ACTION_UP, qx, qy)
        assertNull(keyboard.visiblePreview())

        keyboard.setShifted(true)
        dispatch(keyboard, MotionEvent.ACTION_DOWN, qx, qy)
        assertEquals("Q", keyboard.visiblePreview())
        dispatch(keyboard, MotionEvent.ACTION_UP, qx, qy)

        val (ax, ay) = centerOf(keyboard, "a")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, ax, ay)
        assertEquals("a", keyboard.visiblePreview())
        dispatch(keyboard, MotionEvent.ACTION_UP, ax, ay)

        val (oneX, oneY) = centerOf(keyboard, "1")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, oneX, oneY)
        assertEquals("1", keyboard.visiblePreview())
        dispatch(keyboard, MotionEvent.ACTION_UP, oneX, oneY)
    }

    @Test fun theSpacebarIsLongerThanTheMarksAndSitsRightOfCenter() {
        val keyboard = laidOutKeyboard()
        val bottom = keyboard.getChildAt(keyboard.childCount - 1) as LinearLayout
        fun widthOn(root: View, description: String): Int? {
            if (root.contentDescription == description) return root.width
            if (root is android.view.ViewGroup) {
                for (index in 0 until root.childCount) {
                    widthOn(root.getChildAt(index), description)?.let { return it }
                }
            }
            return null
        }
        fun widthOf(description: String): Int {
            for (column in 0 until bottom.childCount) {
                val cap = bottom.getChildAt(column)
                if (cap.contentDescription == description) return cap.width
            }
            throw AssertionError("missing $description")
        }
        fun centerOfKey(description: String): Int {
            for (column in 0 until bottom.childCount) {
                val cap = bottom.getChildAt(column)
                if (cap.contentDescription == description) return cap.left + cap.width / 2
            }
            throw AssertionError("missing $description")
        }
        val space = widthOf("Space")
        assertTrue("space $space should dwarf the comma", space > widthOf(",") * 3)
        val backspace = widthOn(keyboard, "Backspace") ?: throw AssertionError("missing backspace")
        assertEquals("enter should match the backspace width", backspace, widthOf("Enter"))
        assertTrue("space should extend toward the right thumb", centerOfKey("Space") > bottom.width / 2)
        for (column in 0 until bottom.childCount) {
            assertFalse(bottom.getChildAt(column).contentDescription == ".")
        }
    }

    @Test fun theRightSideOfTheSpacebarIsStillASpace() {
        val keyboard = laidOutKeyboard()
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { pressed += it.output }
        val bottom = keyboard.getChildAt(keyboard.childCount - 1) as LinearLayout
        var cap: View? = null
        for (column in 0 until bottom.childCount) {
            if (bottom.getChildAt(column).contentDescription == "Space") cap = bottom.getChildAt(column)
        }
        val space = cap ?: throw AssertionError("missing space")
        val x = bottom.left + space.left + space.width * 0.92f
        val y = bottom.top + space.top + space.height / 2f
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf(" "), pressed)
    }

    @Test fun aTapInTheGapBetweenKeysDoesNotTypeANeighbor() {
        val keyboard = laidOutKeyboard()
        assertEquals(2f, keyboard.keyGapDp(), 0.01f)
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { pressed += it.output }
        val (qx, qy) = centerOf(keyboard, "q")
        val (wx, _) = centerOf(keyboard, "w")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, (qx + wx) / 2f, qy)
        dispatch(keyboard, MotionEvent.ACTION_UP, (qx + wx) / 2f, qy)
        assertTrue("gap tap produced $pressed", pressed.isEmpty())
    }

    @Test fun rapidTapsAndASecondFingerEachRegister() {
        val keyboard = laidOutKeyboard()
        val pressed = mutableListOf<String>()
        keyboard.setOnKeyListener { pressed += it.output }
        for (letter in listOf("q", "w", "e")) {
            val (x, y) = centerOf(keyboard, letter)
            dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
            dispatch(keyboard, MotionEvent.ACTION_MOVE, x + 4f, y + 3f)
            dispatch(keyboard, MotionEvent.ACTION_UP, x + 4f, y + 3f)
        }
        assertEquals(listOf("q", "w", "e"), pressed)

        val (ax, ay) = centerOf(keyboard, "a")
        val (sx, sy) = centerOf(keyboard, "s")
        secondFinger(keyboard, ax, ay, sx, sy)
        assertEquals(listOf("q", "w", "e", "a", "s"), pressed)
    }

    @Test fun holdingCommaOpensTheFullStopAndASwipeInsertsIt() {
        val keyboard = laidOutKeyboard()
        val events = mutableListOf<Pair<OdictoKeyboardView.Key, String>>()
        keyboard.setOnKeyListener { events += it.key to it.output }
        val (x, y) = centerOf(keyboard, ",")
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf(OdictoKeyboardView.Key.CHARACTER to ","), events)

        events.clear()
        dispatch(keyboard, MotionEvent.ACTION_DOWN, x, y)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        dispatch(keyboard, MotionEvent.ACTION_UP, x, y)
        assertEquals(",", events[0].second)
        assertEquals(OdictoKeyboardView.Key.BACKSPACE, events[1].first)
        assertEquals(".", events[2].second)
    }

    @Test fun aSearchFieldLabelsTheEnterKeyAndSurvivesAPageChange() {
        val keyboard = laidOutKeyboard()
        keyboard.setEnterAction(ImeActions.Kind.SEARCH)
        keyboard.showPage(OdictoKeyboardView.Page.NUMBERS)
        keyboard.showPage(OdictoKeyboardView.Page.LETTERS)
        centerOf(keyboard, "Search")
        assertEquals(ImeActions.Kind.SEARCH, keyboard.enterAction())
    }

    @Test fun theKeyboardHasNoSuggestionStripThatCanChangeItsHeight() {
        val id = context.resources.getIdentifier("suggestion_row", "id", context.packageName)
        assertEquals("a suggestion row changes the keyboard height when it appears", 0, id)
        assertNotNull(inflate())
    }

    @Test fun emojiAndClipboardReplaceTheKeyGridInsteadOfCollapsing() {
        val service = Robolectric.buildService(OdictoImeService::class.java).create().get()
        val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val controller = KeyboardController(service, root)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        controller.onStartInput(info)
        val keyboard = root.findViewById<View>(R.id.ime_keyboard)
        measure(root)
        val keyHeight = keyboard.height
        assertTrue("keys must have a height before a panel opens", keyHeight > 80)

        root.findViewById<View>(R.id.function_emoji).performClick()
        measure(root)
        val emoji = root.findViewById<LinearLayout>(R.id.emoji_panel)
        assertEquals(View.VISIBLE, emoji.visibility)
        assertEquals(View.INVISIBLE, keyboard.visibility)
        assertTrue("emoji panel collapsed to ${emoji.height}, keys were $keyHeight", emoji.height >= keyHeight - 4)
        assertTrue("emoji panel must contain glyphs", containsEmoji(emoji))
        assertFalse("emoji must not be forced onto Lora", emojiUsesLora(emoji))

        root.findViewById<View>(R.id.function_clipboard).performClick()
        measure(root)
        val clipboard = root.findViewById<View>(R.id.clipboard_panel)
        assertEquals(View.VISIBLE, clipboard.visibility)
        assertEquals(View.GONE, emoji.visibility)
        assertTrue("clipboard panel collapsed to ${clipboard.height}", clipboard.height >= keyHeight - 4)
    }

    private fun laidOutKeyboard(): OdictoKeyboardView {
        val keyboard = OdictoKeyboardView(context)
        measure(keyboard)
        return keyboard
    }

    private fun measure(view: View) {
        val width = View.MeasureSpec.makeMeasureSpec((360 * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
        view.measure(width, height)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun centerOf(keyboard: OdictoKeyboardView, description: String): Pair<Float, Float> {
        for (row in 0 until keyboard.childCount) {
            val line = keyboard.getChildAt(row) as LinearLayout
            for (column in 0 until line.childCount) {
                val cap = line.getChildAt(column)
                if (cap.contentDescription == description) {
                    return (line.left + cap.left + cap.width / 2f) to (line.top + cap.top + cap.height / 2f)
                }
            }
        }
        throw AssertionError("no key described as $description")
    }

    private fun secondFinger(view: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0 },
            MotionEvent.PointerProperties().apply { id = 1 },
        )
        val first = MotionEvent.PointerCoords().apply { x = x0; y = y0 }
        val second = MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 1,
            arrayOf(properties[0]), arrayOf(first), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(down)
        down.recycle()
        val extra = MotionEvent.obtain(
            downTime, downTime + 4, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
            properties, arrayOf(first, second), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(extra)
        extra.recycle()
    }

    private fun dispatch(view: View, action: Int, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun containsEmoji(root: View): Boolean {
        if (root is TextView && root.text.toString().codePoints().anyMatch { it > 0x2100 }) return true
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) if (containsEmoji(root.getChildAt(index))) return true
        }
        return false
    }

    private fun emojiUsesLora(root: View): Boolean {
        if (root is TextView && root.text.toString().codePoints().anyMatch { it > 0x2100 }) {
            return root.typeface != null && root.typeface != android.graphics.Typeface.DEFAULT
        }
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) if (emojiUsesLora(root.getChildAt(index))) return true
        }
        return false
    }
}
