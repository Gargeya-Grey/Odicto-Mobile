package app.odicto.mobile

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.overlay.OverlayPresentation
import app.odicto.mobile.overlay.VoiceControlView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayBehaviorTest {
    @Before fun setUp() { VoiceSession.update { VoiceUi() }; OverlayPresentation.collapse() }
    @After fun tearDown() { VoiceSession.update { VoiceUi() }; DictationCoordinator.editorClosed() }

    private fun attach(): Pair<Activity, VoiceControlView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = VoiceControlView(activity)
        activity.setContentView(view, FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight))
        return activity to view
    }

    private fun mic(view: VoiceControlView): View = (0 until view.childCount).map { view.getChildAt(it) }.single {
        (it.layoutParams as FrameLayout.LayoutParams).width == view.dp(56)
    }

    private fun touch(target: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { target.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test @Config(qualifiers = "ar")
    fun aRightToLeftLocaleDoesNotMirrorTheMicrophoneOrItsControls() {
        val (_, view) = attach()
        assertEquals(View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
        view.measure(View.MeasureSpec.makeMeasureSpec(view.requiredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.requiredHeight, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bare = mic(view)
        assertEquals("the microphone sits where its margin says, not mirrored to the other edge",
            (bare.layoutParams as FrameLayout.LayoutParams).leftMargin, bare.left)
    }

    @Test fun aCancelledDragStillSavesWhereTheUserLeftTheMicrophone() {
        val (_, view) = attach()
        var started = 0; var moved = 0; var ended = 0
        view.dragStart = { started++ }
        view.drag = { _, _ -> moved++ }
        view.dragEnd = { ended++ }
        val microphone = mic(view)
        val down = SystemClock.uptimeMillis()
        touch(microphone, MotionEvent.ACTION_DOWN, down, 20f, 20f)
        touch(microphone, MotionEvent.ACTION_MOVE, down, 120f, 20f)
        touch(microphone, MotionEvent.ACTION_MOVE, down, 160f, 40f)
        assertEquals(1, started)
        assertTrue(moved > 0)
        touch(microphone, MotionEvent.ACTION_CANCEL, down, 160f, 40f)
        assertEquals("the system took the gesture mid-drag", 1, ended)
    }

    @Test fun aTapThatNeverMovesDoesNotSaveAPosition() {
        val (_, view) = attach()
        var ended = 0
        view.dragEnd = { ended++ }
        val microphone = mic(view)
        val down = SystemClock.uptimeMillis()
        touch(microphone, MotionEvent.ACTION_DOWN, down, 20f, 20f)
        touch(microphone, MotionEvent.ACTION_CANCEL, down, 20f, 20f)
        assertEquals(0, ended)
    }

    @Test fun startingADragFoldsAnOpenCardAwayWithoutMovingTheMicrophone() {
        val (_, view) = attach()
        var offsets = mutableListOf<Int>()
        view.resize = { _, _, offset -> offsets += offset }
        view.setPlacement(view.dp(180), view.dp(411))
        val result = VoiceUi(phase = "done", resultId = "x", finalText = "kept", inserted = true)
        VoiceSession.update { result }
        view.render(result)
        OverlayPresentation.reopen(); view.render(result)
        val openOffset = offsets.last()
        val microphone = mic(view)
        val down = SystemClock.uptimeMillis()
        touch(microphone, MotionEvent.ACTION_DOWN, down, 20f, 20f)
        touch(microphone, MotionEvent.ACTION_MOVE, down, 160f, 20f)
        assertFalse("the card folds when a drag starts", OverlayPresentation.options)
        assertEquals("a retained result survives the fold", "kept", VoiceSession.state.value.finalText)
        // The collapsed window is the bare 72dp one, and the microphone is at its 8dp offset: nothing to chase.
        assertEquals(view.dp(8), offsets.last())
        assertNotEquals(openOffset, offsets.last())
    }

    @Test fun aResultReopenedAfterItsMomentPassedIsCaptionedAsTheLastResult() {
        val (_, view) = attach()
        DictationCoordinator.editorReady()
        val result = VoiceUi(phase = "done", resultId = "old", finalText = "Retained", inserted = true, message = "Inserted")
        VoiceSession.update { result }
        view.render(result)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500))
        OverlayPresentation.reopen(); view.render(result)
        val captions = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<android.widget.LinearLayout>()
            .flatMap { panel -> (0 until panel.childCount).map { panel.getChildAt(it) } }.filterIsInstance<TextView>()
        assertTrue("the old verdict is not repeated as if current", captions.none { it.text.toString() == "Inserted" })
        assertTrue(captions.any { it.text.toString() == "Last result" })
    }
}
