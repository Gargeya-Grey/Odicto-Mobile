package app.odicto.mobile

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.ImeActions
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class NumericKeyboardLayoutTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun numericPagesOfferOnlyTheRequestedCharactersAndActions() {
        val expected = mapOf(
            OdictoKeyboardView.Page.NUMPAD to emptySet(),
            OdictoKeyboardView.Page.NUMPAD_SIGNED to setOf("-"),
            OdictoKeyboardView.Page.NUMPAD_DECIMAL to setOf("."),
            OdictoKeyboardView.Page.NUMPAD_SIGNED_DECIMAL to setOf("-", "."),
            OdictoKeyboardView.Page.PHONE to setOf("+", "*", "#"),
        )
        for ((page, symbols) in expected) {
            val keyboard = keyboard(page)
            val descriptions = keys(keyboard).map { it.contentDescription.toString() }.toSet()
            assertEquals(page.name, (0..9).map { it.toString() }.toSet() + symbols + setOf("Backspace", "Enter"), descriptions)
            val pressed = mutableListOf<OdictoKeyboardView.KeySpec>()
            keyboard.setOnKeyListener { pressed += it }
            for (label in (0..9).map { it.toString() } + symbols) {
                tap(keyboard, key(keyboard, label))
            }
            assertEquals(page.name, (0..9).map { it.toString() } + symbols, pressed.map { it.output })
        }
    }

    @Test fun numberKeysAreLargerAndKeepTheSameFourColumnsAtTabletWidths() {
        for (width in listOf(360, 800)) {
            val keyboard = keyboard(OdictoKeyboardView.Page.LETTERS, width)
            val letterWidth = key(keyboard, "1").width
            val letterHeight = key(keyboard, "1").height
            keyboard.showPage(OdictoKeyboardView.Page.NUMPAD)
            measure(keyboard, width)
            assertEquals(4, keyboard.childCount)
            assertTrue(key(keyboard, "1").width > letterWidth * 2)
            assertTrue(key(keyboard, "1").height > letterHeight)
            for (row in 0 until keyboard.childCount) {
                assertEquals(4, (keyboard.getChildAt(row) as LinearLayout).childCount)
            }
            assertEquals(key(keyboard, "1").left, key(keyboard, "4").left)
            assertEquals(key(keyboard, "Backspace").left, key(keyboard, "Enter").left)
            keyboard.showPage(OdictoKeyboardView.Page.LETTERS)
            measure(keyboard, width)
            assertEquals(letterWidth, key(keyboard, "1").width)
        }
    }

    @Test fun unusedSlotsHaveNoActionOrAccessibilityTarget() {
        val keyboard = keyboard(OdictoKeyboardView.Page.NUMPAD)
        val row = keyboard.getChildAt(1) as LinearLayout
        val blank = row.getChildAt(3)
        assertFalse(blank.isClickable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, blank.importantForAccessibility)
        val pressed = mutableListOf<OdictoKeyboardView.KeySpec>()
        keyboard.setOnKeyListener { pressed += it }
        tap(keyboard, blank)
        assertTrue(pressed.isEmpty())
    }

    @Test fun holdingDecimalCannotOpenProsePunctuation() {
        val keyboard = keyboard(OdictoKeyboardView.Page.NUMPAD_DECIMAL)
        val pressed = mutableListOf<OdictoKeyboardView.KeySpec>()
        keyboard.setOnKeyListener { pressed += it }
        val cap = key(keyboard, ".")
        dispatch(keyboard, cap, MotionEvent.ACTION_DOWN)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        dispatch(keyboard, cap, MotionEvent.ACTION_UP)
        assertEquals(listOf("."), pressed.map { it.output })
    }

    @Test fun keypadRetainsTheRequestedEditorActionAndBackspace() {
        val keyboard = keyboard(OdictoKeyboardView.Page.NUMPAD)
        keyboard.setEnterAction(ImeActions.Kind.NEXT)
        val pressed = mutableListOf<OdictoKeyboardView.KeySpec>()
        keyboard.setOnKeyListener { pressed += it }
        tap(keyboard, key(keyboard, "Next"))
        tap(keyboard, key(keyboard, "Backspace"))
        assertEquals(listOf(OdictoKeyboardView.Key.ENTER, OdictoKeyboardView.Key.BACKSPACE), pressed.map { it.key })
    }

    private fun keyboard(page: OdictoKeyboardView.Page, width: Int = 360) = OdictoKeyboardView(context).apply {
        showPage(page)
        measure(this, width)
    }

    private fun measure(view: View, widthDp: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun keys(view: OdictoKeyboardView): List<View> = (0 until view.childCount).flatMap { row ->
        val line = view.getChildAt(row) as LinearLayout
        (0 until line.childCount).map { line.getChildAt(it) }.filter { it.contentDescription != null }
    }

    private fun key(view: OdictoKeyboardView, label: String) = keys(view).first { it.contentDescription == label }

    private fun tap(view: OdictoKeyboardView, cap: View) {
        dispatch(view, cap, MotionEvent.ACTION_DOWN)
        dispatch(view, cap, MotionEvent.ACTION_UP)
    }

    private fun dispatch(view: OdictoKeyboardView, cap: View, action: Int) {
        val row = cap.parent as View
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, row.left + cap.left + cap.width / 2f,
            row.top + cap.top + cap.height / 2f, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }
}
