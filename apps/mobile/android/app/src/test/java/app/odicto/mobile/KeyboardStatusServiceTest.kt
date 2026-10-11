package app.odicto.mobile

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import app.odicto.mobile.dictation.*
import app.odicto.mobile.ime.*
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
class KeyboardStatusServiceTest {
    private data class Geometry(val height: Int, val controls: List<Rect>, val keys: List<Rect>, val tools: Rect)
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun measure(root: View, width: Int = 360, maximum: Int = 800): Geometry {
        val density = root.resources.displayMetrics.density
        repeat(2) {
            children(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((maximum * density).toInt(), View.MeasureSpec.AT_MOST))
            root.layout(0, 2000 - root.measuredHeight, root.measuredWidth, 2000)
        }
        val persistent = children(root).filter { it.contentDescription in listOf("Raw transcription with Groq", "AI answer", "Live transcription") || it.id == R.id.ime_polish || it.id == R.id.function_row }
        fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
            (root as ViewGroup).offsetDescendantRectToMyCoords(view, it)
            it.offset(0, -root.height)
        }
        val keys = children(root.findViewById(R.id.ime_keyboard)).filter { it.contentDescription in listOf("q", "Space", "Backspace") }
        return Geometry(root.height, persistent.map(::bounds), keys.map(::bounds), bounds(root.findViewById(R.id.function_row)))
    }
    private fun render(service: OdictoImeService) {
        service.javaClass.getDeclaredMethod("renderTopStatus").apply { isAccessible = true }.invoke(service)
    }
    private fun polish(service: OdictoImeService, message: String, busy: Boolean = false) {
        service.javaClass.getDeclaredMethod("showPolishStatus", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, message, busy)
    }

    @Test fun activeMessagesStayStableAndExpiryRemovesTheHeaderAtRegularCompactAndConstrainedSizes() {
        val previous = VoiceSession.state.value
        VoiceSession.update { VoiceUi() }
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            root.findViewById<View>(R.id.ime_keyboard_toggle).performClick()
            service.onWindowShown()
            val status = root.findViewById<TextView>(R.id.ime_polish_status)
            for ((width, height) in listOf(300 to 800, 360 to 800, 420 to 800, 720 to 220)) {
                VoiceSession.update { VoiceUi() }; shadowOf(Looper.getMainLooper()).idle(); polish(service, "")
                val idle = measure(root, width, height)
                assertEquals(View.GONE, root.findViewById<View>(R.id.ime_assistance).visibility)
                var active: Geometry? = null
                for (phase in listOf("connecting", "recording", "processing", "done", "error", "notice")) {
                    VoiceSession.update { VoiceUi(phase = phase, resultId = phase, message = "Long warning\n".repeat(30), finalText = if (phase == "done") "synthetic" else "", delivery = DeliveryOutcome.REJECTED_STALE) }
                    shadowOf(Looper.getMainLooper()).idle()
                    render(service)
                    val current = measure(root, width, height)
                    if (active == null) active = current else assertEquals("$width/$height/$phase", active, current)
                    assertEquals("bottom tools stay anchored as content appears", idle.tools, current.tools)
                    assertEquals("a status line replaces tool cells; it never adds height", idle.height, current.height)
                    assertEquals("keys never move when status appears", idle.keys, current.keys)
                    assertEquals(View.VISIBLE, status.visibility)
                    assertEquals(1, status.maxLines)
                    assertFalse(status.isClickable)
                    assertFalse(status.text.contains('\n'))
                }
                polish(service, "Copied")
                assertEquals(active, measure(root, width, height))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000))
                assertEquals(View.INVISIBLE, status.visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.ime_assistance).visibility)
                assertEquals("expiry restores the empty geometry", idle, measure(root, width, height))
                for (panel in listOf(R.id.emoji_panel, R.id.clipboard_panel)) {
                    root.findViewById<View>(R.id.ime_keyboard).visibility = View.INVISIBLE
                    root.findViewById<View>(panel).visibility = View.VISIBLE
                    assertEquals(idle, measure(root, width, height))
                    root.findViewById<View>(panel).visibility = View.GONE
                    root.findViewById<View>(R.id.ime_keyboard).visibility = View.VISIBLE
                }
            }
        } finally { owner.destroy(); VoiceSession.update { previous } }
    }

    @Test fun cancellingPolishForANewVoiceOperationKeepsCurrentBusyFeedback() {
        val previous = VoiceSession.state.value
        VoiceSession.update { VoiceUi() }
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            polish(service, "Polishing", true)
            VoiceSession.update { VoiceUi(phase = "processing", message = "Processing voice") }
            polish(service, "")
            assertEquals("Processing voice", root.findViewById<TextView>(R.id.ime_polish_status).text.toString())
        } finally { owner.destroy(); VoiceSession.update { previous } }
    }

    @Test fun scaledFontMessagesUseOneLineAndIdleTakesNoStatusSpace() {
        val previous = VoiceSession.state.value
        VoiceSession.update { VoiceUi() }
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        val service = owner.get()
        val resources = service.resources
        val original = android.content.res.Configuration(resources.configuration)
        val originalMetrics = android.util.DisplayMetrics().apply { setTo(resources.displayMetrics) }
        try {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(android.content.res.Configuration(original).apply { fontScale = 2f }, resources.displayMetrics)
            val root = service.onCreateInputView()
            val baseline = measure(root, 300, 220)
            assertEquals(View.GONE, root.findViewById<View>(R.id.ime_assistance).visibility)
            polish(service, "A long warning\n".repeat(30))
            val populated = measure(root, 300, 220)
            assertEquals("even at 2x font scale the status stays inside the fixed row", baseline, populated)
            val status = root.findViewById<TextView>(R.id.ime_polish_status)
            assertEquals(1, status.maxLines)
            assertFalse(status.text.contains('\n'))
            assertTrue(status.height >= status.lineHeight)
            polish(service, "Copied")
            assertEquals(populated, measure(root, 300, 220))
            polish(service, "")
            assertEquals(baseline, measure(root, 300, 220))
        } finally {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, originalMetrics)
            owner.destroy()
            VoiceSession.update { previous }
        }
    }

    @Test fun terminalDeadlineSurvivesHistoryMeterHideRecreationAndCopyRemains() {
        val previous = VoiceSession.state.value
        VoiceSession.update { VoiceUi() }
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            var root = service.onCreateInputView()
            service.onWindowShown()
            val result = VoiceUi(phase = "done", resultId = "retained", finalText = "synthetic", message = "Check field before pasting", delivery = DeliveryOutcome.ACCEPTED_UNVERIFIED)
            VoiceSession.update { result }; shadowOf(Looper.getMainLooper()).idle(); render(service)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            VoiceSession.update { it.copy(message = "Check field. History unavailable", historyPersistence = HistoryPersistence.FAILED, level = 0.5f) }
            shadowOf(Looper.getMainLooper()).idle(); render(service)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.ime_polish_status).visibility)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.ime_polish_status).visibility)
            assertEquals("synthetic", VoiceSession.state.value.finalText)
            assertTrue(children(root).any { it.contentDescription == "Copy completed result" && it.visibility == View.VISIBLE })
            service.onWindowHidden()
            root = service.onCreateInputView(); service.onWindowShown()
            assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.ime_polish_status).visibility)
            polish(service, "Copied")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            service.onWindowHidden()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            service.onWindowShown()
            assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.ime_polish_status).visibility)
            polish(service, "Polishing", true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
            assertEquals("Polishing", root.findViewById<TextView>(R.id.ime_polish_status).text.toString())
            polish(service, "Field changed")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000))
            render(service)
            assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.ime_polish_status).visibility)
        } finally { owner.destroy(); VoiceSession.update { previous } }
    }

    @Test fun polishRecoveryReplacesContentWithoutChangingBottomAnchoredGeometry() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            root.findViewById<View>(R.id.ime_keyboard_toggle).performClick()
            service.onWindowShown()
            val baseline = measure(root)
            // Seed only a synthetic retained result; provider/delivery guards have their own coordinator tests.
            val coordinator = service.javaClass.getDeclaredField("polish").apply { isAccessible = true }.get(service) as FieldPolishCoordinator
            val retain = coordinator.javaClass.getDeclaredMethod("retain", Long::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
            fun show(id: Long) { retain.invoke(coordinator, id, "synthetic recovery"); shadowOf(Looper.getMainLooper()).idle() }
            show(1)
            assertEquals(baseline, measure(root))
            val copy = children(root).filterIsInstance<Button>().single { it.text == "Copy completed result" }
            assertFalse("No eligible editor: recovery copy must be disabled", copy.isEnabled)
            assertEquals(R.id.ime_content, (copy.parent.parent as View).id)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(baseline, measure(root))
            children(root).filterIsInstance<Button>().single { it.text == "Dismiss" }.performClick()
            assertNull(coordinator.completedResult())
            assertEquals(baseline, measure(root))
            show(2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
            assertNull(coordinator.completedResult())
            assertEquals(baseline, measure(root))
        } finally { owner.destroy() }
    }
}
