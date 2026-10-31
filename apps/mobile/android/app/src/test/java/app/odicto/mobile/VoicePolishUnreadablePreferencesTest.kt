package app.odicto.mobile

import androidx.datastore.preferences.core.emptyPreferences
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePolishUnreadablePreferencesTest {
    @Test fun failedReadReleasesReadyWithoutTrustingTheFallbackProvider() = runBlocking {
        val snapshot = MutableStateFlow(VoiceSettings())
        val ready = MutableStateFlow(false)
        VoicePreferences.observe(flow { throw IOException("unreadable fixture") }, snapshot, ready)
        val fallback = snapshot.value
        assertTrue(ready.value)
        assertEquals("openrouter", fallback.polishProvider)
        assertFalse(fallback.polishSettingsReadable)
        // An older, previously trusted write must not revive trust after the failed read.
        VoicePreferences.publishSaved(snapshot, fallback.copy(polishProvider = "gemini", polishSettingsReadable = true))
        assertFalse(snapshot.value.polishSettingsReadable)
    }

    @Test fun aValidEmptyStoreIsTrustedButALaterReadFailureRevokesTrust() = runBlocking {
        val snapshot = MutableStateFlow(VoiceSettings())
        val ready = MutableStateFlow(false)
        VoicePreferences.observe(flowOf(emptyPreferences()), snapshot, ready)
        assertTrue(ready.value)
        assertTrue(snapshot.value.polishSettingsReadable)
        assertEquals("openrouter", snapshot.value.polishProvider)

        VoicePreferences.observe(flow { throw IOException("unreadable fixture") }, snapshot, ready)
        assertTrue(ready.value)
        assertFalse(snapshot.value.polishSettingsReadable)
    }
}
