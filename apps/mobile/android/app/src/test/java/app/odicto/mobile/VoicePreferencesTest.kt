package app.odicto.mobile

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Test
class VoicePreferencesTest {
    @Test fun fieldPolishDefaultsDoNotChangeVoiceModelOrPrompt() {
        val settings = VoiceSettings()
        assertEquals("poolside/laguna-xs-2.1", settings.polishModel)
        assertEquals("", settings.polishSystemPrompt)
        assertEquals("", settings.model)
        assertEquals("", settings.systemPrompt)
    }

    @Test fun legacyStringSetListsLoadWithoutTypedStringCast() {
        val preferences = mutablePreferencesOf(
            stringSetPreferencesKey("voice_pinned_emoji") to setOf("🌟", "❤️"),
        )
        assertEquals(
            setOf("🌟", "❤️"),
            VoicePreferences.readList(preferences, "voice_pinned_emoji", ",", 5).toSet(),
        )
    }
}
