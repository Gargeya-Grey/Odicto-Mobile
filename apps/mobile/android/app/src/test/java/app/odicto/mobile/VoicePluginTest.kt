package app.odicto.mobile

import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.plugins.VoicePlugin
import app.odicto.mobile.storage.SecureCredentialStore
import app.odicto.mobile.storage.VoicePreferences
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [VoicePluginTest.PluginHost::class, VoicePluginTest.EmptyCredentials::class])
class VoicePluginTest {
    @org.junit.After fun restoreDefaults() = kotlinx.coroutines.runBlocking {
        VoicePreferences.save(ApplicationProvider.getApplicationContext(), app.odicto.mobile.storage.VoiceSettings())
    }

    @Implements(Plugin::class)
    class PluginHost {
        @Implementation fun getContext(): Context = ApplicationProvider.getApplicationContext()
        @Implementation fun getActivity(): AppCompatActivity? = null
    }

    @Implements(SecureCredentialStore::class)
    class EmptyCredentials {
        @Implementation fun get(name: String): String? = null
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
        while (!call.finished && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("plugin call finished", true, call.finished)
        assertNull(call.error)
    }

    @Test fun autocorrectRoundTripPreservesOmittedSettingAndIndependentPolishConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        kotlinx.coroutines.runBlocking {
            VoicePreferences.save(context, app.odicto.mobile.storage.VoiceSettings(
                provider = "openrouter",
                model = "fixture/voice-model",
                systemPrompt = "Fixture voice prompt",
                polishModel = "fixture/polish-model",
                polishSystemPrompt = "Fixture polish prompt",
            ))
        }
        VoicePreferences.initialize(context)
        val plugin = VoicePlugin()
        val initial = Call()
        plugin.status(initial)
        await(initial)
        assertEquals(false, initial.result!!.getBoolean("autocorrectEnabled"))

        val enable = Call(JSObject().put("autocorrectEnabled", true))
        plugin.save(enable)
        await(enable)
        for (patch in listOf(
            JSObject().put("keySize", "large"),
            JSObject().put("autocorrectEnabled", "false"),
            JSObject().put("autocorrectEnabled", org.json.JSONObject.NULL),
        )) {
            val save = Call(patch)
            plugin.save(save)
            await(save)
            val status = Call()
            plugin.status(status)
            await(status)
            assertEquals(true, status.result!!.getBoolean("autocorrectEnabled"))
            assertEquals("large", status.result!!.getString("keySize"))
            assertEquals("fixture/voice-model", status.result!!.getString("model"))
            assertEquals("Fixture voice prompt", status.result!!.getString("systemPrompt"))
            assertEquals("fixture/polish-model", status.result!!.getString("polishModel"))
            assertEquals("Fixture polish prompt", status.result!!.getString("polishSystemPrompt"))
        }
        val disable = Call(JSObject().put("autocorrectEnabled", false))
        plugin.save(disable)
        await(disable)
        val disabled = Call()
        plugin.status(disabled)
        await(disabled)
        assertEquals(false, disabled.result!!.getBoolean("autocorrectEnabled"))
        assertEquals("fixture/polish-model", disabled.result!!.getString("polishModel"))
        assertEquals("Fixture polish prompt", disabled.result!!.getString("polishSystemPrompt"))
    }

    @Test fun promptModesRoundTripAndAnOmittedOrUnknownModeNeverChangesTheSavedOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        kotlinx.coroutines.runBlocking {
            VoicePreferences.save(context, app.odicto.mobile.storage.VoiceSettings(systemPrompt = "Fixture voice prompt", polishSystemPrompt = "Fixture polish prompt"))
        }
        VoicePreferences.initialize(context)
        val plugin = VoicePlugin()
        fun status(): JSObject { val c = Call(); plugin.status(c); await(c); return c.result!! }
        fun save(patch: JSObject): Call { val c = Call(patch); plugin.save(c); await(c); return c }

        assertEquals("append", status().getString("systemPromptMode"))
        assertEquals("append", status().getString("polishSystemPromptMode"))

        assertNull(save(JSObject().put("systemPromptMode", "replace").put("polishSystemPromptMode", "replace")).error)
        assertEquals("replace", status().getString("systemPromptMode"))
        assertEquals("replace", status().getString("polishSystemPromptMode"))

        assertNull(save(JSObject().put("keySize", "large")).error)
        assertEquals("a save that does not mention the mode keeps it", "replace", status().getString("systemPromptMode"))

        val refused = Call(JSObject().put("systemPromptMode", "sepia")).also { plugin.save(it) }
        val deadline = System.nanoTime() + 5_000_000_000
        while (!refused.finished && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertNotNull("an unknown mode is refused", refused.error)
        assertEquals("replace", status().getString("systemPromptMode"))

        assertNull(save(JSObject().put("systemPromptMode", "append")).error)
        assertEquals("append", status().getString("systemPromptMode"))
        assertEquals("the other prompt's mode is untouched", "replace", status().getString("polishSystemPromptMode"))
    }
    @Test fun toleranceOnlySavePreservesCustomProviderModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        kotlinx.coroutines.runBlocking {
            VoicePreferences.save(context, app.odicto.mobile.storage.VoiceSettings(provider = "openrouter", model = "fixture/custom-model"))
        }
        VoicePreferences.initialize(context)
        val plugin = VoicePlugin()
        val save = Call(JSObject().put("tapTolerance", 1))
        plugin.save(save)
        await(save)
        val status = Call()
        plugin.status(status)
        await(status)
        assertEquals("fixture/custom-model", status.result!!.getString("model"))
        assertEquals(1, status.result!!.getInt("tapTolerance"))
    }

    @Test fun spacingStatusRoundTripPreservesOmittedAndInvalidValues() {
        VoicePreferences.initialize(ApplicationProvider.getApplicationContext())
        val call = Call()
        VoicePlugin().status(call)
        await(call)
        assertEquals(2, call.result!!.getInt("tapTolerance"))
        val plugin = VoicePlugin()
        for ((supplied, expected) in listOf(0 to 0, 1 to 1, 2 to 2, -5 to 0, 99 to 2, 1.0 to 1, Long.MAX_VALUE to 2, Long.MIN_VALUE to 0)) {
            val save = Call(JSObject().put("tapTolerance", supplied))
            plugin.save(save)
            await(save)
            val status = Call()
            plugin.status(status)
            await(status)
            assertEquals(expected, status.result!!.getInt("tapTolerance"))
        }
        val select = Call(JSObject().put("tapTolerance", 1))
        plugin.save(select)
        await(select)
        for (patch in listOf(JSObject().put("keySize", "large"), JSObject().put("tapTolerance", "0"), JSObject().put("tapTolerance", true), JSObject().put("tapTolerance", org.json.JSONObject.NULL), JSObject().put("tapTolerance", 0.5), JSObject().put("tapTolerance", JSObject()))) {
            val save = Call(patch)
            plugin.save(save)
            await(save)
            val status = Call()
            plugin.status(status)
            await(status)
            assertEquals(1, status.result!!.getInt("tapTolerance"))
            assertEquals("large", status.result!!.getString("keySize"))
        }
    }
}
