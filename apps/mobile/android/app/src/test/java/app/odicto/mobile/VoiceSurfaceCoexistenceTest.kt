package app.odicto.mobile

import android.os.Looper
import android.view.View
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.ime.OdictoImeService
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
class VoiceSurfaceCoexistenceTest {
    @Test fun overlayStaysVisibleWithImeAndBothReflectTheSameRecording() = runBlocking {
        val ime = Robolectric.buildService(OdictoImeService::class.java).create()
        val previous = VoicePreferences.state.value
        val previousVoice = VoiceSession.state.value
        ShadowSettings.setCanDrawOverlays(true)
        VoicePreferences.save(ime.get(), VoiceSettings(enabled = true, y = 450))
        VoiceSession.update { VoiceUi() }
        val root = ime.get().onCreateInputView()
        ime.get().onWindowShown()
        val overlay = Robolectric.buildService(VoiceOverlayService::class.java).create()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            fun control(): VoiceControlView? = overlay.get().javaClass.getDeclaredField("control").apply { isAccessible = true }.get(overlay.get()) as? VoiceControlView
            assertTrue(DictationCoordinator.keyboardVisible.value)
            assertNotNull("enabled overlay must coexist with visible IME", control())
            fun placement() = overlay.get().javaClass.getDeclaredField("params").apply { isAccessible = true }
                .get(overlay.get()) as android.view.WindowManager.LayoutParams
            val originalY = placement().y
            DictationCoordinator.keyboardTopPx.value = 300
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("bubble must clear the expanded toolbar", placement().y + placement().height <= 292)
            assertEquals("temporary avoidance must not rewrite the saved position", 450, VoicePreferences.state.value.y)
            control()!!.drag?.invoke(10f, 0f)
            control()!!.dragEnd?.invoke()
            assertEquals("horizontal dragging must preserve the vertical anchor", 450, VoicePreferences.state.value.y)
            DictationCoordinator.keyboardTopPx.value = null
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("return to the preferred anchor when space is available", originalY, placement().y)
            val strip = root.findViewById<android.widget.FrameLayout>(R.id.ime_top_row).getChildAt(0) as VoiceControlView
            VoiceSession.update { VoiceUi(phase = "recording", locked = true) }
            DictationCoordinator.keyboardTopPx.value = 120
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("busy chrome must fit a short viewport", placement().y + placement().height <= 112)
            // The floating microphone has a window of its own, so it is found there; the keyboard's strip still holds its own.
            val micHost = overlay.get().javaClass.getDeclaredField("micHost").apply { isAccessible = true }.get(overlay.get()) as android.widget.FrameLayout
            for (surface in listOf<android.view.ViewGroup>(strip, micHost)) {
                assertTrue((0 until surface.childCount).map { surface.getChildAt(it) }.any {
                    it.visibility == View.VISIBLE && it.contentDescription == "Finish recording"
                })
            }
            DictationCoordinator.keyboardTopPx.value = 60
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("do not cover the IME when even the floating mic cannot fit", control())
            DictationCoordinator.keyboardTopPx.value = null
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("restore the floating mic when space returns", control())
        } finally {
            VoiceSession.update { previousVoice }
            DictationCoordinator.keyboardTopPx.value = null
            overlay.destroy(); ime.destroy()
            VoicePreferences.save(root.context, previous)
            ShadowSettings.setCanDrawOverlays(false)
        }
    }
}
