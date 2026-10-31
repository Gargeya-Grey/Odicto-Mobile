package app.odicto.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import app.odicto.mobile.storage.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePolishPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @org.junit.After fun restoreDefaults() = runBlocking {
        VoicePreferences.save(context, VoiceSettings())
    }

    @Test fun legacyPolishModelSurvivesUpgradeAndGeminiChoiceIsPersisted() = runBlocking {
        val marker = "legacy-polish-${System.nanoTime()}"
        val providerKey = stringPreferencesKey("voice_polish_provider")
        val modelKey = stringPreferencesKey("voice_polish_model")
        context.dataStore.edit {
            it.clear()
            it[stringPreferencesKey("voice_model")] = marker
            it[modelKey] = "fixture/legacy-model"
        }
        VoicePreferences.initialize(context)
        val legacy = withTimeout(5_000) { VoicePreferences.state.first { it.model == marker } }
        assertEquals("openrouter", legacy.polishProvider)
        assertEquals("fixture/legacy-model", legacy.polishModel)
        assertTrue(legacy.polishSettingsReadable)

        VoicePreferences.save(context, legacy.copy(polishProvider = "gemini", polishModel = "gemini-fixture"))
        val stored = context.dataStore.data.first()
        assertEquals("gemini", stored[providerKey])
        assertEquals("gemini-fixture", stored[modelKey])

        val reloadedMarker = "reloaded-polish-${System.nanoTime()}"
        context.dataStore.edit { it[stringPreferencesKey("voice_model")] = reloadedMarker }
        val reloaded = withTimeout(5_000) { VoicePreferences.state.first { it.model == reloadedMarker } }
        assertEquals("gemini", reloaded.polishProvider)
        assertEquals("gemini-fixture", reloaded.polishModel)

        val invalidMarker = "invalid-provider-${System.nanoTime()}"
        context.dataStore.edit {
            it[stringPreferencesKey("voice_model")] = invalidMarker
            it[providerKey] = "unexpected-provider"
        }
        val invalid = withTimeout(5_000) { VoicePreferences.state.first { it.model == invalidMarker } }
        assertFalse(invalid.polishSettingsReadable)
    }
}
