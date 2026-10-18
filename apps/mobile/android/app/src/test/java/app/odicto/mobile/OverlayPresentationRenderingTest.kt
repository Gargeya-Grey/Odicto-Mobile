package app.odicto.mobile

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.overlay.VoiceControlView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayPresentationRenderingTest {
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun buttons(view: VoiceControlView) = (0 until view.childCount).map { view.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
    private fun reopen(view: VoiceControlView) {
        val mic = buttons(view).single { it.contentDescription?.contains("Double tap") == true }
        val node = mic.createAccessibilityNodeInfo()
        val action = node.actionList.single { it.label == "Show voice options" }
        assertTrue(mic.performAccessibilityAction(action.id, null))
    }
    private fun attached(test: (VoiceControlView, Activity) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        app.odicto.mobile.dictation.VoiceSession.update { VoiceUi() }
        advance(1)
        try {
            val view = VoiceControlView(owner.get())
            owner.get().setContentView(view, FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight))
            view.resize = { w, h, _ -> view.layoutParams = view.layoutParams.apply { width = w; height = h } }
            test(view, owner.get())
        } finally {
            owner.pause().stop().destroy()
            app.odicto.mobile.dictation.VoiceSession.update { VoiceUi() }
            app.odicto.mobile.dictation.DictationCoordinator.editorClosed()
            advance(1)
        }
    }
    private fun publish(view: VoiceControlView, state: VoiceUi) {
        app.odicto.mobile.dictation.VoiceSession.update { state }
        advance(1)
        view.render(state)
    }

    @Test fun historyAndMeterUpdatesDoNotRenewTerminalDeadline() = attached { view, _ ->
        val result = VoiceUi(phase = "done", resultId = "history", finalText = "Retained")
        publish(view, result)
        advance(2000)
        publish(view, result.copy(message = "History unavailable", historyPersistence = app.odicto.mobile.dictation.HistoryPersistence.FAILED, level = 0.5f))
        advance(1000)
        assertEquals(view.dp(72), view.layoutParams.width)
        assertEquals("Retained", app.odicto.mobile.dictation.VoiceSession.state.value.finalText)
    }

    @Test fun shortViewportKeepsOptionsAndCopyInsideTheSingleRow() = attached { view, _ ->
        view.setOverlayAvailableHeight(view.dp(120))
        publish(view, VoiceUi(phase = "done", resultId = "short", finalText = "Synthetic result"))
        reopen(view)
        assertEquals(view.dp(72), view.requiredHeight)
        for (button in buttons(view)) {
            val params = button.layoutParams as FrameLayout.LayoutParams
            assertTrue(params.topMargin >= 0)
            assertTrue(params.topMargin + params.height <= view.requiredHeight)
        }
        assertTrue(buttons(view).any { it.contentDescription == "Copy available text" })
    }

    @Test fun busyRecordingSupersedesOldTerminalTimer() = attached { view, _ ->
        publish(view, VoiceUi(phase = "done", resultId = "old", finalText = "Old"))
        advance(2000)
        publish(view, VoiceUi(phase = "recording", mode = "raw"))
        advance(5000)
        // The window is exactly as wide as what is open: a 288dp card plus the microphone and margins.
        assertEquals(view.dp(296), view.layoutParams.width)
        assertTrue(buttons(view).any { it.contentDescription == "Finish recording" })
    }

    @Test fun backRetainsAllOutcomesAndOptionsReopenRawAiAndLiveCopy() = attached { view, _ ->
        val clipboard = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        for ((index, mode) in listOf("raw", "ai", "live").withIndex()) {
            app.odicto.mobile.dictation.DictationCoordinator.editorReady()
            val result = VoiceUi(phase = if (mode == "live") "error" else "done", mode = mode,
                resultId = "copy-$index", finalText = "Retained $mode", interim = if (mode == "live") "tail" else "",
                delivery = app.odicto.mobile.dictation.DeliveryOutcome.REJECTED_STALE)
            publish(view, result)
            buttons(view).single { it.contentDescription == "Collapse voice controls" }.performClick()
            assertEquals(view.dp(72), view.layoutParams.width)
            assertEquals(result, app.odicto.mobile.dictation.VoiceSession.state.value)
            reopen(view)
            assertTrue(buttons(view).map { it.contentDescription }.containsAll(listOf("Raw transcription with Groq", "AI answer", "Live transcription")))
            buttons(view).single { it.contentDescription == "Copy available text" }.performClick()
            assertEquals(if (mode == "live") "Retained live tail" else "Retained $mode", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(result, app.odicto.mobile.dictation.VoiceSession.state.value)
        }
    }

    @Test fun protectedCopyRefusesAtTapTimeAndKeepsDimMicAndResult() = attached { view, _ ->
        val coordinator = app.odicto.mobile.dictation.DictationCoordinator
        val clipboard = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("before", "Unchanged"))
        coordinator.editorReady()
        val result = VoiceUi(phase = "done", resultId = "protected", finalText = "Retained")
        publish(view, result)
        val copy = buttons(view).single { it.contentDescription == "Copy available text" }
        coordinator.editorClosed(true)
        copy.performClick()
        assertEquals("Unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(result, app.odicto.mobile.dictation.VoiceSession.state.value)
        advance(3000)
        assertEquals(view.dp(72), view.layoutParams.width)
        assertEquals(0.5f, buttons(view).single().alpha)
    }

    @Test fun terminalStatesExpireAcrossDetachedAndRecreatedViews() = attached { view, activity ->
        for (phase in listOf("done", "error", "notice")) {
            publish(view, VoiceUi(phase = "recording"))
            activity.setContentView(FrameLayout(activity))
            val result = VoiceUi(phase = phase, resultId = "hidden-$phase", finalText = "Retained")
            app.odicto.mobile.dictation.VoiceSession.update { result }
            // A finished result folds after 3s; an error or notice is given 5s to be read.
            advance(if (phase == "done") 4000 else 6000)
            val replacement = VoiceControlView(activity)
            activity.setContentView(replacement)
            assertEquals(replacement.dp(72), replacement.requiredWidth)
            assertEquals(replacement.dp(72), replacement.requiredHeight)
            assertEquals(result, app.odicto.mobile.dictation.VoiceSession.state.value)
            reopen(replacement)
            assertTrue(buttons(replacement).any { it.contentDescription == "Copy available text" })
        }
    }

    @Test fun clipboardFailurePreservesRecovery() = attached { _, activity ->
        val context = object : android.content.ContextWrapper(activity) {
            override fun getSystemService(name: String): Any? {
                if (name == android.content.Context.CLIPBOARD_SERVICE) throw SecurityException("Unavailable")
                return super.getSystemService(name)
            }
        }
        val view = VoiceControlView(context)
        app.odicto.mobile.dictation.DictationCoordinator.editorReady()
        val result = VoiceUi(phase = "done", resultId = "clipboard-failure", finalText = "Recover")
        publish(view, result)
        buttons(view).single { it.contentDescription == "Copy available text" }.performClick()
        assertEquals("Could not copy result", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals(result, app.odicto.mobile.dictation.VoiceSession.state.value)
        assertTrue(buttons(view).any { it.contentDescription == "Copy available text" })
    }

    @org.robolectric.annotation.Implements(android.view.accessibility.AccessibilityManager::class)
    class ExtendedTimeout : org.robolectric.shadows.ShadowAccessibilityManager() {
        @org.robolectric.annotation.Implementation(minSdk = 29)
        fun getRecommendedTimeoutMillis(originalTimeout: Int, uiContentFlags: Int): Int = 6000
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [35], shadows = [ExtendedTimeout::class])
    fun accessibilityTimeoutExtendsActualCollapse() = attached { view, _ ->
        publish(view, VoiceUi(phase = "notice", resultId = "accessible", message = "Notice"))
        advance(3000)
        // The window is exactly as wide as what is open: a 288dp card plus the microphone and margins.
        assertEquals(view.dp(296), view.layoutParams.width)
        advance(3000)
        assertEquals(view.dp(72), view.layoutParams.width)
    }

    @Test fun terminalResultCollapsesActualSurfaceAfterThreeSeconds() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = VoiceControlView(owner.get())
            owner.get().setContentView(view, FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight))
            view.resize = { w, h, _ -> view.layoutParams = view.layoutParams.apply { width = w; height = h } }
            view.render(VoiceUi(phase = "done", resultId = "completion", finalText = "Retained", message = "Check field before pasting"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2999))
            // The window is exactly as wide as what is open: a 288dp card plus the microphone and margins.
            assertEquals(view.dp(296), view.layoutParams.width)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            view.measure(View.MeasureSpec.makeMeasureSpec(view.layoutParams.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(view.layoutParams.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertEquals(view.dp(72), view.width)
            assertEquals(view.dp(72), view.height)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun theOpenOptionsAndTheLastResultFoldAwayOnTheirOwn() = attached { view, _ ->
        publish(view, VoiceUi(phase = "done", resultId = "old", finalText = "Old words"))
        advance(4000)
        assertEquals("the result card has had its moment", view.dp(72), view.layoutParams.width)
        reopen(view)
        assertTrue("options are open, with the last result", view.layoutParams.width > view.dp(72))
        assertTrue(buttons(view).any { it.contentDescription == "Copy available text" })
        advance(7000)
        assertTrue("still open before the idle time", view.layoutParams.width > view.dp(72))
        advance(1500)
        assertEquals("folded away, taking the card with it", view.dp(72), view.layoutParams.width)
        assertEquals("the result itself is kept for Copy", "Old words", app.odicto.mobile.dictation.VoiceSession.state.value.finalText)
        reopen(view)
        assertTrue("opening again brings it back", view.layoutParams.width > view.dp(72))
    }

    @Test fun touchingTheOpenOptionsKeepsThemOpenLonger() = attached { view, _ ->
        publish(view, VoiceUi(phase = "done", resultId = "old", finalText = "Old words"))
        advance(4000)
        reopen(view)
        advance(6000)
        view.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        advance(6000)
        assertTrue("six seconds after the touch it is still open", view.layoutParams.width > view.dp(72))
        advance(3000)
        assertEquals(view.dp(72), view.layoutParams.width)
    }

    @Test fun theOptionsPillEndsExactlyAtTheMicrophoneAndIsAsTallAsIt() = attached { view, _ ->
        reopen(view)
        val pill = (0 until view.childCount).map { view.getChildAt(it) }.single { it.visibility == View.VISIBLE && it.layoutParams.height == view.dp(56) && it.layoutParams.width > view.dp(200) }
        val mic = buttons(view).single { it.contentDescription?.contains("Double tap") == true }
        val pillLp = pill.layoutParams as FrameLayout.LayoutParams
        val micLp = mic.layoutParams as FrameLayout.LayoutParams
        val micCentre = micLp.leftMargin + micLp.width / 2
        val pillEndsAt = if (pillLp.leftMargin < micLp.leftMargin) pillLp.leftMargin + pillLp.width else pillLp.leftMargin
        val micEdge = if (pillLp.leftMargin < micLp.leftMargin) micLp.leftMargin + micLp.width else micLp.leftMargin
        assertEquals("the pill's rounded end is concentric with the disc", micEdge, pillEndsAt)
        assertEquals("same height, so the end is a half circle around it", micLp.height, pillLp.height)
        assertEquals(micLp.topMargin, pillLp.topMargin)
        assertTrue(micCentre > 0)
    }
}