package app.odicto.mobile

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.ime.EditorKeyboard
import app.odicto.mobile.ime.KeyboardExpansion
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.OdictoKeyboardView.Page
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditorKeyboardTest {
    private fun editor(type: Int) = EditorInfo().apply { inputType = type }
    private fun labels(view: View): Set<String> = setOfNotNull(view.contentDescription?.toString()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }.toSet() else emptySet()

    @Test fun fieldClassAndNumericFlagsChooseOnlyRelevantSymbols() {
        val number = InputType.TYPE_CLASS_NUMBER
        val signed = InputType.TYPE_NUMBER_FLAG_SIGNED
        val decimal = InputType.TYPE_NUMBER_FLAG_DECIMAL
        assertEquals(Page.NUMPAD, EditorKeyboard.page(editor(number)))
        assertEquals(Page.NUMPAD_SIGNED, EditorKeyboard.page(editor(number or signed)))
        assertEquals(Page.NUMPAD_DECIMAL, EditorKeyboard.page(editor(number or decimal)))
        assertEquals(Page.NUMPAD_SIGNED_DECIMAL, EditorKeyboard.page(editor(number or signed or decimal)))
        assertEquals(Page.NUMPAD, EditorKeyboard.page(editor(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
        assertEquals(Page.PHONE, EditorKeyboard.page(editor(InputType.TYPE_CLASS_PHONE)))
        assertEquals(Page.LETTERS, EditorKeyboard.page(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)))
        assertEquals(Page.LETTERS, EditorKeyboard.page(editor(InputType.TYPE_CLASS_DATETIME)))
        assertEquals(Page.LETTERS, EditorKeyboard.page(null))
    }

    @Test fun numericFocusShowsKeypadImmediatelyAndTextFocusRestoresCompactStart() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            val expansion = service.javaClass.getDeclaredField("expansion").apply { isAccessible = true }
                .get(service) as KeyboardExpansion
            val toggle = root.findViewById<View>(R.id.ime_keyboard_toggle)
            val number = editor(InputType.TYPE_CLASS_NUMBER)
            service.onStartInput(number, false)
            service.onStartInputView(number, false)
            assertTrue(expansion.isExpanded)
            assertFalse(toggle.isEnabled)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.ime_keyboard).visibility)
            assertEquals((0..9).map { it.toString() }.toSet() + setOf("Backspace", "Done"),
                labels(root.findViewById(R.id.ime_keyboard)))
            val text = editor(InputType.TYPE_CLASS_TEXT)
            service.onStartInput(text, false)
            service.onStartInputView(text, false)
            assertFalse(expansion.isExpanded)
            assertTrue(toggle.isEnabled)
            assertTrue(labels(root.findViewById(R.id.ime_keyboard)).contains("q"))
            service.onStartInput(editor(InputType.TYPE_CLASS_PHONE), false)
            assertTrue(expansion.isExpanded)
            assertTrue(labels(root.findViewById(R.id.ime_keyboard)).containsAll(setOf("+", "*", "#")))
            service.onStartInput(editor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD), false)
            assertTrue(expansion.isExpanded)
        } finally { owner.destroy() }
    }
}
