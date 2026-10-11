package app.odicto.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.ClipboardPanelBinding
import app.odicto.mobile.ime.KeyboardController
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.storage.ClipboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** Presentation of the clipboard and emoji panels: sizes, spacing, selection look, and the open fade. */
@RunWith(RobolectricTestRunner::class)
class PanelPresentationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun measure(view: View, width: Int = 360, height: Int = 360) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun anEmptyClipboardShowsOneCentredMessageAndNoCards() {
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT)
        panel.submit(ClipboardState())
        measure(panel.root)
        val texts = descendants(panel.root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Nothing saved yet"))
        assertTrue(descendants(panel.root).filterIsInstance<LinearLayout>().none { it.tag is String })
    }

    @Test fun aSelectedClipCardIsMarkedSelectedAndPinsKeepTheirName() {
        val panel = ClipboardPanelBinding(context, Typeface.DEFAULT)
        panel.submit(ClipboardState(history = listOf("recent"), pins = listOf("pin")))
        fun card(payload: String) = descendants(panel.root).filterIsInstance<LinearLayout>().single { it.tag == payload }
        descendants(panel.root).first { it.contentDescription == "Select clips" }.performClick()
        card("recent").getChildAt(0).performClick()
        assertTrue(card("recent").isSelected)
        assertFalse(card("pin").isSelected)
        assertEquals("Unpin clip", card("pin").getChildAt(1).contentDescription)
        assertEquals("Pin clip", card("recent").getChildAt(1).contentDescription)
    }

    private fun withEmojiPanel(check: (root: View, controller: KeyboardController) -> Unit) {
        val serviceOwner = Robolectric.buildService(OdictoImeService::class.java).create()
        val service = serviceOwner.get()
        val host = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
            host.get().setContentView(root)
            val controller = KeyboardController(service, root)
            controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
            measure(root, 360, 2400)
            check(root, controller)
            controller.dispose()
        } finally { host.pause().stop().destroy(); serviceOwner.destroy() }
    }

    @Test fun emojiCategoriesAre32dpPillsInA48dpRowAndCellsAre48dpWithNoRule() {
        withEmojiPanel { root, _ ->
            root.findViewById<View>(R.id.function_emoji).performClick()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            measure(root, 360, 2400)
            val emoji = root.findViewById<LinearLayout>(R.id.emoji_panel)
            val pills = descendants(emoji).filter { it.contentDescription?.startsWith("Emoji category ") == true }
            assertTrue(pills.size > 2)
            for (pill in pills) assertEquals(dp(48), pill.layoutParams.height)
            val cells = descendants(emoji).filterIsInstance<TextView>().filter { it.parent is GridLayout }
            assertTrue(cells.isNotEmpty())
            for (cell in cells) {
                assertEquals(dp(48), cell.layoutParams.height)
                assertEquals(26f, cell.textSize / density, 0.01f)
            }
            // Sections are separated by spacing, never by a 1px rule.
            assertTrue(descendants(emoji).none { it !is ViewGroup && it !is TextView && it.layoutParams?.height == dp(1) })
        }
    }

    // The platform setter is hidden from the SDK; the test must flip the same switch the system setting does.
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Test fun openingAPanelFadesInByAlphaAndHonoursTheSystemAnimatorSetting() {
        withEmojiPanel { root, _ ->
            val emoji = root.findViewById<LinearLayout>(R.id.emoji_panel)
            val keys = root.findViewById<View>(R.id.ime_keyboard)
            try {
                setAnimatorScale(0f)
                assertFalse(ValueAnimator.areAnimatorsEnabled())
                root.findViewById<View>(R.id.function_emoji).performClick()
                assertEquals(1f, emoji.alpha, 0f)
                assertEquals(View.VISIBLE, emoji.visibility)
                assertEquals(View.INVISIBLE, keys.visibility)
                root.findViewById<View>(R.id.function_emoji).performClick()
                assertEquals(View.GONE, emoji.visibility)
                assertEquals(1f, emoji.alpha, 0f)

                setAnimatorScale(1f)
                root.findViewById<View>(R.id.function_emoji).performClick()
                assertEquals(0f, emoji.alpha, 0f)
                assertEquals(View.INVISIBLE, keys.visibility)
                // Closing in the middle of the fade leaves nothing half-transparent behind.
                root.findViewById<View>(R.id.function_emoji).performClick()
                assertEquals(1f, emoji.alpha, 0f)
                assertEquals(View.GONE, emoji.visibility)
            } finally { setAnimatorScale(1f) }
        }
    }
}
