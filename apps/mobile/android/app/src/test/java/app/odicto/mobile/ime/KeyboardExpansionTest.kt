package app.odicto.mobile.ime

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.graphics.Point
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardExpansionTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private data class GridGeometry(val height: Int, val keys: List<Rect>)

    private inner class Fixture(val widthDp: Int = 412, val heightDp: Int = 800) : AutoCloseable {
        private val owner = Robolectric.buildActivity(Activity::class.java).setup()
        private val display = ShadowDisplay.getDefaultDisplay()
        private val previousDisplaySize = Point().also { display.getSize(it) }
        val host = FrameLayout(owner.get())
        val root = LayoutInflater.from(owner.get()).inflate(R.layout.ime_keyboard, null, false)
        val area = root.findViewById<View>(R.id.ime_typing_area)
        val tools = root.findViewById<View>(R.id.function_row)
        val toggle = root.findViewById<View>(R.id.ime_keyboard_toggle)
        val grid = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        var cancelledTouches = 0
        val expansion: KeyboardExpansion

        init {
            // The display budget stays fixed even when the IME window's own cap follows the reveal.
            shadowOf(display).setWidth((widthDp * root.resources.displayMetrics.density).toInt())
            shadowOf(display).setHeight((heightDp * root.resources.displayMetrics.density).toInt())
            // Match the service's bounded, weighted content slot, including landscape constraints.
            root.findViewById<View>(R.id.ime_content).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            expansion = KeyboardExpansion(root) { cancelledTouches++; grid.cancelTouch() }
            owner.get().setContentView(host)
            host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            measure()
            assertTrue("exercise the attached-view animation path", area.isAttachedToWindow)
        }

        fun measure() {
            measureWindow((heightDp * root.resources.displayMetrics.density).toInt())
        }

        fun measureWindow(heightPx: Int) {
            val density = root.resources.displayMetrics.density
            repeat(3) {
                descendants(host).forEach { it.forceLayout() }
                host.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
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

        fun gridGeometry(): GridGeometry = GridGeometry(grid.height,
            descendants(grid).filter { it.isClickable && it !== grid }.map { key ->
                Rect(0, 0, key.width, key.height).also { grid.offsetDescendantRectToMyCoords(key, it) }
            })

        fun firstKeyBounds(): Rect = descendants(grid).first { it.isClickable && it !== grid }.let { key ->
            Rect(0, 0, key.width, key.height).also { host.offsetDescendantRectToMyCoords(key, it) }
        }

        fun showAssistance() {
            root.findViewById<View>(R.id.ime_assistance).visibility = View.VISIBLE
            root.findViewById<LinearLayout>(R.id.ime_suggestions).apply {
                addView(android.widget.Button(context).apply { text = "Synthetic suggestion" })
                visibility = View.VISIBLE
            }
            measure()
        }

        fun clearAssistanceOnCollapse() {
            expansion.onPresentationChanged = { expanded ->
                if (!expanded) {
                    root.findViewById<LinearLayout>(R.id.ime_suggestions).apply { removeAllViews(); visibility = View.GONE }
                    root.findViewById<View>(R.id.ime_assistance).visibility = View.GONE
                }
            }
        }

        override fun close() {
            expansion.dispose(); owner.pause().stop().destroy()
            shadowOf(display).setWidth(previousDisplaySize.x)
            shadowOf(display).setHeight(previousDisplaySize.y)
        }
    }

    @Test fun collapseKeepsTheFooterUntilTheRevealHasFinished() {
        Fixture().use { f ->
            f.toggle.performClick(); f.finish()
            f.toggle.performClick()
            assertEquals("footer must leave with the keys, not disappear before the first frame", View.VISIBLE, f.tools.visibility)
            f.seek(0.5f)
            assertEquals(View.VISIBLE, f.tools.visibility)
            f.finish()
            assertEquals(View.GONE, f.tools.visibility)
            assertEquals(View.GONE, f.area.visibility)
        }
    }

    @Test fun expansionAndCollapseRevealFullSizeKeysInsteadOfCompressingTheirRows() {
        Fixture().use { f ->
            val collapsedHeight = f.root.height
            f.toggle.performClick(); f.finish()
            val expandedHeight = f.root.height
            val geometry = f.gridGeometry()
            assertTrue(geometry.keys.isNotEmpty())
            f.toggle.performClick()
            for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                f.seek(fraction)
                assertEquals("collapse must clip the grid, never shrink its keys", geometry, f.gridGeometry())
                assertTrue(f.root.height in (collapsedHeight + 1) until expandedHeight)
            }
            f.finish()
            assertEquals(collapsedHeight, f.root.height)
            f.toggle.performClick()
            for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                f.seek(fraction)
                assertEquals("expansion must reveal the same full-size key geometry", geometry, f.gridGeometry())
                assertTrue(f.root.height in (collapsedHeight + 1) until expandedHeight)
            }
            f.finish()
            assertEquals(expandedHeight, f.root.height)
        }
    }

    @Test fun rapidReversalStartsFromTheCurrentRevealWithoutAnOpacityOrHeightJump() {
        Fixture().use { f ->
            val collapsedHeight = f.root.height
            val presentations = mutableListOf<Boolean>()
            f.expansion.onPresentationChanged = { presentations += it }
            f.toggle.performClick(); f.seek(0.35f)
            val openingHeight = f.root.height
            val geometry = f.gridGeometry()
            val opening = requireNotNull(f.animator())
            f.toggle.performClick(); f.measure()
            assertEquals(openingHeight, f.root.height)
            assertEquals(1f, f.area.alpha, 0f)
            assertEquals(0f, f.area.translationY, 0f)
            assertEquals(geometry, f.gridGeometry())
            f.seek(0.25f)
            val closingHeight = f.root.height
            assertTrue(closingHeight < openingHeight)
            val closing = requireNotNull(f.animator())
            f.toggle.performClick(); f.measure()
            assertEquals(closingHeight, f.root.height)
            assertEquals(geometry, f.gridGeometry())
            // A retired animator must not apply another frame or settle the new transition.
            opening.end(); closing.end(); f.measure()
            assertEquals(closingHeight, f.root.height)
            assertNotNull(f.animator())
            f.finish()
            assertTrue(f.root.height > openingHeight)
            assertEquals(listOf(true, false, true), presentations)
            f.toggle.performClick(); f.finish()
            assertEquals(collapsedHeight, f.root.height)
        }
    }

    @Test fun editorResetAndDetachRetireEveryPendingAnimationFrame() {
        Fixture().use { f ->
            val collapsedHeight = f.root.height
            f.toggle.performClick(); f.seek(0.5f)
            val retired = requireNotNull(f.animator())
            f.expansion.reset(); f.measure()
            retired.end(); f.measure()
            assertNull(f.animator())
            assertEquals(collapsedHeight, f.root.height)
            assertEquals(View.GONE, f.area.visibility)
            assertEquals("Show keyboard", f.toggle.contentDescription)
            f.toggle.performClick(); f.finish()
            f.toggle.performClick(); f.seek(0.4f)
            val detached = requireNotNull(f.animator())
            f.host.removeView(f.root)
            detached.end()
            f.host.addView(f.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            f.measure()
            assertNull(f.animator())
            assertEquals(collapsedHeight, f.root.height)
            assertTrue(f.cancelledTouches >= 6)
        }
    }

    @Test fun recoveryInterruptsMotionAndDismissalRestoresTheCompactBar() {
        Fixture().use { f ->
            val collapsedHeight = f.root.height
            f.toggle.performClick(); f.seek(0.3f)
            val retired = requireNotNull(f.animator())
            f.expansion.recoveryVisible(true); f.measure()
            assertNull(f.animator())
            assertEquals(View.VISIBLE, f.area.visibility)
            f.expansion.reset(); f.measure()
            assertEquals(View.VISIBLE, f.area.visibility)
            assertEquals(View.GONE, f.tools.visibility)
            assertTrue(f.root.height > collapsedHeight)
            f.expansion.recoveryVisible(false); f.measure()
            retired.end(); f.measure()
            assertEquals(collapsedHeight, f.root.height)
            assertEquals(View.GONE, f.area.visibility)
            assertEquals("Show keyboard", f.toggle.contentDescription)
        }
    }

    @Test fun disabledSystemAnimationsSettleImmediatelyWithoutLeavingAClippedArea() {
        val field = ValueAnimator::class.java.getDeclaredField("sDurationScale").apply { isAccessible = true }
        val previous = field.getFloat(null)
        try {
            field.setFloat(null, 0f)
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            Fixture().use { f ->
                val collapsedHeight = f.root.height
                f.toggle.performClick(); f.measure()
                assertNull(f.animator())
                assertEquals(View.VISIBLE, f.area.visibility)
                assertTrue(f.root.height > collapsedHeight)
                val viewport = f.area.parent as ViewGroup
                val footer = Rect(0, 0, f.tools.width, f.tools.height)
                viewport.offsetDescendantRectToMyCoords(f.tools, footer)
                assertTrue("the viewport exposes the complete footer", footer.top >= 0 && footer.bottom <= viewport.height)
                f.toggle.performClick(); f.measure()
                assertNull(f.animator())
                assertEquals(collapsedHeight, f.root.height)
                assertEquals(View.GONE, f.area.visibility)
            }
        } finally { field.setFloat(null, previous) }
    }

    @Test
    @Config(qualifiers = "land")
    fun revealPreservesTheFinalConstrainedGeometryInShortAndNarrowWindows() {
        for ((width, height) in listOf(800 to 220, 288 to 800)) {
            Fixture(width, height).use { f ->
                val collapsedHeight = f.root.height
                f.toggle.performClick(); f.finish()
                val expandedHeight = f.root.height
                val geometry = f.gridGeometry()
                assertTrue(geometry.height > 0)
                assertTrue(expandedHeight <= f.host.height)
                f.toggle.performClick()
                for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                    f.seek(fraction)
                    assertEquals("keys use the final available window budget throughout motion", geometry, f.gridGeometry())
                }
                f.finish()
                assertEquals(collapsedHeight, f.root.height)
                f.toggle.performClick(); f.finish()
                assertEquals(expandedHeight, f.root.height)
                assertEquals(geometry, f.gridGeometry())
            }
        }
    }

    @Test fun clearingSuggestionsOnCollapseDoesNotJumpTheBarOrKeysAndLeavesNoIdleSpace() {
        for ((width, height) in listOf(412 to 800, 800 to 220)) {
            Fixture(width, height).use { f ->
                val collapsedHeight = f.root.height
                f.toggle.performClick(); f.finish()
                f.showAssistance()
                f.clearAssistanceOnCollapse()
                val expandedHeight = f.root.height
                val key = f.firstKeyBounds()
                val geometry = f.gridGeometry()
                f.toggle.performClick(); f.measure()
                assertEquals("spelling state is invalidated immediately", View.GONE,
                    f.root.findViewById<View>(R.id.ime_assistance).visibility)
                assertEquals(0, f.root.findViewById<LinearLayout>(R.id.ime_suggestions).childCount)
                assertEquals("the first collapse frame must preserve the old drawer extent", expandedHeight, f.root.height)
                assertEquals("the first key must not jump when its old header disappears", key, f.firstKeyBounds())
                var previousHeight = expandedHeight
                for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                    f.seek(fraction)
                    assertEquals(geometry, f.gridGeometry())
                    assertTrue(f.root.height in (collapsedHeight + 1) until previousHeight)
                    previousHeight = f.root.height
                }
                f.finish()
                assertEquals("no temporary header space survives collapse", collapsedHeight, f.root.height)
                f.toggle.performClick(); f.finish()
                f.toggle.performClick(); f.finish()
                assertEquals(collapsedHeight, f.root.height)
            }
        }
    }

    @Test fun reversingAfterSuggestionsWereClearedKeepsTheCurrentVisiblePosition() {
        Fixture().use { f ->
            f.toggle.performClick(); f.finish()
            val plainExpandedHeight = f.root.height
            f.showAssistance(); f.clearAssistanceOnCollapse()
            f.toggle.performClick(); f.seek(0.35f)
            val height = f.root.height
            val key = f.firstKeyBounds()
            f.toggle.performClick(); f.measure()
            assertEquals(height, f.root.height)
            assertEquals(key, f.firstKeyBounds())
            f.seek(0.5f); f.finish()
            assertEquals("reversal must remove the retired header space", plainExpandedHeight, f.root.height)
        }
    }

    @Test fun aStatusHeaderThatRemainsVisibleDoesNotGainAnExtraGap() {
        Fixture().use { f ->
            f.root.findViewById<android.widget.TextView>(R.id.ime_polish_status).apply {
                text = "Synthetic busy status"
                visibility = View.VISIBLE
            }
            f.root.findViewById<View>(R.id.ime_assistance).visibility = View.VISIBLE
            f.measure()
            val collapsedHeight = f.root.height
            f.toggle.performClick(); f.finish()
            val expandedHeight = f.root.height
            val key = f.firstKeyBounds()
            f.toggle.performClick(); f.measure()
            assertEquals(expandedHeight, f.root.height)
            assertEquals(key, f.firstKeyBounds())
            f.seek(0.5f); f.finish()
            assertEquals(collapsedHeight, f.root.height)
            f.toggle.performClick(); f.finish()
            assertEquals(expandedHeight, f.root.height)
        }
    }

    @Test fun resizedImeWindowDoesNotFeedTheCurrentRevealHeightBackIntoKeyMeasurement() {
        for ((width, height) in listOf(412 to 800, 800 to 220)) {
            Fixture(width, height).use { f ->
                f.toggle.performClick(); f.finish()
                val geometry = f.gridGeometry()
                f.toggle.performClick()
                for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                    f.seek(fraction)
                    // The IME WindowManager can remeasure the input view with the just-revealed
                    // window height, unlike an Activity whose available height stays constant.
                    f.measureWindow(f.root.height)
                    assertEquals("the live IME window may shrink; its key rows must not", geometry, f.gridGeometry())
                }
                f.finish()
                f.toggle.performClick()
                for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                    f.seek(fraction)
                    f.measureWindow(f.root.height)
                    assertEquals("opening rows must not compress under the interim window cap", geometry, f.gridGeometry())
                }
                f.finish()
            }
        }
    }

    @Test fun freshExpansionMeasuresFullSizeKeysBeforeTheWindowHasExpanded() {
        val expected = Fixture().use { f -> f.toggle.performClick(); f.finish(); f.gridGeometry() }
        Fixture().use { f ->
            val collapsedHeight = f.root.height
            f.toggle.performClick()
            val animator = requireNotNull(f.animator())
            animator.currentPlayTime = animator.duration / 4
            // No generous expanded-host pass has occurred for this fresh view.
            f.measureWindow(collapsedHeight)
            assertEquals("the first expansion must use the available display budget, not the old tiny window", expected, f.gridGeometry())
            f.measure()
            val interimHeight = f.root.height
            animator.end()
            f.measureWindow(interimHeight)
            assertEquals("the last animation frame must remain full-size until the resized window catches up", expected, f.gridGeometry())
            f.measure()
            assertEquals(expected, f.gridGeometry())
        }
    }
}
