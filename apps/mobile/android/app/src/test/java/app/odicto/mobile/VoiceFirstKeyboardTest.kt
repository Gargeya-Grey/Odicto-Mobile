package app.odicto.mobile

import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.ime.KeyboardController
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class VoiceFirstKeyboardTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let { visible(it) } ?: true)

    /** The window keeps one height; the keyboard the user sees is what lies below the inset line. */
    private fun shown(service: OdictoImeService, root: View): Int {
        val expansion = service.javaClass.getDeclaredField("expansion").apply { isAccessible = true }.get(service)
            as app.odicto.mobile.ime.KeyboardExpansion
        return root.height - expansion.contentTop()
    }

    private fun measure(view: View, width: Int = 412) {
        repeat(3) {
            // These service roots are detached: there is no ViewRootImpl to propagate layout
            // requests after nested presentation changes, so drive the complete layout pass.
            descendants(view).forEach { it.forceLayout() }
            view.measure(View.MeasureSpec.makeMeasureSpec((width * view.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }

    @Test fun focusStartsWithVoiceBarAndToggleExpandsThenResetsForAnotherEditor() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            service.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
            measure(root)
            val collapsedHeight = shown(service, root)
            assertFalse("letter grid must start hidden", visible(root.findViewById<View>(R.id.ime_keyboard)))
            val toggle = descendants(root).single { it.contentDescription == "Show keyboard" }
            assertTrue(visible(toggle))
            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            measure(root)
            assertTrue(visible(root.findViewById<View>(R.id.ime_keyboard)))
            assertTrue(shown(service, root) > collapsedHeight + 100)
            assertEquals("Hide keyboard", toggle.contentDescription)
            service.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }, false)
            measure(root)
            assertEquals(collapsedHeight, shown(service, root))
            assertEquals("Show keyboard", toggle.contentDescription)
            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            measure(root)
            assertTrue("manual keyboard remains available for phone fields", visible(root.findViewById<View>(R.id.ime_keyboard)))
            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            measure(root)
            assertEquals(collapsedHeight, shown(service, root))
        } finally { owner.destroy() }
    }

    @Test fun emojiTapsStayOnTheSelectedCategoryAndRespectChangedEditorPolicy() {
        val previous = VoiceSession.state.value
        VoiceSession.update { VoiceUi() }
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
            val connection = BaseInputConnection(View(service), true)
            val controller = KeyboardController(service, root) { connection }
            val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 }
            controller.onStartInput(info)
            measure(root)
            root.findViewById<View>(R.id.function_emoji).performClick()
            val panel = root.findViewById<View>(R.id.emoji_panel)
            descendants(panel).filterIsInstance<TextView>().single { it.text == "Smileys" }.performClick()
            val cells = descendants(panel).filterIsInstance<TextView>().filter { it.contentDescription?.startsWith("Emoji ") == true && it.text.toString().codePoints().anyMatch { cp -> cp > 0x2000 } }
            assertTrue("a category needs substantially more than 16 choices", cells.size >= 32)
            val first = cells[0]
            val second = cells[1]
            first.performClick()
            assertEquals(View.VISIBLE, panel.visibility)
            second.performClick()
            assertEquals(first.text.toString() + second.text.toString(), connection.editable.toString())
            assertEquals(View.VISIBLE, panel.visibility)
            assertTrue("must retain the same browsed grid after insertion", descendants(panel).contains(second))
            info.inputType = InputType.TYPE_CLASS_NUMBER
            first.performClick()
            assertEquals("protected fields must reject subsequent emoji taps", first.text.toString() + second.text.toString(), connection.editable.toString())
            controller.dispose()
        } finally { owner.destroy(); VoiceSession.update { previous } }
    }

    @Test fun basicPunctuationIsReachableWithoutReplacingExistingSymbols() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val keyboard = OdictoKeyboardView(owner.get())
            val found = mutableSetOf<String>()
            for (page in listOf(OdictoKeyboardView.Page.NUMBERS, OdictoKeyboardView.Page.SYMBOLS)) {
                keyboard.showPage(page)
                measure(keyboard)
                found += descendants(keyboard).mapNotNull { it.contentDescription?.toString() }
            }
            val required = listOf("\"", "'", "#", "%", "*", "+", "=", "_", "[", "]", "{", "}", "\\", "/", "|", "~", "^", "<", ">", "₹", "€", "£", "•", "→", "©", "°")
            assertTrue("missing punctuation: ${required.filterNot { it in found }}", found.containsAll(required))
        } finally { owner.destroy() }
    }

    @Test fun polishRecoveryCanBeRecoveredFromVoiceBarAndDismissRestoresIt() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            service.onWindowShown()
            measure(root)
            val collapsedHeight = shown(service, root)
            val coordinator = service.javaClass.getDeclaredField("polish").apply { isAccessible = true }.get(service)
                as app.odicto.mobile.ime.FieldPolishCoordinator
            coordinator.javaClass.getDeclaredMethod("retain", Long::class.javaPrimitiveType, String::class.java)
                .apply { isAccessible = true }.invoke(coordinator, 1L, "Synthetic recovery")
            shadowOf(Looper.getMainLooper()).idle()
            measure(root)
            val copy = descendants(root).filterIsInstance<android.widget.Button>().single { it.text == "Copy completed result" }
            assertTrue(visible(copy))
            assertTrue(shown(service, root) > collapsedHeight)
            assertFalse(visible(root.findViewById<View>(R.id.ime_keyboard)))
            descendants(root).filterIsInstance<android.widget.Button>().single { it.text == "Dismiss" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            measure(root)
            assertEquals(collapsedHeight, shown(service, root))
            assertEquals("Show keyboard", root.findViewById<View>(R.id.ime_keyboard_toggle).contentDescription)
        } finally { owner.destroy() }
    }
}
