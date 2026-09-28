package app.odicto.mobile

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.FieldPolishSnapshot
import app.odicto.mobile.ime.FieldPolishSnapshotReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FieldPolishSnapshotTest {
    private class Editor(text: String) : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
        var text = text
        var start = 0
        var end = 0
        var partial = false
        var truncated = false
        var refuseRestore = false
        var styled: CharSequence? = null
        var selects = 0

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            this.text = if (truncated) this@Editor.text.take(3) else styled ?: this@Editor.text
            startOffset = 0
            partialStartOffset = if (partial) 0 else -1
            selectionStart = this@Editor.start
            selectionEnd = this@Editor.end
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.selectAll) return false
            selects++
            start = 0
            end = text.length
            return true
        }

        override fun getSelectedText(flags: Int): CharSequence = text.substring(minOf(start, end), maxOf(start, end))
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = text.substring(0, minOf(start, end)).takeLast(length)
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = text.substring(maxOf(start, end)).take(length)

        override fun setSelection(start: Int, end: Int): Boolean {
            if (refuseRestore && start != 0) return false
            this.start = start
            this.end = end
            return true
        }
    }

    @Test fun completeTextIsVerifiedWithoutChangingSelection() {
        val editor = Editor("Hello Acme").apply { start = 3; end = 3 }
        assertEquals(FieldPolishSnapshot("Hello Acme", 3, 3), FieldPolishSnapshotReader.capture(editor))
        assertEquals(0, editor.selects)
        assertEquals(3, editor.start)
        assertEquals(3, editor.end)
    }

    @Test fun partialAndOversizedTextAreRefusedBeforeSelectAll() {
        val partial = Editor("some text").apply { this.partial = true }
        assertNull(FieldPolishSnapshotReader.capture(partial))
        assertEquals(0, partial.selects)
        val oversized = Editor("a".repeat(FieldPolishSnapshotReader.MAX_CHARS + 1))
        assertNull(FieldPolishSnapshotReader.capture(oversized))
        assertEquals(0, oversized.selects)
    }

    @Test fun truncatedTextIsRefusedWithoutSelectingTheEditor() {
        val truncated = Editor("complete text").apply { this.truncated = true }
        assertNull(FieldPolishSnapshotReader.capture(truncated))
        assertEquals(0, truncated.selects)
        val unrestorable = Editor("complete text").apply { start = 2; end = 2; refuseRestore = true }
        assertEquals(FieldPolishSnapshot("complete text", 2, 2), FieldPolishSnapshotReader.capture(unrestorable))
        assertEquals(0, unrestorable.selects)
        assertEquals(2, unrestorable.start)
    }

    @Test fun styledTextIsRefusedRatherThanStripped() {
        val editor = Editor("Styled").apply {
            styled = SpannableStringBuilder(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        assertNull(FieldPolishSnapshotReader.capture(editor))
        assertEquals(0, editor.selects)
    }

    @Test fun reversedUnicodeSelectionKeepsDirectionAndExactPayload() {
        val editor = Editor("x👩‍💻e\u0301z").apply { start = 8; end = 1 }
        val captured = FieldPolishSnapshotReader.capture(editor)
        assertEquals(FieldPolishSnapshot("x👩‍💻e\u0301z", 8, 1), captured)
        assertEquals("👩‍💻e\u0301", captured!!.payload)
        assertEquals(1, captured.rangeStart)
        assertEquals(8, captured.rangeEnd)
        assertEquals(6 to 1, captured.replacementSelection("👨‍💻"))
        assertEquals(0, editor.selects)
    }

    @Test fun selectionInsideSurrogatePairIsRefusedWithoutExpandingPayload() {
        val editor = Editor("x😀z").apply { start = 2; end = 3 }
        assertNull(FieldPolishSnapshotReader.capture(editor))
        assertEquals(0, editor.selects)
    }

    @Test fun revisedSelectionRemainsOnGraphemeBoundaries() {
        val source = "a👩‍💻b"
        val mapped = FieldPolishSnapshotReader.mapSelection(FieldPolishSnapshot(source, 4, 4), source)
        assertTrue(mapped.first == 1 || mapped.first == 6)
        assertEquals(mapped.first, mapped.second)
        val full = FieldPolishSnapshotReader.mapSelection(FieldPolishSnapshot(source, 0, source.length), "A👩‍💻b")
        assertEquals(0 to 7, full)
    }
}
