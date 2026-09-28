package app.odicto.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.ClipboardPanel
import app.odicto.mobile.ime.ClipboardPanelActions
import app.odicto.mobile.ime.ClipboardPanelBinding
import app.odicto.mobile.storage.ClipboardState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardPanelBindingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let { visible(it) } ?: true)
    private fun action(root: View, name: String) = descendants(root).first { it.contentDescription == name }
    private fun textAction(root: View, text: String) = descendants(root).filterIsInstance<Button>().first { it.text.toString() == text }
    private fun measure(root: View, width: Int, height: Int) {
        val density = root.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    @Test fun narrowAndLandscapeLayoutsKeepControlsWithinParentsAtLargeFontScales() {
        for (width in listOf(240, 280, 360, 411, 640)) for (scale in listOf(1f, 1.5f, 2f)) {
            val config = Configuration(context.resources.configuration).apply { fontScale = scale }
            val themed = context.createConfigurationContext(config)
            val panel = ClipboardPanelBinding(themed, Typeface.DEFAULT)
            panel.submit(ClipboardState(history = List(50) { "Synthetic item $it: " + "Long preview content ".repeat(16) }, pins = listOf("Pinned")))
            measure(panel.root, width, if (width == 640) 200 else 360)
            action(panel.root, "Select clips").performClick()
            action(panel.root, "Select all unpinned").performClick()
            measure(panel.root, width, if (width == 640) 200 else 360)
            for (view in descendants(panel.root).filter { visible(it) }) {
                val parent = view.parent as? ViewGroup ?: continue
                assertTrue("Left bound at $width/$scale", view.left >= 0)
                assertTrue("Right bound at $width/$scale", view.right <= parent.width)
                if (view is ImageButton || view is Button || view is Switch) {
                    assertTrue("Touch width at $width/$scale", view.width >= (48 * themed.resources.displayMetrics.density).toInt())
                    assertTrue("Touch height at $width/$scale", view.height >= (48 * themed.resources.displayMetrics.density).toInt())
                }
            }
            val scroll = descendants(panel.root).filterIsInstance<ScrollView>().single()
            assertTrue(scroll.height > 0)
            assertEquals(51, descendants(panel.root).filterIsInstance<LinearLayout>().count { it.tag is String })
        }
    }

    @Test fun pinGlyphIsSmallAndInsetWithoutShrinkingItsTouchTarget() {
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT)
        panel.submit(ClipboardState(pins = listOf("Pinned entry")))
        measure(panel.root, 360, 280)
        val pin = action(panel.root, "Unpin clip") as ImageButton
        val row = pin.parent as View
        val density = context.resources.displayMetrics.density
        assertEquals((48 * density).toInt(), pin.width)
        assertEquals((16 * density).toInt(), pin.width - pin.paddingLeft - pin.paddingRight)
        assertTrue(row.width - pin.right >= (8 * density).toInt())
        assertFalse(pin.isSelected)
    }

    @Test fun pasteAndPinUseExactPayloadAndOnlyThePreviewIsEllipsized() {
        val payload = " \n" + "👩🏽‍💻 example ".repeat(100) + "\u001F\n "
        var pasted: String? = null
        var pinned: Pair<String, Boolean>? = null
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT, ClipboardPanelActions(
            paste = { pasted = it }, pin = { text, value -> pinned = text to value },
        ))
        panel.submit(ClipboardState(history = listOf(payload)))
        val row = descendants(panel.root).filterIsInstance<LinearLayout>().single { it.tag == payload }
        val preview = row.getChildAt(0) as Button
        assertEquals(ClipboardPanel.preview(payload), preview.text.toString())
        assertTrue(preview.text.endsWith("…"))
        assertEquals(3, preview.maxLines)
        preview.performClick()
        row.getChildAt(1).performClick()
        assertEquals(payload, pasted)
        assertEquals(payload to true, pinned)
    }

    @Test fun selectAllAndStateChangesNeverPassPinsToBulkDelete() {
        var deleted = emptySet<String>()
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT, ClipboardPanelActions(delete = { deleted = it }))
        panel.submit(ClipboardState(history = listOf("recent", "new pin"), pins = listOf("pin")))
        action(panel.root, "Select clips").performClick()
        textAction(panel.root, "pin").performClick()
        assertFalse(textAction(panel.root, "pin").isSelected)
        action(panel.root, "Select all unpinned").performClick()
        assertTrue(textAction(panel.root, "recent").isSelected)
        panel.submit(ClipboardState(history = listOf("recent"), pins = listOf("pin", "new pin")))
        action(panel.root, "Delete selected clips").performClick()
        assertEquals(setOf("recent"), deleted)
        assertFalse(textAction(panel.root, "new pin").isSelected)
    }

    @Test fun clearRequiresExplicitConfirmationAndCancelIsSafe() {
        var clears = 0
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT, ClipboardPanelActions(clear = { clears++ }))
        panel.submit(ClipboardState(history = listOf("recent"), pins = listOf("pin")))
        action(panel.root, "Clear all unpinned").performClick()
        assertEquals(0, clears)
        textAction(panel.root, "Cancel").performClick()
        assertEquals(0, clears)
        action(panel.root, "Clear all unpinned").performClick()
        textAction(panel.root, "Clear recent").performClick()
        assertEquals(1, clears)
    }

    @Test fun autoSaveHasAccessibleLabelAndBindingDoesNotEmitWrites() {
        val changes = mutableListOf<Boolean>()
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT, ClipboardPanelActions(automaticCapture = { changes.add(it) }))
        panel.submit(ClipboardState(automaticCapture = true))
        val toggle = action(panel.root, "Save copied text automatically") as Switch
        assertTrue(toggle.isChecked)
        assertTrue(changes.isEmpty())
        toggle.performClick()
        assertEquals(listOf(false), changes)
        val details = descendants(panel.root).filterIsInstance<TextView>().single { it.text.toString() == ClipboardPanel.DISCLOSURE }
        assertEquals(View.GONE, details.visibility)
        action(panel.root, "Local only. Show clipboard privacy details").performClick()
        assertEquals(View.VISIBLE, details.visibility)
        action(panel.root, "Local only. Hide clipboard privacy details").performClick()
        assertEquals(View.GONE, details.visibility)
    }

    @Test fun aNewCaptureKeepsTheVisibleClipAtTheSameOffset() {
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT)
        val state = ClipboardState(history = List(40) { "Synthetic item $it" })
        panel.submit(state)
        measure(panel.root, 280, 360)
        shadowOf(Looper.getMainLooper()).idle()
        val scroll = descendants(panel.root).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, 500)
        val row = descendants(panel.root).filterIsInstance<LinearLayout>().first { it.tag == "Synthetic item 8" }
        val parent = row.parent as View
        val offset = parent.top + row.top - scroll.scrollY
        panel.submit(state.copy(history = listOf("New synthetic capture") + state.history))
        measure(panel.root, 280, 360)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(offset, parent.top + row.top - scroll.scrollY)
    }

    @Test fun stateOnlyUpdatesReuseClipViewsAndKeepScrollPosition() {
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT)
        val state = ClipboardState(history = List(50) { "Synthetic item $it" })
        panel.submit(state)
        measure(panel.root, 280, 360)
        shadowOf(Looper.getMainLooper()).idle()
        val scroll = descendants(panel.root).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, 500)
        val before = textAction(panel.root, "Synthetic item 12")
        panel.submit(state.copy(automaticCapture = true))
        measure(panel.root, 280, 360)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(before, textAction(panel.root, "Synthetic item 12"))
        assertEquals(500, scroll.scrollY)
    }
}
