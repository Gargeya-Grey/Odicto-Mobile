package app.odicto.mobile

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.overlay.OverlayPresentation
import app.odicto.mobile.overlay.VoiceControlView
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * The floating window is sized around the microphone, never the reverse. Rather than check a few hand-picked
 * cases, sweep screen widths, microphone positions and every content state, and require for each:
 *  - the microphone is drawn at exactly the offset the window is positioned for (so it cannot move);
 *  - every piece, and the window, lies on screen;
 *  - no two touch targets overlap.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayLayoutSweepTest {
    private enum class Case(val label: String, val state: VoiceUi, val options: Boolean, val preview: Boolean = true) {
        IDLE("idle", VoiceUi(), false),
        OPTIONS("options", VoiceUi(), true),
        OPTIONS_WITH_RESULT("options+result", VoiceUi(phase = "done", resultId = "r1", finalText = "Some retained text", inserted = true), true),
        RECORDING("recording", VoiceUi(phase = "recording", mode = "raw"), false),
        LOCKED("locked", VoiceUi(phase = "recording", mode = "raw", locked = true), false),
        PROCESSING("processing", VoiceUi(phase = "processing", mode = "ai"), false),
        ERROR("error", VoiceUi(phase = "error", message = "Add your Groq key in Voice settings."), false),
        DONE("done", VoiceUi(phase = "done", resultId = "r2", finalText = "Done text", inserted = true, message = "Inserted"), false),
        COMPACT_RECORDING("compact recording", VoiceUi(phase = "recording", mode = "raw"), false, preview = false),
        COMPACT_DONE("compact done", VoiceUi(phase = "done", resultId = "r3", finalText = "Done text", inserted = true, message = "Inserted"), false, preview = false),
    }

    @Suppress("UNCHECKED_CAST")
    private val preferences = VoicePreferences::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(null) as MutableStateFlow<VoiceSettings>
    private var previous = VoiceSettings()

    @Before fun setUp() {
        previous = preferences.value
        VoiceSession.update { VoiceUi() }
        OverlayPresentation.collapse()
    }

    @After fun tearDown() {
        preferences.value = previous
        VoiceSession.update { VoiceUi() }
        DictationCoordinator.editorClosed()
    }

    private class Layout(val windowLeft: Int, val windowWidth: Int, val windowHeight: Int, val micOffset: Int,
                         val pieces: List<Pair<View, Rect>>, val mic: Pair<View, Rect>)

    private fun measure(activity: Activity, view: VoiceControlView, screenWidth: Int, micLeft: Int, case: Case, overlayHeight: Int): Layout {
        preferences.value = preferences.value.copy(showTextPreview = case.preview)
        view.setOverlayAvailableHeight(overlayHeight)
        view.setPlacement(micLeft, screenWidth)
        VoiceSession.update { case.state }
        OverlayPresentation.collapse()
        view.render(case.state)
        if (case.options) { OverlayPresentation.reopen(); view.render(case.state) }
        var windowWidth = 0; var windowHeight = 0; var micOffset = 0
        view.resize = { w, h, offset -> windowWidth = w; windowHeight = h; micOffset = offset }
        view.setPlacement(micLeft + 1, screenWidth); view.setPlacement(micLeft, screenWidth)
        if (windowWidth == 0) { windowWidth = view.requiredWidth; windowHeight = view.requiredHeight; micOffset = view.micOffsetPx }
        val left = micLeft - micOffset
        val pieces = (0 until view.childCount).map { view.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.map {
            val p = it.layoutParams as FrameLayout.LayoutParams
            it to Rect(left + p.leftMargin, p.topMargin, left + p.leftMargin + p.width, p.topMargin + p.height)
        }
        val mic = pieces.single { (_, r) -> r.width() == view.dp(56) && r.left == micLeft }
        return Layout(left, windowWidth, windowHeight, micOffset, pieces, mic)
    }

    @Test fun theMicrophoneNeverMovesAndNothingLeavesTheScreen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        DictationCoordinator.editorReady()
        val failures = mutableListOf<String>()
        var checked = 0
        for (widthDp in listOf(280, 320, 360, 393, 411, 480, 600, 840)) {
            val probe = VoiceControlView(activity)
            val width = probe.dp(widthDp)
            val inset = probe.dp(8); val mic = probe.dp(56)
            val positions = (inset..(width - inset - mic) step probe.dp(24)).toList() + (width - inset - mic)
            for (case in Case.values()) for (overlayHeight in listOf(probe.dp(700), probe.dp(150))) {
                val view = VoiceControlView(activity)
                activity.setContentView(view, FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight))
                for (micLeft in positions) {
                    val layout = measure(activity, view, width, micLeft, case, overlayHeight)
                    checked++
                    val where = "${widthDp}dp mic@${micLeft / view.dp(1)} ${case.label} h=${overlayHeight / view.dp(1)}"
                    // The window is positioned at micLeft - micOffset; the microphone must be drawn at exactly micOffset.
                    if (layout.mic.second.left - layout.windowLeft != layout.micOffset) failures += "$where: mic drawn off its offset"
                    if (layout.windowLeft < -1 || layout.windowLeft + layout.windowWidth > width + 1) failures += "$where: window off screen (${layout.windowLeft}..${layout.windowLeft + layout.windowWidth} of $width)"
                    for ((piece, rect) in layout.pieces) {
                        if (rect.left < -1 || rect.right > width + 1) failures += "$where: ${piece.contentDescription ?: piece.javaClass.simpleName} off screen (${rect.left}..${rect.right} of $width)"
                    }
                    val targets = layout.pieces.filter { (v, _) -> v.isClickable }
                    for (i in targets.indices) for (j in i + 1 until targets.size) {
                        val a = targets[i].second; val b = targets[j].second
                        val overlapX = minOf(a.right, b.right) - maxOf(a.left, b.left)
                        val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                        if (overlapX > 1 && overlapY > 1) failures += "$where: ${targets[i].first.contentDescription} overlaps ${targets[j].first.contentDescription}"
                    }
                }
            }
        }
        assertTrue("checked $checked layouts", checked > 1000)
        assertEquals("layout failures (first 25 of ${failures.size}):\n" + failures.distinct().take(25).joinToString("\n"), 0, failures.size)
    }

    @Test fun theFullOptionsRowStacksAboveTheMicrophoneWhereNeitherSideHasRoom() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        DictationCoordinator.editorReady()
        val view = VoiceControlView(activity)
        activity.setContentView(view, FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight))
        val width = view.dp(411)
        val middle = view.dp(180)
        val layout = measure(activity, view, width, middle, Case.OPTIONS, view.dp(700))
        val buttons = layout.pieces.filter { (v, _) -> v.isClickable && v !== layout.mic.first }
        assertEquals("back, settings, raw, ai, live", 5, buttons.size)
        assertTrue("every control sits above the microphone", buttons.all { (_, r) -> r.bottom <= layout.mic.second.top })
        assertEquals("the microphone is exactly where it was put", middle, layout.mic.second.left)
        // And beside it when there is room, in the original arrangement.
        val right = measure(activity, view, width, view.dp(340), Case.OPTIONS, view.dp(700))
        val sameRow = right.pieces.filter { (v, _) -> v.isClickable && v !== right.mic.first }
        assertTrue(sameRow.all { (_, r) -> r.bottom > right.mic.second.top && r.right <= right.mic.second.left })
    }

    @Test fun aMessageCardOpensWithoutMovingTheMicrophoneInTheMiddleOfTheScreen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        DictationCoordinator.editorReady()
        val view = VoiceControlView(activity)
        activity.setContentView(view, FrameLayout.LayoutParams(view.requiredWidth, view.requiredHeight))
        val width = view.dp(411)
        for (micDp in listOf(100, 180, 250)) {
            val micLeft = view.dp(micDp)
            val bare = measure(activity, view, width, micLeft, Case.IDLE, view.dp(700))
            val card = measure(activity, view, width, micLeft, Case.ERROR, view.dp(700))
            assertEquals("mic at ${micDp}dp", bare.mic.second.left, card.mic.second.left)
            assertTrue("card fits the screen", card.pieces.all { (_, r) -> r.left >= 0 && r.right <= width })
        }
    }
}
