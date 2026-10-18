package app.odicto.mobile.ime

import android.text.InputType
import android.text.Selection
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
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
class PasteChipTest {
    private class Host(view: View) : BaseInputConnection(view, true) {
        fun seed(text: String) { editable!!.replace(0, editable!!.length, text); Selection.setSelection(editable, text.length) }
        fun text() = editable.toString()
    }

    private class Fixture(type: Int = InputType.TYPE_CLASS_TEXT) : AutoCloseable {
        private val previousVoice = VoiceSession.state.value
        private val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        val service: OdictoImeService = owner.get()
        val root: View = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
        val host = Host(View(service))
        val controller: KeyboardController
        val slot: FrameLayout get() = root.findViewById(R.id.ime_paste)
        init {
            VoiceSession.update { VoiceUi() }
            host.seed("seed ")
            controller = KeyboardController(service, root) { host }
            controller.onStartInput(info(type))
        }
        fun info(type: Int) = EditorInfo().apply { inputType = type; initialSelStart = 5; initialSelEnd = 5 }
        fun copied(text: String, token: String = "t-$text", ageMs: Long = 2_000) {
            if (service.pasteSuggestion.offer(text, token, System.currentTimeMillis() - ageMs)) controller.refreshPaste()
        }
        val pill: View get() = slot.getChildAt(0)
        val visible: Boolean get() = slot.visibility == View.VISIBLE
        fun tapQuick(label: String) {
            val quick = root.findViewById<OdictoKeyboardView>(R.id.ime_quick_keys)
            assertTrue(descendants(quick).single { it.contentDescription == label }.performClick())
        }
        override fun close() { controller.dispose(); owner.destroy(); VoiceSession.update { previousVoice } }
    }

    @Test fun aFreshCopyAppearsInTheCentreSlotWithAPreviewAndNothingElseMoves() {
        Fixture().use { f ->
            assertFalse(f.visible)
            f.copied("see you at 5")
            assertTrue(f.visible)
            assertEquals("Paste copied text: see you at 5", f.pill.contentDescription.toString())
            assertSame("it lives in the header row", f.root.findViewById<View>(R.id.ime_top_row), f.slot.parent)
        }
    }

    @Test fun tappingItPastesThroughTheGuardedPathAndSpendsTheOffer() {
        Fixture().use { f ->
            f.copied("hello there")
            assertTrue(f.pill.performClick())
            assertEquals("seed hello there", f.host.text())
            assertFalse(f.visible)
            f.copied("hello there")
            assertFalse("the same copy is not offered again", f.visible)
            f.copied("something new")
            assertTrue("a new copy is", f.visible)
        }
    }

    @Test fun startingToTypeFromTheQuickRowEndsTheOfferForThatCopy() {
        Fixture().use { f ->
            f.copied("hello there")
            f.tapQuick(",")
            assertFalse(f.visible)
            f.controller.onStartInput(f.info(InputType.TYPE_CLASS_TEXT))
            assertFalse("and it does not return in the next field", f.visible)
        }
    }

    @Test fun theOfferFollowsTheUserAcrossFieldsUntilTheyActOrItExpires() {
        Fixture().use { f ->
            f.copied("carry me")
            f.controller.onFinishInput()
            assertFalse("no field, nothing to paste into", f.visible)
            f.controller.onStartInput(f.info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
            assertTrue("a different field in a different app", f.visible)
        }
    }

    @Test fun aProtectedFieldHidesTheOfferWithoutSpendingIt() {
        Fixture().use { f ->
            f.copied("carry me")
            f.controller.onStartInput(f.info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
            assertFalse(f.visible)
            f.controller.onStartInput(f.info(InputType.TYPE_CLASS_TEXT))
            assertTrue(f.visible)
        }
    }

    @Test fun voiceWorkEndsTheOfferAndPasswordLikeTextIsNotPrinted() {
        Fixture().use { f ->
            f.copied("Tr0ub4dor&3xyz")
            assertEquals("Paste copied text", f.pill.contentDescription.toString())
            VoiceSession.update { VoiceUi(phase = "processing", message = "Processing voice") }
            f.service.pasteSuggestion.spend()
            f.controller.refreshPaste()
            assertFalse(f.visible)
        }
    }

    @Test fun theDismissButtonSpendsTheOffer() {
        Fixture().use { f ->
            f.copied("hello there")
            val close = descendants(f.slot).single { it.contentDescription == "Dismiss paste suggestion" }
            assertTrue(close.performClick())
            assertFalse(f.visible)
            assertEquals("seed ", f.host.text())
            f.copied("hello there")
            assertFalse(f.visible)
        }
    }

    @Test fun anExpiredCopyIsNeverShown() {
        Fixture().use { f ->
            f.copied("stale", ageMs = 31_000)
            assertFalse(f.visible)
        }
    }
}
