package app.odicto.mobile

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Android refuses service starts from a background app, and the bubble lives in the background while
 * the capture service can already be gone. A rejected command must resolve the session instead of
 * throwing on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSessionCommandTest {
    private class BlockedContext(base: Context) : ContextWrapper(base) {
        override fun startService(service: Intent): ComponentName = throw IllegalStateException("Not allowed to start service Intent $service: app is in background")
    }

    private val context by lazy { BlockedContext(ApplicationProvider.getApplicationContext()) }

    @Before fun resetSession() {
        DictationCoordinator.cancel()
        VoiceSession.update { VoiceUi() }
    }

    @Test fun finishSurvivesARejectedBackgroundStart() {
        VoiceSession.update { it.copy(phase = "recording") }
        VoiceSession.finish(context)
        assertEquals("error", VoiceSession.state.value.phase)
        assertFalse(VoiceSession.state.value.busy)
        assertTrue(VoiceSession.state.value.message.isNotBlank())
    }

    @Test fun cancelSurvivesARejectedBackgroundStart() {
        VoiceSession.update { it.copy(phase = "recording") }
        VoiceSession.cancel(context)
        assertEquals("idle", VoiceSession.state.value.phase)
        assertFalse(VoiceSession.transportActive)
    }

    @Test fun cancelIsIgnoredWhileIdle() {
        VoiceSession.cancel(context)
        assertEquals("idle", VoiceSession.state.value.phase)
    }
}
