package app.odicto.mobile.ime

import android.text.InputType
import android.text.Selection
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.R
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun descendants(view: View): List<View> = listOf(view) +
    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickRowTest {
    private class Host(view: View) : BaseInputConnection(view, true) {
        val actions = mutableListOf<Int>()
        override fun performEditorAction(actionCode: Int): Boolean { actions += actionCode; return true }
        fun seed(text: String) { editable!!.replace(0, editable!!.length, text); Selection.setSelection(editable, text.length) }
        fun text() = editable.toString()
    }

    private class Fixture(options: Int = 0, type: Int = InputType.TYPE_CLASS_TEXT) : AutoCloseable {
        private val previousVoice = VoiceSession.state.value
        private val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        val root: View = LayoutInflater.from(owner.get()).inflate(R.layout.ime_keyboard, null, false)
        val host = Host(View(owner.get()))
        val controller: KeyboardController
        val quick: OdictoKeyboardView get() = root.findViewById(R.id.ime_quick_keys)
        val info = EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 5; initialSelEnd = 5 }
        init {
            VoiceSession.update { VoiceUi() }
            host.seed("seed ")
            controller = KeyboardController(owner.get(), root) { host }
            controller.onStartInput(info)
        }
        fun keys(): List<View> = descendants(quick).filter { it.contentDescription != null }
        fun tap(label: String) {
            val old = Selection.getSelectionStart(host.editable)
            assertTrue("quick key $label", descendants(quick).single { it.contentDescription == label }.performClick())
            controller.onSelectionChanged(old, old, Selection.getSelectionStart(host.editable), Selection.getSelectionEnd(host.editable), -1, -1)
        }
        override fun close() { controller.dispose(); owner.destroy(); VoiceSession.update { previousVoice } }
    }

    @Test fun theQuickRowSitsInTheAlwaysVisibleHeaderWithTheFiveEditingKeys() {
        Fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).use { f ->
            assertSame(f.root.findViewById<View>(R.id.ime_top_controls), f.quick.parent)
            assertEquals(listOf(",", ".", "Space", "Backspace", "Enter"), f.keys().map { it.contentDescription.toString() })
        }
    }

    @Test fun theQuickRowIsOneFixedRowAndIsNeverSplitOnAWideScreen() {
        Fixture().use { f ->
            val density = f.root.resources.displayMetrics.density
            for (width in listOf(360, 412, 800)) {
                f.quick.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((300 * density).toInt(), View.MeasureSpec.AT_MOST))
                f.quick.layout(0, 0, f.quick.measuredWidth, f.quick.measuredHeight)
                assertEquals((48 * density).toInt(), f.quick.measuredHeight)
                val keys = f.keys()
                val gaps = keys.zipWithNext { a, b -> (b.parent as View).left + b.left - ((a.parent as View).left + a.right) }
                assertTrue("keys stay contiguous at ${width}dp, gaps=$gaps", gaps.all { it <= (6 * density).toInt() })
            }
        }
    }

    @Test fun commaPeriodSpaceAndBackspaceEditTheFieldFromTheQuickRow() {
        Fixture().use { f ->
            f.tap(",")
            assertEquals("seed ,", f.host.text())
            f.tap(".")
            assertEquals("seed ,.", f.host.text())
            f.tap("Space")
            assertTrue(f.host.text().startsWith("seed ,."))
            f.tap("Backspace")
            assertEquals("seed ,.", f.host.text())
        }
    }

    @Test fun aSingleLineFieldShowsDoneOnEnterAsTheGridDoes() {
        Fixture().use { f ->
            assertEquals("Done", f.keys().last().contentDescription.toString())
        }
    }

    @Test fun enterIsANewlineInAPlainMultilineField() {
        Fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).use { f ->
            f.tap("Enter")
            assertEquals("seed \n", f.host.text())
            assertTrue(f.host.actions.isEmpty())
        }
    }

    @Test fun enterBecomesSendWhereTheFieldAsksForSendAndSendsInsteadOfTyping() {
        Fixture(options = EditorInfo.IME_ACTION_SEND).use { f ->
            assertEquals(listOf(",", ".", "Space", "Backspace", "Send"), f.keys().map { it.contentDescription.toString() })
            f.tap("Send")
            assertEquals(listOf(EditorInfo.IME_ACTION_SEND), f.host.actions)
            assertEquals("seed ", f.host.text())
        }
    }

    @Test fun theQuickRowFollowsTheFieldWhenItChanges() {
        Fixture(options = EditorInfo.IME_ACTION_SEND).use { f ->
            f.controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH })
            assertEquals("Search", f.keys().last().contentDescription.toString())
            f.controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE })
            assertEquals("Enter", f.keys().last().contentDescription.toString())
        }
    }
}
