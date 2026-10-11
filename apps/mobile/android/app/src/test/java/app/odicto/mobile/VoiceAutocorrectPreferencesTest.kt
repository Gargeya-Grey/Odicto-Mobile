package app.odicto.mobile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.storage.KeySettings
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import app.odicto.mobile.storage.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceAutocorrectPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val enabledKey = booleanPreferencesKey("voice_autocorrect_enabled")

    @org.junit.After fun restoreDefaults() = runBlocking {
        VoicePreferences.save(context, VoiceSettings())
    }

    @Test fun absentFlagDefaultsOffAndSavedValuesAreReadFromDataStore() = runBlocking {
        val marker = "autocorrect-absent-${System.nanoTime()}"
        context.dataStore.edit {
            it.clear()
            it[stringPreferencesKey("voice_model")] = marker
            it[stringPreferencesKey("voice_polish_model")] = "fixture/polish-model"
            it[stringPreferencesKey("voice_polish_system_prompt")] = "Fixture polish prompt"
        }
        VoicePreferences.initialize(context)
        val initial = withTimeout(5_000) { VoicePreferences.state.first { it.model == marker } }
        assertEquals(false, initial.keys.autocorrectEnabled)
        assertEquals("fixture/polish-model", initial.polishModel)
        assertEquals("Fixture polish prompt", initial.polishSystemPrompt)

        for (enabled in listOf(true, false)) {
            VoicePreferences.save(context, initial.copy(keys = KeySettings(autocorrectEnabled = enabled)))
            assertEquals(enabled, context.dataStore.data.first()[enabledKey])
            assertEquals(enabled, VoicePreferences.state.value.keys.autocorrectEnabled)

            // A fresh store emission, rather than save's immediate publication, proves the read path.
            val loadedMarker = "autocorrect-loaded-$enabled-${System.nanoTime()}"
            context.dataStore.edit {
                it[enabledKey] = !enabled
                it[stringPreferencesKey("voice_model")] = loadedMarker
            }
            val loaded = withTimeout(5_000) { VoicePreferences.state.first { it.model == loadedMarker } }
            assertEquals(!enabled, loaded.keys.autocorrectEnabled)
            assertEquals("fixture/polish-model", loaded.polishModel)
            assertEquals("Fixture polish prompt", loaded.polishSystemPrompt)
        }
    }
}
