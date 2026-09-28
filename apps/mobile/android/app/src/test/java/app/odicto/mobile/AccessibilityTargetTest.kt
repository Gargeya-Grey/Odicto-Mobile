package app.odicto.mobile

import android.text.InputType
import app.odicto.mobile.accessibility.TextSplice
import app.odicto.mobile.ime.EditorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTargetTest {
    @Test fun insertsAtTheCursorAndPreservesSurroundingText() {
        assertEquals("hello world", TextSplice.merge("hello ", 6, 6, "world"))
    }
    @Test fun replacesOnlyTheSelectedRange() {
        assertEquals("hi there", TextSplice.merge("hello there", 0, 5, "hi"))
    }
    @Test fun clampsOutOfRangeSelections() {
        assertEquals("abXY", TextSplice.merge("ab", 99, 99, "XY"))
        assertEquals("XYab", TextSplice.merge("ab", -4, -2, "XY"))
        assertEquals("aXY", TextSplice.merge("ab", 1, 99, "XY"))
    }
    @Test fun refusesToRebuildWhenTheFieldTextIsNotExposed() {
        assertNull(TextSplice.merge(null, 0, 0, "hi"))
    }
    @Test fun caretSitsAfterTheInsertedText() {
        assertEquals(9, TextSplice.caret(6, 6, 3))
        assertEquals(5, TextSplice.caret(2, 6, 3))
        assertEquals(9, TextSplice.caret(99, 6, 3))
    }
    @Test fun accessibilityNodesUseTheSameEligibilityRulesAsTheIme() {
        assertTrue(EditorPolicy.permits(InputType.TYPE_CLASS_TEXT, false))
        assertFalse(EditorPolicy.permits(InputType.TYPE_CLASS_TEXT, true))
        assertFalse(EditorPolicy.permits(InputType.TYPE_CLASS_NUMBER, false))
        assertFalse(EditorPolicy.permits(InputType.TYPE_CLASS_PHONE, false))
        assertFalse(EditorPolicy.permits(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, false))
    }
    @Test fun editorsThatReportNoInputClassAreStillTextFields() {
        // X and other apps expose custom editors with inputType 0 but isEditable true.
        assertTrue(EditorPolicy.permitsNode(0, false, true))
        assertTrue(EditorPolicy.permitsNode(InputType.TYPE_CLASS_TEXT, false, true))
        assertFalse(EditorPolicy.permitsNode(0, false, false))
        assertFalse(EditorPolicy.permitsNode(0, true, true))
        assertFalse(EditorPolicy.permitsNode(InputType.TYPE_CLASS_PHONE, false, true))
        assertFalse(EditorPolicy.permitsNode(InputType.TYPE_CLASS_NUMBER, false, true))
    }
}
