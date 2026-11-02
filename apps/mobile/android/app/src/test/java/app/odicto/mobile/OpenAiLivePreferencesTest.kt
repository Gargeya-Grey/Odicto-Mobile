package app.odicto.mobile

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.plugins.VoicePlugin
import app.odicto.mobile.storage.*
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [VoicePluginTest.PluginHost::class, OpenAiLivePreferencesTest.Credentials::class])
class OpenAiLivePreferencesTest {
    @Implements(SecureCredentialStore::class)
    class Credentials {
        @Implementation fun get(name: String): String? = values[name]
        @Implementation fun put(name: String, value: String) { values[name] = value }
        @Implementation fun remove(name: String) { values.remove(name) }
        companion object { val values = mutableMapOf<String, String>() }
    }

    private class Call(data: JSObject = JSObject()) : PluginCall(null, "OdictoVoice", "test", "test", data) {
        var result: JSObject? = null
        var error: String? = null
        var finished = false
        override fun resolve(data: JSObject) { result = data; finished = true }
        override fun resolve() { finished = true }
        override fun reject(message: String) { error = message; finished = true }
    }

    private fun await(call: Call) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!call.finished && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(call.finished)
        assertNull(call.error)
    }

    @org.junit.After fun restoreDefaults() = runBlocking {
        VoicePreferences.save(ApplicationProvider.getApplicationContext(), VoiceSettings())
        Credentials.values.clear()
    }

    @Test fun oldAndMalformedStoresDefaultToGeminiWithoutResettingOtherSettings() = runBlocking {
        val snapshot = MutableStateFlow(VoiceSettings())
        VoicePreferences.observe(flowOf(mutablePreferencesOf(
            intPreferencesKey("voice_live_provider") to 42,
            intPreferencesKey("voice_openai_live_model") to 42,
            androidx.datastore.preferences.core.stringPreferencesKey("voice_model") to "fixture/answer",
        )), snapshot, MutableStateFlow(false))
        assertEquals("gemini", snapshot.value.liveProvider)
        assertEquals("gpt-live-transcribe", snapshot.value.openaiLiveModel)
        assertEquals("fixture/answer", snapshot.value.model)
        assertEquals("gemini", VoiceSettings().liveProvider)
    }

    @Test fun providerAndEachModelPersistIndependently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VoicePreferences.save(context, VoiceSettings(liveProvider = "openai", openaiLiveModel = "gpt-realtime-whisper", liveModel = "fixture-gemini", model = "fixture-answer"))
        val snapshot = MutableStateFlow(VoiceSettings())
        VoicePreferences.observe(flowOf(context.dataStore.data.first()), snapshot, MutableStateFlow(false))
        assertEquals("openai", snapshot.value.liveProvider)
        assertEquals("gpt-realtime-whisper", snapshot.value.openaiLiveModel)
        assertEquals("fixture-gemini", snapshot.value.liveModel)
        assertEquals("fixture-answer", snapshot.value.model)
        val normalized = MutableStateFlow(VoiceSettings())
        VoicePreferences.publishSaved(normalized, VoiceSettings(liveProvider = "bad", openaiLiveModel = "bad"))
        assertEquals("gemini", normalized.value.liveProvider)
        assertEquals("gpt-live-transcribe", normalized.value.openaiLiveModel)
    }

    @Test fun optionalKeyAndProviderRoundTripWithoutChangingAiOrGeminiModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { VoicePreferences.save(context, VoiceSettings(liveModel = "fixture-gemini", model = "fixture-answer")) }
        VoicePreferences.initialize(context)
        val plugin = VoicePlugin()
        fun status(): JSObject { val call = Call(); plugin.status(call); await(call); return call.result!! }
        fun save(value: JSObject) { val call = Call(value); plugin.save(call); await(call) }
        assertFalse(status().getBoolean("openaiKeySet"))
        save(JSObject().put("openaiKey", "fake-test-key").put("liveProvider", "openai").put("openaiLiveModel", "gpt-realtime-whisper"))
        assertEquals("fake-test-key", Credentials.values["voice-openai"])
        assertTrue(status().getBoolean("openaiKeySet"))
        assertEquals("openai", status().getString("liveProvider"))
        save(JSObject().put("keySize", "large"))
        assertEquals("gpt-realtime-whisper", status().getString("openaiLiveModel"))
        assertEquals("fixture-gemini", status().getString("liveModel"))
        assertEquals("fixture-answer", status().getString("model"))
        for (patch in listOf(
            JSObject().put("liveProvider", "unsupported"),
            JSObject().put("liveProvider", 42),
            JSObject().put("openaiLiveModel", "gpt-chat"),
            JSObject().put("openaiLiveModel", JSONObjectNull),
        )) {
            val bad = Call(patch); plugin.save(bad)
            assertNotNull(bad.error)
            assertEquals("openai", status().getString("liveProvider"))
            assertEquals("gpt-realtime-whisper", status().getString("openaiLiveModel"))
        }
        save(JSObject().put("openaiKey", ""))
        assertFalse(status().getBoolean("openaiKeySet"))
        assertFalse(Credentials.values.containsKey("voice-openai"))
    }

    companion object { private val JSONObjectNull = org.json.JSONObject.NULL }
}
