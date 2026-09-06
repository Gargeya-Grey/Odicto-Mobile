package app.odicto.mobile

import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.odicto.mobile.dictation.VoiceUi
import app.odicto.mobile.overlay.VoiceControlView
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI states only: no microphone, provider calls, or history writes. */
@RunWith(AndroidJUnit4::class)
class VoiceControlViewDeviceTest {
    @Test fun previewToggleAvoidsHiddenTextWorkAndKeepsStatusCompact() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = booleanPreferencesKey("voice_show_text_preview")
        val original = context.dataStore.data.first()[key]
        VoicePreferences.initialize(context)
        suspend fun previewEnabled(enabled: Boolean) {
            context.dataStore.edit { it[key] = enabled }
            withTimeout(5000) { VoicePreferences.state.first { it.showTextPreview == enabled } }
        }
        val recording = VoiceUi(phase = "recording", mode = "raw", finalText = "Synthetic words. ".repeat(1000), message = "Listening")
        lateinit var view: VoiceControlView
        lateinit var preview: TextView
        var textWrites = 0
        var resizes = 0
        try {
            previewEnabled(false)
            instrumentation.runOnMainSync {
                view = VoiceControlView(context, true)
                preview = (view.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
                preview.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { textWrites++ }
                    override fun afterTextChanged(s: Editable?) {}
                })
                view.resize = { _, _ -> resizes++ }
                repeat(1000) { view.render(recording.copy(interim = "Update $it", level = (it % 10) / 10f)) }
                assertEquals("Hidden preview must receive no text updates", 0, textWrites)
                assertEquals("Steady recording must not resize", 0, resizes)
                for (phase in listOf("connecting", "processing", "done")) {
                    view.render(recording.copy(phase = phase, message = "Inserted"))
                    assertEquals("Status-only controls stay one row tall", view.dp(72), view.requiredHeight)
                }
            }
            previewEnabled(true)
            instrumentation.runOnMainSync {
                view.render(recording)
                assertTrue("Enabled preview displays text", preview.text.isNotEmpty())
                assertTrue(view.requiredHeight > view.dp(72))
                textWrites = 0; resizes = 0
                repeat(1000) { view.render(recording.copy(level = (it % 10) / 10f)) }
                assertEquals("Meter-only ticks must not rewrite the transcript", 0, textWrites)
                assertEquals(0, resizes)
            }
            previewEnabled(false)
            instrumentation.runOnMainSync {
                view.render(recording)
                assertEquals(view.dp(72), view.requiredHeight)
                assertEquals("", preview.text.toString())
                view.render(VoiceUi(phase = "error", message = "Connection unavailable. Please try again."))
                assertTrue("Longer errors retain readable space", view.requiredHeight > view.dp(72))
            }
        } finally {
            context.dataStore.edit { if (original == null) it.remove(key) else it[key] = original }
            withTimeout(5000) { VoicePreferences.state.first { it.showTextPreview == (original ?: true) } }
        }
    }
}
