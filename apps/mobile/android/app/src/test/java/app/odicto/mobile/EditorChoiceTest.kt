package app.odicto.mobile

import app.odicto.mobile.accessibility.EditorChoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fallback may never guess between fields: that would risk typing into the wrong editor. */
class EditorChoiceTest {
    @Test fun aFocusedEditorIsAlwaysChosenWhenOneExists() {
        assertTrue(EditorChoice.picks(count = 3, hasFocusedEditor = true))
    }
    @Test fun aSingleEditorIsUnambiguous() {
        assertTrue(EditorChoice.picks(count = 1, hasFocusedEditor = false))
    }
    @Test fun severalEditorsWithoutFocusAreRefused() {
        assertFalse(EditorChoice.picks(count = 2, hasFocusedEditor = false))
    }
    @Test fun noEditorIsRefused() {
        assertFalse(EditorChoice.picks(count = 0, hasFocusedEditor = false))
        assertFalse(EditorChoice.picks(count = 0, hasFocusedEditor = true))
    }
}
