package app.odicto.mobile

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.recording.AudioCaptureService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Releasing the mic before it opened must end the session and stop the service, not leave it started. */
@RunWith(RobolectricTestRunner::class)
class AudioCaptureServiceTest {
    @Before fun resetSession() {
        DictationCoordinator.cancel()
        VoiceSession.update { VoiceUi() }
        VoiceSession.transportActive = false
    }

    @Test fun stopBeforeCaptureStopsTheServiceAndTheSession() {
        val controller = Robolectric.buildService(AudioCaptureService::class.java)
        val service = controller.create().get()
        val stop = Intent(ApplicationProvider.getApplicationContext(), AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP)
        controller.withIntent(stop).startCommand(0, 0)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("idle", VoiceSession.state.value.phase)
        assertEquals(false, VoiceSession.transportActive)
    }
}
