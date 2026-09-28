package app.odicto.mobile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import app.odicto.mobile.ime.BackspacePace
import app.odicto.mobile.ime.KeyboardController
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HeldBackspaceSafetyTest {
    @Test fun unrelatedCollapsedSelectionStopsAnOngoingHoldBeforeItsNextDeletion() = withFixture { fixture ->
        fixture.startHold()
        val editor = fixture.editor
        editor.moveExternally(5, 5)
        fixture.deliver(5 to 5)
        val text = editor.text
        val writes = editor.mutations

        fixture.advance(BackspacePace.LETTER_INTERVAL_MS * 4)
        fixture.release("Backspace")
        fixture.advance(BackspacePace.HOLD_MS)

        assertEquals("The old hold must not issue a mutation at the new cursor", writes, editor.mutations)
        assertEquals(text, editor.text)
        assertEquals(5 to 5, editor.selection)
    }

    @Test fun staleOwnDeletionCallbacksDoNotRewindThePredictedCursor() = withFixture { fixture ->
        fixture.startHold()
        val editor = fixture.editor
        assertEquals(listOf(20 to 20, 19 to 19, 18 to 18), editor.callbacks)
        fixture.deliver(editor.callbacks.removeAt(0))
        fixture.deliver(editor.callbacks.removeAt(0))
        assertEquals(18 to 18, editor.selection)

        fixture.advance(BackspacePace.LETTER_INTERVAL_MS)
        assertEquals("Known own echoes must not cancel a valid hold", 4, editor.deletions)
        assertEquals(17 to 17, editor.selection)
        fixture.release("Backspace")
        fixture.dragSpaceRight()

        assertEquals("A real cursor gesture must use the latest predicted deletion endpoint", listOf(18 to 18), editor.requestedSelections)
        assertEquals("abcdefghijklmnopquvwxyz", editor.text)
    }

    @Test fun movingToAnOldAcknowledgedPositionStillStopsTheHold() = withFixture { fixture ->
        fixture.startHold()
        val editor = fixture.editor
        editor.moveExternally(20, 20)
        fixture.deliver(20 to 20)
        val before = editor.text
        val mutations = editor.mutations
        fixture.advance(BackspacePace.LETTER_INTERVAL_MS * 3)
        fixture.release("Backspace")
        assertEquals(mutations, editor.mutations)
        assertEquals(before, editor.text)
    }

    @Test fun replacingTheInputConnectionStopsTheOldHoldBeforeWritingToTheNewEditor() = withFixture { fixture ->
        fixture.startHold()
        val original = fixture.editor
        val oldText = original.text
        val oldWrites = original.mutations
        val replacement = RecordingConnection(View(fixture.service), "replacement text", 8)
        fixture.editor = replacement

        fixture.advance(BackspacePace.LETTER_INTERVAL_MS * 4)
        fixture.release("Backspace")
        fixture.advance(BackspacePace.HOLD_MS)

        assertEquals("A repeat cannot migrate to a different InputConnection", 0, replacement.mutations)
        assertEquals("replacement text", replacement.text)
        assertEquals(8 to 8, replacement.selection)
        assertEquals(oldWrites, original.mutations)
        assertEquals(oldText, original.text)
    }

    @Test fun aNewNoncollapsedSelectionStopsTheHoldWithoutDeletingAroundIt() = withFixture { fixture ->
        fixture.startHold()
        val editor = fixture.editor
        editor.moveExternally(3, 7)
        fixture.deliver(3 to 7)
        val text = editor.text
        val writes = editor.mutations

        fixture.advance(BackspacePace.LETTER_INTERVAL_MS * 4)
        fixture.release("Backspace")
        fixture.advance(BackspacePace.HOLD_MS)

        assertEquals("A repeat must neither replace nor delete around a newly selected range", writes, editor.mutations)
        assertEquals(text, editor.text)
        assertEquals(3 to 7, editor.selection)
        assertEquals("defg", editor.getSelectedText(0).toString())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val serviceController = Robolectric.buildService(OdictoImeService::class.java).create()
        val fixture = Fixture(serviceController.get())
        try {
            test(fixture)
        } finally {
            fixture.controller.cancelTouch()
            fixture.controller.onFinishInput()
            serviceController.destroy()
        }
    }

    private class Fixture(val service: OdictoImeService) {
        private val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val keyboard: OdictoKeyboardView = root.findViewById(R.id.ime_keyboard)
        var editor = RecordingConnection(View(service), "abcdefghijklmnopqrstuvwxyz", 20)
        val controller = KeyboardController(service, root) { editor }
        private var downTime = 0L

        init {
            controller.onStartInput(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                initialSelStart = 20
                initialSelEnd = 20
            })
            val width = (360 * service.resources.displayMetrics.density).toInt()
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
            )
            keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
            press("q")
            release("q")
            assertEquals("abcdefghijklmnopqrstquvwxyz", editor.text)
            deliver(editor.callbacks.single())
            editor.callbacks.clear()
            editor.mutations = 0
        }

        fun startHold() {
            press("Backspace")
            advance(BackspacePace.HOLD_MS + BackspacePace.LETTER_INTERVAL_MS + 1)
            assertEquals("Touch-down and two timer repeats must really reach the editor", 3, editor.deletions)
            assertEquals("abcdefghijklmnopqruvwxyz", editor.text)
            assertEquals(18 to 18, editor.selection)
        }

        fun deliver(selection: Pair<Int, Int>) {
            Handler(Looper.getMainLooper()).post {
                controller.onSelectionChanged(selection.first, selection.second)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        fun advance(millis: Long) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        }

        fun press(description: String) {
            downTime = SystemClock.uptimeMillis()
            val (x, y) = centerOf(description)
            dispatch(MotionEvent.ACTION_DOWN, x, y)
        }

        fun release(description: String) {
            val (x, y) = centerOf(description)
            dispatch(MotionEvent.ACTION_UP, x, y)
        }

        fun dragSpaceRight() {
            val (x, y) = centerOf("Space")
            val density = service.resources.displayMetrics.density
            press("Space")
            dispatch(MotionEvent.ACTION_MOVE, x + 8f * density, y)
            dispatch(MotionEvent.ACTION_MOVE, x + 12f * density, y)
            dispatch(MotionEvent.ACTION_UP, x + 12f * density, y)
        }

        private fun dispatch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                assertTrue(keyboard.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        private fun centerOf(description: String): Pair<Float, Float> {
            for (row in 0 until keyboard.childCount) {
                val line = keyboard.getChildAt(row) as LinearLayout
                for (column in 0 until line.childCount) {
                    val cap = line.getChildAt(column)
                    if (cap.contentDescription == description) {
                        return (line.left + cap.left + cap.width / 2f) to (line.top + cap.top + cap.height / 2f)
                    }
                }
            }
            throw AssertionError("Missing key: $description")
        }
    }

    private class RecordingConnection(view: View, initialText: String, initialCursor: Int) : BaseInputConnection(view, true) {
        var text = initialText
            private set
        var selection = initialCursor to initialCursor
            private set
        var mutations = 0
        var deletions = 0
            private set
        val callbacks = mutableListOf<Pair<Int, Int>>()
        val requestedSelections = mutableListOf<Pair<Int, Int>>()

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = text.take(selection.first).takeLast(length)
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = text.drop(selection.second).take(length)
        override fun getSelectedText(flags: Int): CharSequence = text.substring(selection.first, selection.second)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText {
            val from = (selection.first - beforeLength).coerceAtLeast(0)
            val to = (selection.second + afterLength).coerceAtMost(text.length)
            return android.view.inputmethod.SurroundingText(text.substring(from, to), selection.first - from, selection.second - from, from)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            mutations++
            val inserted = text?.toString().orEmpty()
            val start = selection.first
            this.text = this.text.replaceRange(start, selection.second, inserted)
            selection = (start + inserted.length) to (start + inserted.length)
            callbacks += selection
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            mutations++
            deletions++
            val start = selection.first
            val end = selection.second
            val from = (start - beforeLength).coerceAtLeast(0)
            val to = (end + afterLength).coerceAtMost(text.length)
            text = text.removeRange(end, to).removeRange(from, start)
            selection = from to (end - (start - from))
            callbacks += selection
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val start = selection.first
            val end = selection.second
            val from = text.offsetByCodePoints(start, -beforeLength.coerceAtMost(text.codePointCount(0, start)))
            val to = text.offsetByCodePoints(end, afterLength.coerceAtMost(text.codePointCount(end, text.length)))
            return deleteSurroundingText(start - from, to - end)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            requestedSelections += start to end
            moveExternally(start, end)
            callbacks += selection
            return true
        }

        fun moveExternally(start: Int, end: Int) {
            require(start in 0..text.length && end in start..text.length)
            selection = start to end
        }
    }
}
