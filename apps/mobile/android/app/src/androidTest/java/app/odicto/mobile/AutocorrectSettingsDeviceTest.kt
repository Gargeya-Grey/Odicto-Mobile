package app.odicto.mobile

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives only the public autocorrect switch; never reads keys, history, prompts or field text. */
@RunWith(AndroidJUnit4::class)
class AutocorrectSettingsDeviceTest {
    @Test fun setAutocorrectThroughPublicSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = InstrumentationRegistry.getArguments().getString("autocorrectEnabled")
        require(requested == "true" || requested == "false")
        val enabled = requested.toBoolean()
        val context = instrumentation.targetContext
        VoicePreferences.initialize(context)
        withTimeout(5_000) { VoicePreferences.ready.first { it } }
        val before = VoicePreferences.state.value.keys.autocorrectEnabled
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("voice_settings", true)
        }) as MainActivity
        fun evaluate(script: String): String {
            val done = CountDownLatch(1)
            var result: String? = null
            instrumentation.runOnMainSync {
                activity.bridge.webView.evaluateJavascript(script) { result = it; done.countDown() }
            }
            assertTrue("settings UI responded", done.await(5, TimeUnit.SECONDS))
            return result.orEmpty()
        }
        val selector = "document.querySelector('button[aria-label=\"Autocorrect (English)\"]')"
        val deadline = System.nanoTime() + 15_000_000_000
        while (evaluate("(()=>{const b=$selector;return !!b&&!b.disabled})()") != "true" && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        assertEquals("public settings switch present", "true", evaluate("!!$selector"))
        assertEquals("settings shows persisted value", "\"$before\"", evaluate("$selector.getAttribute('aria-checked')"))
        assertEquals("true", evaluate("(()=>{const b=$selector;b.scrollIntoView({block:'center'});if(b.getAttribute('aria-checked')!=='$enabled')b.click();return true})()"))
        withTimeout(5_000) { VoicePreferences.state.first { it.keys.autocorrectEnabled == enabled } }
        val savedDeadline = System.nanoTime() + 5_000_000_000
        while (evaluate("(()=>{const b=$selector;return !b.disabled&&b.getAttribute('aria-checked')==='$enabled'})()") != "true" && System.nanoTime() < savedDeadline) {
            Thread.sleep(100)
        }
        assertEquals("\"$enabled\"", evaluate("$selector.getAttribute('aria-checked')"))
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("autocorrectBefore", before)
            putBoolean("autocorrectEnabled", enabled)
            putBoolean("publicSettingsSwitchVerified", true)
        })
    }
}
