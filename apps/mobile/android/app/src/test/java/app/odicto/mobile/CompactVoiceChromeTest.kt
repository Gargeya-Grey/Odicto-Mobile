package app.odicto.mobile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.overlay.VoiceControlView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompactVoiceChromeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun visible(view: ViewGroup): List<View> = (0 until view.childCount).map { view.getChildAt(it) }.filter { it.visibility == View.VISIBLE }

    @Test fun keyboardHas48dpChromeAndNoCollapseControl() {
        val view = VoiceControlView(context, true)
        view.render(VoiceUi())
        assertEquals(view.dp(48), view.requiredHeight)
        assertFalse(visible(view).any { it.contentDescription == "Collapse voice controls" })
        assertTrue(visible(view).any { it.contentDescription == "Open Odicto settings" })
        for (compact in listOf(false, true)) {
            view.setKeyboardCompact(compact)
            val descriptions = visible(view).map { it.contentDescription }
            assertTrue(descriptions.containsAll(listOf("Raw transcription with Groq", "AI answer", "Live transcription")))
        }
    }

    @Test fun compactKeyboardKeepsCompletedCopyAndRecordingCancelReachable() {
        val view = VoiceControlView(context, true)
        view.setKeyboardCompact(true)
        view.render(VoiceUi(phase = "done", finalText = "Recover me", resultId = "result"))
        assertTrue(visible(view).any { it.contentDescription == "Copy completed result" })
        view.render(VoiceUi(phase = "recording"))
        assertTrue(visible(view).any { it.contentDescription == "Cancel recording" })
        assertTrue(visible(view).any { it.contentDescription == "Finish recording" })
        assertEquals(view.dp(48), view.requiredHeight)
    }

    @Test fun imePlacesMicImmediatelyBesidePolishAtTheEdgeAndLabelsSelection() {
        val owner = org.robolectric.Robolectric.buildService(app.odicto.mobile.ime.OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            val width = (360 * context.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            val row = root.findViewById<ViewGroup>(R.id.ime_top_row)
            val voice = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<VoiceControlView>().single()
            val polish = root.findViewById<View>(R.id.ime_polish)
            val mic = visible(voice).single { it.contentDescription?.startsWith("Hold to speak") == true }
            assertEquals(voice.dp(48), row.height)
            assertEquals(polish.left, voice.right)
            assertEquals(voice.width, mic.right)
            assertEquals(row.width, polish.right)
            service.onUpdateSelection(0, 0, 1, 4, -1, -1)
            assertEquals("Polish selected text", polish.contentDescription)
            service.onUpdateSelection(1, 4, 4, 4, -1, -1)
            assertEquals("Polish all text in this field", polish.contentDescription)
        } finally { owner.destroy() }
    }

    @Test fun completedStatusSeparatesTheOutcomeFromRecoveryDetails() {
        val owner = org.robolectric.Robolectric.buildService(app.odicto.mobile.ime.OdictoImeService::class.java).create()
        val previous = app.odicto.mobile.dictation.VoiceSession.state.value
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            app.odicto.mobile.dictation.VoiceSession.update { VoiceUi(
                phase = "done", finalText = "test result", message = "Insertion unverified · check field before pasting · Copy available · Saved to history",
                delivery = app.odicto.mobile.dictation.DeliveryOutcome.ACCEPTED_UNVERIFIED,
                historyPersistence = app.odicto.mobile.dictation.HistoryPersistence.SAVED,
            ) }
            service.javaClass.getDeclaredMethod("renderTopStatus").apply { isAccessible = true }.invoke(service)
            val status = root.findViewById<android.widget.TextView>(R.id.ime_polish_status)
            assertEquals("Check field before pasting", status.text.toString())
            assertFalse(status.isClickable)
            assertEquals(1, status.maxLines)
            assertEquals(android.view.Gravity.START, status.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
            assertTrue(status.contentDescription.contains("before pasting"))
        } finally {
            owner.destroy()
            app.odicto.mobile.dictation.VoiceSession.update { previous }
        }
    }

    @Test fun sixSlotsKeepPersistentButtonsFixedAndMeterUpdatesKeepLayoutParams() {
        for (compact in listOf(false, true)) {
            val view = VoiceControlView(context, true)
            view.setKeyboardCompact(compact)
            view.render(VoiceUi())
            fun measure() {
                view.measure(View.MeasureSpec.makeMeasureSpec(view.requiredWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(view.requiredHeight, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun positions() = visible(view).filter { it.contentDescription in listOf("Raw transcription with Groq", "AI answer", "Live transcription") }.map { listOf(it.left, it.top, it.right, it.bottom) }
            measure()
            val baseline = positions()
            val width = view.requiredWidth
            for (state in listOf(VoiceUi(phase = "connecting"), VoiceUi(phase = "recording"), VoiceUi(phase = "recording", locked = true), VoiceUi(phase = "processing"), VoiceUi(phase = "done", finalText = "synthetic", resultId = "a"), VoiceUi(phase = "error"), VoiceUi())) {
                view.render(state); measure()
                assertEquals(width, view.requiredWidth)
                assertEquals(baseline, positions())
                val params = visible(view).associateWith { it.layoutParams }
                view.render(state.copy(level = 0.5f)); measure()
                params.forEach { (button, param) -> assertSame(param, button.layoutParams) }
            }
        }
    }

    @Test fun keyboardCopyRechecksPolicyAtTapTimeWithoutChangingResult() {
        val view = VoiceControlView(context, true)
        val notices = mutableListOf<String>()
        var allowed = true
        view.keyboardCopyAllowed = { allowed }
        view.keyboardNotice = { notices += it }
        val state = VoiceUi(phase = "done", finalText = "synthetic", resultId = "copy")
        view.render(state)
        val copy = visible(view).single { it.contentDescription == "Copy completed result" }
        allowed = false
        copy.performClick()
        assertEquals(listOf("Copy unavailable in this field"), notices)
        allowed = true
        copy.performClick()
        assertEquals("Copied", notices.last())
        assertEquals(View.VISIBLE, copy.visibility)
    }

    @Test fun repeatedMicRefusalsProduceFreshKeyboardNotices() {
        val previous = app.odicto.mobile.dictation.VoiceSession.state.value
        try {
            app.odicto.mobile.dictation.VoiceSession.update { VoiceUi() }
            app.odicto.mobile.dictation.DictationCoordinator.editorClosed(true)
            val view = VoiceControlView(context, true)
            val notices = mutableListOf<String>()
            view.keyboardNotice = { notices += it }
            val hold = view.javaClass.getDeclaredField("beginHold").apply { isAccessible = true }.get(view) as Runnable
            hold.run()
            hold.run()
            assertEquals(listOf("Voice unavailable in this field", "Voice unavailable in this field"), notices)
            assertEquals("idle", app.odicto.mobile.dictation.VoiceSession.state.value.phase)
        } finally { app.odicto.mobile.dictation.VoiceSession.update { previous } }
    }

    @Test fun floatingControlRetainsItsIdleDimensions() {
        val view = VoiceControlView(context)
        view.render(VoiceUi())
        assertEquals(view.dp(72), view.requiredWidth)
        assertEquals(view.dp(72), view.requiredHeight)
    }
}
