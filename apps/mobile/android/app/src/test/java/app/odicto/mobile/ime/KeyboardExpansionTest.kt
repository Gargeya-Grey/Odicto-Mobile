package app.odicto.mobile.ime

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.odicto.mobile.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The IME window keeps one height; expand and collapse are a translation of the column. The host
 * app hears about the new keyboard top once per transition: before an expand, after a collapse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardExpansionTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private inner class Fixture(val widthDp: Int = 412, val heightDp: Int = 800) : AutoCloseable {
        private val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val host = FrameLayout(owner.get())
        val root = LayoutInflater.from(owner.get()).inflate(R.layout.ime_keyboard, null, false)
        val column = root.findViewById<View>(R.id.ime_column)
        val area = root.findViewById<View>(R.id.ime_typing_area)
        val tools = root.findViewById<View>(R.id.function_row)
        val toggle = root.findViewById<View>(R.id.ime_keyboard_toggle)
        val grid = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        var cancelledTouches = 0
        var insetUpdates = 0
        val expansion: KeyboardExpansion

        init {
            root.findViewById<View>(R.id.ime_content).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            expansion = KeyboardExpansion(root, { cancelledTouches++; grid.cancelTouch() }) { insetUpdates++ }
            owner.get().setContentView(host)
            host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            measure()
            assertTrue("exercise the attached-view animation path", area.isAttachedToWindow)
        }

        fun measure() {
            val density = root.resources.displayMetrics.density
            repeat(3) {
                descendants(host).forEach { it.forceLayout() }
                host.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY))
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            }
        }

        fun animator(): ValueAnimator? = expansion.javaClass.getDeclaredField("animation")
            .apply { isAccessible = true }.get(expansion) as? ValueAnimator

        fun seek(fraction: Float) {
            val animator = requireNotNull(animator()) { "A real animation must be in flight" }
            animator.currentPlayTime = (animator.duration * fraction).toLong()
            measure()
        }

        fun finish() { requireNotNull(animator()).end(); measure() }

        fun keyRects(): List<Rect> = descendants(grid).filter { it.isClickable && it !== grid }.map { key ->
            Rect(0, 0, key.width, key.height).also { grid.offsetDescendantRectToMyCoords(key, it) }
        }

        override fun close() { expansion.dispose(); owner.pause().stop().destroy() }
    }

    @Test fun startsCollapsedWithOnlyTheTopRowClearOfTheApp() {
        Fixture().use { f ->
            assertTrue(f.area.height > 0)
            assertEquals(f.area.height.toFloat(), f.column.translationY)
            assertEquals(View.INVISIBLE, f.area.visibility)
            assertEquals(f.column.top + f.area.height, f.expansion.contentTop())
            assertEquals("Show keyboard", f.toggle.contentDescription)
        }
    }

    @Test fun motionNeverResizesTheWindowOrTheKeys() {
        Fixture().use { f ->
            val height = f.root.height
            val keys = f.keyRects()
            assertTrue(keys.isNotEmpty())
            f.toggle.performClick()
            for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                f.seek(fraction)
                assertEquals("the IME window keeps one height", height, f.root.height)
                assertEquals("keys slide; they are never squeezed", keys, f.keyRects())
                assertTrue(f.column.translationY > 0f && f.column.translationY < f.area.height)
            }
            f.finish()
            assertEquals(0f, f.column.translationY)
            assertEquals(height, f.root.height)
            f.toggle.performClick()
            f.seek(0.5f)
            assertEquals(height, f.root.height)
            assertEquals(keys, f.keyRects())
            f.finish()
            assertEquals(f.area.height.toFloat(), f.column.translationY)
            assertEquals(height, f.root.height)
        }
    }

    @Test fun theAppIsResizedBeforeAnExpandAndAfterACollapse() {
        Fixture().use { f ->
            val collapsedTop = f.expansion.contentTop()
            val updates = f.insetUpdates
            f.toggle.performClick()
            assertEquals("expand: keys slide into space that is already clear", f.column.top, f.expansion.contentTop())
            assertEquals(updates + 1, f.insetUpdates)
            f.seek(0.5f); f.finish()
            assertEquals(updates + 1, f.insetUpdates)
            f.toggle.performClick()
            f.seek(0.5f)
            assertEquals("collapse: the app waits until the keys are gone", f.column.top, f.expansion.contentTop())
            f.finish()
            assertEquals(collapsedTop, f.expansion.contentTop())
            assertEquals(updates + 2, f.insetUpdates)
        }
    }

    @Test fun collapseKeepsTheFooterUntilTheMotionHasFinished() {
        Fixture().use { f ->
            f.toggle.performClick(); f.finish()
            f.toggle.performClick()
            assertEquals(View.VISIBLE, f.tools.visibility)
            f.seek(0.5f)
            assertEquals(View.VISIBLE, f.tools.visibility)
            f.finish()
            assertEquals(View.INVISIBLE, f.tools.visibility)
            assertEquals(View.INVISIBLE, f.area.visibility)
        }
    }

    @Test fun rapidReversalContinuesFromTheCurrentPosition() {
        Fixture().use { f ->
            val presentations = mutableListOf<Boolean>()
            f.expansion.onPresentationChanged = { presentations += it }
            f.toggle.performClick(); f.seek(0.35f)
            val opening = f.column.translationY
            val retired = requireNotNull(f.animator())
            f.toggle.performClick(); f.measure()
            assertEquals("no jump on reversal", opening, f.column.translationY, 0.5f)
            retired.end(); f.measure()
            assertNotNull("a retired animator must not settle the new transition", f.animator())
            f.finish()
            assertEquals(f.area.height.toFloat(), f.column.translationY)
            assertEquals(listOf(true, false), presentations)
        }
    }

    @Test fun editorResetAndDetachSettleWithoutAPendingFrame() {
        Fixture().use { f ->
            f.toggle.performClick(); f.seek(0.5f)
            val retired = requireNotNull(f.animator())
            f.expansion.reset(); f.measure()
            retired.end(); f.measure()
            assertNull(f.animator())
            assertEquals(f.area.height.toFloat(), f.column.translationY)
            assertEquals(View.INVISIBLE, f.area.visibility)
            assertEquals("Show keyboard", f.toggle.contentDescription)
            f.toggle.performClick(); f.finish()
            f.toggle.performClick(); f.seek(0.4f)
            f.host.removeView(f.root)
            f.host.addView(f.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            f.measure()
            assertNull(f.animator())
            assertEquals(f.area.height.toFloat(), f.column.translationY)
            assertTrue(f.cancelledTouches >= 5)
        }
    }

    @Test fun recoveryShowsItsPanelWhileCollapsedAndDismissalReturnsToTheBar() {
        Fixture().use { f ->
            f.expansion.recoveryVisible(true); f.measure()
            assertEquals(0f, f.column.translationY)
            assertEquals(View.VISIBLE, f.area.visibility)
            assertEquals("tools stay hidden: the keyboard was not expanded", View.INVISIBLE, f.tools.visibility)
            assertEquals(f.column.top, f.expansion.contentTop())
            f.expansion.recoveryVisible(false); f.measure()
            assertEquals(f.area.height.toFloat(), f.column.translationY)
            assertEquals(View.INVISIBLE, f.area.visibility)
        }
    }

    @Test fun disabledSystemAnimationsSettleImmediately() {
        val field = ValueAnimator::class.java.getDeclaredField("sDurationScale").apply { isAccessible = true }
        val previous = field.getFloat(null)
        try {
            field.setFloat(null, 0f)
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            Fixture().use { f ->
                f.toggle.performClick(); f.measure()
                assertNull(f.animator())
                assertEquals(0f, f.column.translationY)
                assertEquals(View.VISIBLE, f.area.visibility)
                f.toggle.performClick(); f.measure()
                assertNull(f.animator())
                assertEquals(f.area.height.toFloat(), f.column.translationY)
            }
        } finally { field.setFloat(null, previous) }
    }
}
