package app.odicto.mobile

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.OdictoKeyboardView
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class KeySpacingViewTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @org.junit.After fun restoreDefaults() = runBlocking {
        VoicePreferences.save(context, app.odicto.mobile.storage.VoiceSettings())
    }

    private fun gap(value: Int) = runBlocking {
        VoicePreferences.save(context, VoicePreferences.state.value.let { it.copy(keys = it.keys.copy(tapTolerance = 2 - value)) })
    }

    private fun layout(view: OdictoKeyboardView, width: Int = 360) {
        view.requestLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun caps(view: OdictoKeyboardView): List<View> = (0 until view.childCount).flatMap { index ->
        val row = view.getChildAt(index) as ViewGroup
        (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isClickable }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun toleranceKeepsVisibleGapsAndBoundaryOwnershipUnique() {
        gap(2)
        val view = OdictoKeyboardView(context)
        layout(view)
        val widths = mutableListOf<Int>()
        for (spacing in listOf(2, 1, 0)) {
            gap(spacing)
            view.refreshAppearance()
            layout(view)
            val keys = caps(view)
            val q = keys.single { it.contentDescription == "q" }
            val w = keys.single { it.contentDescription == "w" }
            widths += q.width
            assertEquals(4, w.left - q.right)
            val output = mutableListOf<OdictoKeyboardView.KeySpec>()
            view.setOnKeyListener { output += it }
            val row = q.parent as View
            val x = (q.right + w.left) / 2f
            val y = row.y + q.y + q.height / 2f
            touch(view, MotionEvent.ACTION_DOWN, q.right.toFloat(), y)
            touch(view, MotionEvent.ACTION_UP, q.right.toFloat(), y)
            if (spacing == 2) assertTrue(output.isEmpty()) else assertEquals(listOf("q"), output.map { it.output })
            output.clear()
            touch(view, MotionEvent.ACTION_DOWN, x, y)
            touch(view, MotionEvent.ACTION_UP, x, y)
            if (spacing == 0) assertEquals(listOf("w"), output.map { it.output }) else assertTrue(output.isEmpty())
        }
        assertEquals(1, widths.distinct().size)
    }

    @Test fun defaultForgivingRecoversPaintedGapWithoutResizingKeys() {
        runBlocking { VoicePreferences.save(context, app.odicto.mobile.storage.VoiceSettings()) }
        val view = OdictoKeyboardView(context)
        layout(view)
        val q = caps(view).single { it.contentDescription == "q" }
        val w = caps(view).single { it.contentDescription == "w" }
        assertEquals(4, w.left - q.right)
        val output = mutableListOf<OdictoKeyboardView.KeySpec>()
        view.setOnKeyListener { output += it }
        app.odicto.mobile.ime.KeyLatencyProbe.outcomeSnapshot(clear = true)
        val x = (q.right + w.left) / 2f
        val y = (q.parent as View).y + q.y + q.height / 2f
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("w"), output.map { it.output })
        assertEquals(1L, app.odicto.mobile.ime.KeyLatencyProbe.outcomeSnapshot().getValue(app.odicto.mobile.ime.KeyLatencyProbe.Outcome.MARGIN_RECOVERED))
    }

    @Test fun ownedTouchCellsNeverOverlapOrLeaveTheKeyboard() {
        val view = OdictoKeyboardView(context)
        val cellsField = OdictoKeyboardView::class.java.getDeclaredField("cells").apply { isAccessible = true }
        for (spacing in 0..2) for (width in listOf(360, 824)) for (page in OdictoKeyboardView.Page.entries) {
            gap(spacing)
            view.showPage(page)
            layout(view, width)
            val bounds = (cellsField.get(view) as List<*>).map { cell ->
                cell!!.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(cell) as android.graphics.Rect
            }
            bounds.forEachIndexed { index, rect ->
                assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= view.width && rect.bottom <= view.height)
                bounds.drop(index + 1).forEach { other -> assertFalse(android.graphics.Rect.intersects(rect, other)) }
            }
        }
    }

    @Test fun allSpacingChoicesPreserveInertTabletGapsAndEqualSpaces() {
        val view = OdictoKeyboardView(context)
        for (spacing in 0..2) for (page in OdictoKeyboardView.Page.entries) {
            gap(spacing)
            view.showPage(page)
            layout(view, 824)
            val spaces = caps(view).filter { it.contentDescription == "Space" }
            assertEquals(2, spaces.size)
            assertEquals(spaces[0].width, spaces[1].width)
            val output = mutableListOf<OdictoKeyboardView.KeySpec>()
            view.setOnKeyListener { output += it }
            for (index in 0 until view.childCount) {
                val row = view.getChildAt(index)
                for (x in listOf(0f, 412f, 823f)) {
                    val y = row.y + row.height / 2f
                    touch(view, MotionEvent.ACTION_DOWN, x, y)
                    touch(view, MotionEvent.ACTION_UP, x, y)
                }
            }
            assertTrue(output.isEmpty())
        }
    }

    @Test fun serviceRefreshesAppearanceButIgnoresUnrelatedPreferenceChanges() {
        gap(2)
        val owner = org.robolectric.Robolectric.buildService(app.odicto.mobile.ime.OdictoImeService::class.java).create()
        try {
            val root = owner.get().onCreateInputView()
            val view = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
            layout(view)
            val original = caps(view).first { it.contentDescription == "q" }
            gap(0)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            layout(view)
            val changed = caps(view).first { it.contentDescription == "q" }
            assertNotSame(original, changed)
            assertEquals(original.width, changed.width)
            runBlocking { VoicePreferences.save(context, VoicePreferences.state.value.copy(showTextPreview = false)) }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            layout(view)
            assertSame(changed, caps(view).first { it.contentDescription == "q" })
        } finally { owner.destroy() }
    }

    @Test fun spacingRefreshCancelsPendingSpaceWithoutDispatchingStaleRelease() {
        gap(2)
        val view = OdictoKeyboardView(context)
        layout(view)
        val space = caps(view).single { it.contentDescription == "Space" }
        val x = space.x + space.width / 2f
        val y = (space.parent as View).y + space.y + space.height / 2f
        val output = mutableListOf<OdictoKeyboardView.KeySpec>()
        view.setOnKeyListener { output += it }
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        gap(0)
        view.refreshAppearance()
        layout(view)
        touch(view, MotionEvent.ACTION_UP, x, y)
        assertTrue(output.isEmpty())
        val fresh = caps(view).single { it.contentDescription == "q" }
        val row = fresh.parent as View
        touch(view, MotionEvent.ACTION_DOWN, fresh.x + fresh.width / 2f, row.y + fresh.y + fresh.height / 2f)
        touch(view, MotionEvent.ACTION_UP, fresh.x + fresh.width / 2f, row.y + fresh.y + fresh.height / 2f)
        assertEquals(listOf("q"), output.map { it.output })
    }
}
