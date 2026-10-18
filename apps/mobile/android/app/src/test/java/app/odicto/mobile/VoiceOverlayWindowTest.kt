package app.odicto.mobile

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.overlay.OverlayGeometry
import app.odicto.mobile.overlay.OverlayPresentation
import app.odicto.mobile.overlay.VoiceControlView
import app.odicto.mobile.overlay.VoiceOverlayService
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35])
class VoiceOverlayWindowTest {
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(service: VoiceOverlayService, name: String): T =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service) as T

    /** The floating overlay at its first-run place: the right edge of the screen. */
    private fun overlay(test: (VoiceOverlayService) -> Unit) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val previous = VoicePreferences.state.value
        ShadowSettings.setCanDrawOverlays(true)
        VoiceSession.update { VoiceUi() }
        OverlayPresentation.collapse()
        VoicePreferences.save(context, VoiceSettings(enabled = true))
        val controller = Robolectric.buildService(VoiceOverlayService::class.java).create()
        try {
            advance(1)
            test(controller.get())
        } finally {
            controller.destroy()
            OverlayPresentation.collapse()
            VoicePreferences.save(context, previous)
            ShadowSettings.setCanDrawOverlays(false)
        }
    }

    private fun mic(service: VoiceOverlayService): View = field<FrameLayout>(service, "micHost").getChildAt(0)

    private fun openDrawer(mic: View) {
        val action = mic.createAccessibilityNodeInfo().actionList.single { it.label == "Show voice options" }
        assertTrue(mic.performAccessibilityAction(action.id, null))
    }

    private fun touch(mic: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { mic.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun draggingStraightDownFromTheOpenDrawerKeepsTheIconInItsColumn() = overlay { service ->
        val micWindow = field<WindowManager.LayoutParams>(service, "micParams")
        val drawerWindow = field<WindowManager.LayoutParams>(service, "params")
        val density = service.resources.displayMetrics.density
        val column = micWindow.x
        openDrawer(mic(service))
        advance(1)
        assertTrue("the drawer opens toward the left, so its window starts left of the icon", drawerWindow.x < column - (200 * density))
        val down = SystemClock.uptimeMillis()
        touch(mic(service), MotionEvent.ACTION_DOWN, down, 28f, 28f)
        advance(20)
        touch(mic(service), MotionEvent.ACTION_MOVE, down, 28f, 228f)
        assertEquals("the icon must not jump sideways when a vertical drag folds the drawer", column, micWindow.x)
        advance(20)
        touch(mic(service), MotionEvent.ACTION_UP, down, 28f, 228f)
        advance(100)
        assertEquals("the icon ends the drag in the column it started in", column, micWindow.x)
        val control = field<VoiceControlView>(service, "control")
        assertEquals("the folded drawer window sits back under the icon", column - control.micOffsetPx, drawerWindow.x)
        assertEquals("the saved place is still the right edge", OverlayGeometry.ANCHOR_SCALE, VoicePreferences.state.value.anchorX)
    }

    @Test fun theDrawerWindowMovesAtOnceInsteadOfBeingSlidByTheSystem() = overlay { service ->
        // Android slides a window that changes place and size in one update. The drawer window does both when it opens
        // toward the left or above, and a slide would start the drawer away from the icon.
        assertFalse(field<WindowManager.LayoutParams>(service, "params").canPlayMoveAnimation())
    }
}
