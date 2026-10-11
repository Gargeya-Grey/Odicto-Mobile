package app.odicto.mobile

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardSplitLayoutTest {
    @org.junit.Before fun preciseTargets() = kotlinx.coroutines.runBlocking {
        app.odicto.mobile.storage.VoicePreferences.save(ApplicationProvider.getApplicationContext(), app.odicto.mobile.storage.VoiceSettings(keys = app.odicto.mobile.storage.KeySettings(tapTolerance = 0)))
    }
    @org.junit.After fun restoreTargets() = kotlinx.coroutines.runBlocking {
        app.odicto.mobile.storage.VoicePreferences.save(ApplicationProvider.getApplicationContext(), app.odicto.mobile.storage.VoiceSettings())
    }

    private fun keyboard(landscape: Boolean, densityDpi: Int = 160): OdictoKeyboardView {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            this.densityDpi = densityDpi
        }
        return OdictoKeyboardView(base.createConfigurationContext(config))
    }

    private fun layout(view: View, width: Int) {
        view.requestLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun caps(view: OdictoKeyboardView) = (0 until view.childCount).flatMap { r ->
        val row = view.getChildAt(r) as ViewGroup
        (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isClickable }
    }

    @Test fun orientationPolicyUsesMeasuredContentWidth() {
        for (landscape in listOf(false, true)) for (width in listOf(320, 400, 591, 592, 599, 600, 615, 616, 640, 800, 824, 951, 1024, 1318)) {
            val view = keyboard(landscape)
            layout(view, width)
            val gap = (width - if (landscape) 576 else 600).coerceIn(0, if (landscape) 375 else 64).let { if (it < 16) 0 else it }
            assertEquals("landscape=$landscape width=$width", if (gap == 0) 1 else 2, caps(view).count { it.contentDescription == "Space" })
            if (gap > 0) {
                val row = view.getChildAt(0) as ViewGroup
                val center = (0 until row.childCount).map { row.getChildAt(it) }.single { !it.isClickable && it.left <= width / 2 && it.right > width / 2 }
                assertTrue("gap=$gap actual=${center.width}", center.width in gap..gap + 1)
                val first = caps(view).first()
                val margin = (2 * view.resources.displayMetrics.density).toInt()
                val edge = (width - gap) / 12
                assertTrue(first.left in edge + margin..edge + margin + 4)
            }
        }
    }

    @Test fun allSplitPagesShareColumnsSpacesAndOuterControls() {
        for (landscape in listOf(false, true)) for ((width, dpi) in listOf(640 to 160, 824 to 160, 1318 to 160, 1752 to 340, 2800 to 340, 1753 to 340)) {
            val view = keyboard(landscape, dpi)
            for (page in OdictoKeyboardView.Page.entries) {
                view.showPage(page)
                layout(view, width)
                val rendered = mutableListOf<Pair<View, OdictoKeyboardView.KeySpec>>()
                caps(view).forEach { cap -> view.setOnKeyListener { rendered += cap to it }; cap.performClick() }
                val chars = rendered.filter { it.second.key == OdictoKeyboardView.Key.CHARACTER }.map { it.first.width }
                assertTrue("$landscape/$width/$page character widths $chars", chars.max() - chars.min() <= 1)
                val spaces = rendered.filter { it.second.key == OdictoKeyboardView.Key.SPACE }.map { it.first.width }
                assertEquals(2, spaces.size)
                assertEquals(spaces.first(), spaces.last())
                val backspace = rendered.single { it.second.key == OdictoKeyboardView.Key.BACKSPACE }.first
                val enter = rendered.single { it.second.key == OdictoKeyboardView.Key.ENTER }.first
                assertEquals(backspace.width, enter.width)
                assertEquals(backspace.right, enter.right)
                rendered.filter { it.second.control || it.second.key == OdictoKeyboardView.Key.SHIFT }.forEach { assertEquals(backspace.width, it.first.width) }
                caps(view).forEach { assertTrue(it.left >= 0 && it.right <= width && it.width > 0) }
            }
        }
    }

    @Test fun tabletThumbMarginsAndTopRowsAreMirroredWithoutShrinkingPhoneEdges() {
        for (landscape in listOf(false, true)) {
            val width = if (landscape) 2800 else 1752
            val view = keyboard(landscape, 340)
            layout(view, width)
            val keys = caps(view)
            val first = keys.first { it.contentDescription == "1" }
            val last = keys.first { it.contentDescription == "0" }
            val outer = first.left
            assertTrue(outer / view.resources.displayMetrics.density >= 60f)
            assertTrue(kotlin.math.abs(outer - (width - last.right)) <= 1)
            for (row in listOf("1234567890", "qwertyuiop")) {
                for (i in 0 until 5) {
                    val left = keys.first { it.contentDescription == row[i].toString() }
                    val right = keys.first { it.contentDescription == row[9 - i].toString() }
                    assertEquals(left.width, right.width)
                    assertTrue(kotlin.math.abs(left.left - (width - right.right)) <= 1)
                }
            }
        }
        for (width in listOf(320, 360, 400, 480)) {
            val view = keyboard(false)
            layout(view, width)
            val keys = caps(view)
            assertTrue(keys.first().left <= 3)
            assertEquals(1, keys.count { it.contentDescription == "Space" })
        }
    }

    @Test fun sameWidthRotationRebuildsActualGeometryAndKeepsPage() {
        val view = keyboard(false)
        view.showPage(OdictoKeyboardView.Page.SYMBOLS)
        layout(view, 1024)
        val old = caps(view).first()
        val config = view.resources.configuration
        config.orientation = Configuration.ORIENTATION_LANDSCAPE
        view.dispatchConfigurationChanged(config)
        layout(view, 1024)
        assertNotSame(old, caps(view).first())
        assertEquals("₹", caps(view).first().contentDescription)
        assertTrue(caps(view).first().width < old.width)
        assertTrue(caps(view).first().left < old.left)
    }

    @Test fun splitBlanksAndCapEdgesAreInertButEveryCenterWorks() {
        for (landscape in listOf(false, true)) {
            val view = keyboard(landscape)
            for (page in OdictoKeyboardView.Page.entries) {
                view.showPage(page)
                layout(view, 1024)
                val output = mutableListOf<OdictoKeyboardView.KeySpec>()
                view.setOnKeyListener { output += it }
                fun tap(x: Float, y: Float) {
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(0, 0, action, x, y, 0)
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                for (r in 0 until view.childCount) {
                    val row = view.getChildAt(r) as ViewGroup
                    for (i in 0 until row.childCount) {
                        val child = row.getChildAt(i)
                        val y = row.y + child.y + child.height / 2f
                        if (!child.isClickable) {
                            assertFalse(child.isFocusable)
                            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, child.importantForAccessibility)
                            for (x in child.left until child.right) tap(x + 0.5f, y)
                        } else {
                            tap(child.right.toFloat(), y)
                            tap(child.left - 0.1f, y)
                        }
                    }
                }
                assertTrue(output.isEmpty())
                caps(view).forEach {
                    val row = it.parent as View
                    tap(it.x + it.width / 2f, row.y + it.y + it.height / 2f)
                }
                assertEquals(caps(view).size, output.size)
            }
        }
    }

    @Test fun everyPagePreservesSemanticOutputOrderAndLabels() {
        for (page in OdictoKeyboardView.Page.entries) {
            fun inventory(view: OdictoKeyboardView, width: Int): List<Triple<OdictoKeyboardView.Key, String, String>> {
                view.showPage(page)
                layout(view, width)
                val result = mutableListOf<Triple<OdictoKeyboardView.Key, String, String>>()
                view.setOnKeyListener { result += Triple(it.key, it.output, it.label) }
                caps(view).forEach { it.performClick() }
                return result.filterIndexed { index, spec -> spec.first != OdictoKeyboardView.Key.SPACE || result.take(index).none { it.first == OdictoKeyboardView.Key.SPACE } }
            }
            val expected = inventory(keyboard(false), 400)
            for (landscape in listOf(false, true)) assertEquals(expected, inventory(keyboard(landscape), 1024))
        }
    }
}
