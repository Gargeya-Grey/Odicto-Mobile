package app.odicto.mobile

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class KeyboardPrecisionViewTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun layout(view: View, dp: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec((dp * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun keys(view: View): List<View> = if (view is ViewGroup) (0 until view.childCount).flatMap { keys(view.getChildAt(it)) } else listOf(view)
    private fun key(view: View, name: String) = keys(view).first { it.contentDescription == name }
    private fun point(view: View): Pair<Float, Float> {
        val parent = view.parent as View
        return view.x + parent.x + view.width / 2f to view.y + parent.y + view.height / 2f
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }
    @Test fun gapsAndOutsideNeverChooseNearestKey() {
        val view = OdictoKeyboardView(context)
        layout(view, 400)
        val pressed = mutableListOf<String>()
        view.setOnKeyListener { pressed += it.output }
        val q = key(view, "q")
        val p = point(q)
        for (x in listOf(-0.1f, 0f, (q.x + q.width), view.width.toFloat())) {
            touch(view, MotionEvent.ACTION_DOWN, x, p.second)
            touch(view, MotionEvent.ACTION_UP, x, p.second)
        }
        assertTrue(pressed.isEmpty())
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        assertEquals(listOf("q"), pressed)
    }
    @Test fun everyWidePageHasTwoThumbSpacesAndInertCenter() {
        val view = OdictoKeyboardView(context)
        val pressed = mutableListOf<String>()
        view.setOnKeyListener { pressed += it.output }
        for (page in OdictoKeyboardView.Page.entries) {
            view.showPage(page)
            layout(view, 600)
            val spaces = keys(view).filter { it.contentDescription == "Space" }
            assertEquals(2, spaces.size)
            spaces.forEach { it.performClick() }
            assertEquals(listOf(" ", " "), pressed)
            pressed.clear()
            for (row in 0 until view.childCount) {
                val line = view.getChildAt(row)
                touch(view, MotionEvent.ACTION_DOWN, view.width / 2f, line.y + line.height / 2f)
                touch(view, MotionEvent.ACTION_UP, view.width / 2f, line.y + line.height / 2f)
            }
            assertTrue(pressed.isEmpty())
        }
        layout(view, 599)
        assertEquals(1, keys(view).count { it.contentDescription == "Space" })
    }
    @Test fun busyStateKeepsShortSpaceTapButSuppressesDragging() {
        val view = OdictoKeyboardView(context)
        layout(view, 400)
        val pressed = mutableListOf<String>()
        view.setOnKeyListener { pressed += it.output }
        view.setDragListener({ _, _ -> fail("busy cursor dispatch") }, { false })
        val p = point(key(view, "Space"))
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        touch(view, MotionEvent.ACTION_MOVE, p.first + 1, p.second)
        touch(view, MotionEvent.ACTION_UP, p.first + 1, p.second)
        assertEquals(listOf(" "), pressed)
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        touch(view, MotionEvent.ACTION_MOVE, p.first + 30 * context.resources.displayMetrics.density, p.second)
        touch(view, MotionEvent.ACTION_UP, p.first, p.second)
        assertEquals(listOf(" "), pressed)
    }

    @Test fun holdArmsAt250msAndMovesInFourDpWithoutSpace() {
        val view = OdictoKeyboardView(context)
        layout(view, 400)
        val pressed = mutableListOf<String>()
        val moves = mutableListOf<Int>()
        view.setOnKeyListener { pressed += it.output }
        view.setDragListener({ x, y -> assertEquals(0, y); moves += x }, { true })
        val p = point(key(view, "Space"))
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        val x = p.first + 4 * context.resources.displayMetrics.density
        touch(view, MotionEvent.ACTION_MOVE, x, p.second)
        touch(view, MotionEvent.ACTION_UP, x, p.second)
        assertEquals(listOf(1), moves)
        assertTrue(pressed.isEmpty())
    }
    @Test fun uppercasePreviewActivatesAt300msAndAcceptsOnlyOnRelease() {
        val view = OdictoKeyboardView(context)
        layout(view, 400)
        val pressed = mutableListOf<String>()
        var captures = 0
        var accepts = 0
        var dispatched: OdictoKeyboardView.KeySpec? = null
        view.setOnKeyListener { pressed += it.output; dispatched = it }
        view.setUppercaseChoiceListener { spec ->
            assertSame(dispatched, spec)
            assertEquals("q", spec.output)
            captures++
            val accept: () -> Boolean = { accepts++; false }
            accept
        }
        val p = point(key(view, "q"))
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        assertEquals(listOf("q"), pressed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299))
        assertEquals("q", view.visiblePreview())
        touch(view, MotionEvent.ACTION_UP, p.first, p.second)
        assertEquals(0, accepts)
        assertNull(view.visiblePreview())
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299))
        assertEquals("q", view.visiblePreview())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals("Q", view.visiblePreview())
        assertEquals(0, accepts)
        var capitalDraws = 0
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(object : android.graphics.Canvas(bitmap) {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
                if (text == "Q") capitalDraws++
                super.drawText(text, x, y, paint)
            }
        })
        assertEquals("The key and its preview must both draw the capital", 2, capitalDraws)
        bitmap.recycle()
        touch(view, MotionEvent.ACTION_UP, p.first, p.second)
        assertEquals(1, accepts)
        assertNull(view.visiblePreview())
        assertEquals(2, captures)
        assertEquals(listOf("q", "q"), pressed)
    }

    @Test fun movingAwayOrChangingPageCancelsUppercase() {
        val view = OdictoKeyboardView(context)
        layout(view, 400)
        var accepts = 0
        view.setUppercaseChoiceListener { val accept: () -> Boolean = { accepts++; true }; accept }
        val p = point(key(view, "q"))
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        touch(view, MotionEvent.ACTION_MOVE, -1f, -1f)
        touch(view, MotionEvent.ACTION_UP, p.first, p.second)
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        view.showPage(OdictoKeyboardView.Page.NUMBERS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        touch(view, MotionEvent.ACTION_UP, p.first, p.second)
        assertEquals(0, accepts)
    }

    @Test fun constrainedLandscapeHeightKeepsEveryRowInsideView() {
        val view = OdictoKeyboardView(context)
        val density = context.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((700 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((180 * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.getChildAt(view.childCount - 1).bottom <= view.height)
    }

    @Test fun resizingCancelsPendingSpaceAndRebuildsBottomWidths() {
        val view = OdictoKeyboardView(context)
        layout(view, 700)
        val pressed = mutableListOf<String>()
        view.setOnKeyListener { pressed += it.output }
        val p = point(key(view, "Space"))
        touch(view, MotionEvent.ACTION_DOWN, p.first, p.second)
        layout(view, 400)
        touch(view, MotionEvent.ACTION_UP, p.first, p.second)
        assertTrue(pressed.isEmpty())
        val enter = key(view, "Enter")
        assertTrue(enter.right <= view.width)
    }
}
