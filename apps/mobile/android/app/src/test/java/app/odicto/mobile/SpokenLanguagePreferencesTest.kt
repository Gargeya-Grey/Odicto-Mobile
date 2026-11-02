package app.odicto.mobile

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import app.odicto.mobile.storage.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpokenLanguagePreferencesTest {
    @Test fun vocabularyTagsPersistAndLoadWithoutDependingOnImmediatePublication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val terms = listOf("Odicto", "New Delhi", "नमस्ते")
        VoicePreferences.save(context, VoiceSettings(vocabularyHints = terms))
        val snapshot = MutableStateFlow(VoiceSettings())
        VoicePreferences.observe(flowOf(context.dataStore.data.first()), snapshot, MutableStateFlow(false))
        assertEquals(terms, snapshot.value.vocabularyHints)
        VoicePreferences.save(context, snapshot.value.copy(vocabularyHints = emptyList()))
        VoicePreferences.observe(flowOf(context.dataStore.data.first()), snapshot, MutableStateFlow(false))
        assertEquals(emptyList<String>(), snapshot.value.vocabularyHints)
    }

    @org.junit.After fun restoreDefaults() = runBlocking {
        VoicePreferences.save(ApplicationProvider.getApplicationContext(), VoiceSettings())
    }

    @Test fun persistedLanguageLoadsIntoFreshSnapshotAndLegacyDefaultsToAutomatic() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (language in listOf("", "hi", "en")) {
            VoicePreferences.save(context, VoiceSettings(spokenLanguage = language))
            val snapshot = MutableStateFlow(VoiceSettings())
            VoicePreferences.observe(flowOf(context.dataStore.data.first()), snapshot, MutableStateFlow(false))
            assertEquals(language, snapshot.value.spokenLanguage)
        }
        for (value in listOf<String?>(null, "invalid", "hi,en")) {
            val preferences = mutablePreferencesOf()
            if (value != null) preferences[stringPreferencesKey("voice_spoken_language")] = value
            val snapshot = MutableStateFlow(VoiceSettings(spokenLanguage = "hi"))
            VoicePreferences.observe(flowOf(preferences), snapshot, MutableStateFlow(false))
            assertEquals("", snapshot.value.spokenLanguage)
        }
    }
}
