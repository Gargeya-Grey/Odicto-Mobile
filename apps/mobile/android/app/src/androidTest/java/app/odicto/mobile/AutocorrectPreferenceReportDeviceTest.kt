package app.odicto.mobile

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reports only the loaded public enable switch; opens no UI and never changes preferences. */
@RunWith(AndroidJUnit4::class)
class AutocorrectPreferenceReportDeviceTest {
    @Test fun reportPersistedAutocorrectEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = runCatching {
            VoicePreferences.initialize(instrumentation.targetContext)
            withTimeout(5_000) { VoicePreferences.ready.first { it } }
        }.getOrDefault(false)
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("preferencesReady", ready)
            if (ready) putBoolean("autocorrectEnabled", VoicePreferences.state.value.keys.autocorrectEnabled)
        })
        assertTrue("The public autocorrect preference became ready", ready)
    }
}
