package app.odicto.mobile.plugins

import android.content.Intent
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.overlay.VoiceOverlayService
import app.odicto.mobile.storage.*
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.*

@CapacitorPlugin(name = "OdictoVoice")
class VoicePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun load() {
        VoicePreferences.initialize(context)
        activity.runOnUiThread { try { context.startService(Intent(context, VoiceOverlayService::class.java).setAction(VoiceOverlayService.ACTION_SHOW)) } catch (_: Exception) {} }
    }
    @PluginMethod fun feedback(call: PluginCall) {
        activity.runOnUiThread { if (VoicePreferences.state.value.haptics) activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); call.resolve() }
    }
    @PluginMethod fun status(call: PluginCall) {
        scope.launch {
            try {
                val settings = VoicePreferences.state.value
                val keys = withContext(Dispatchers.IO) { val store = SecureCredentialStore(context); Triple(!store.get("voice-gemini").isNullOrEmpty(), !store.get("voice-openrouter").isNullOrEmpty(), !store.get("voice-groq").isNullOrEmpty()) }
                val show = activity.intent.getBooleanExtra("voice_settings", false); activity.intent.removeExtra("voice_settings")
                call.resolve(JSObject().put("enabled", settings.enabled).put("mode", settings.mode).put("provider", settings.provider).put("model", settings.model).put("liveModel", settings.liveModel).put("systemPrompt", settings.systemPrompt).put("haptics", settings.haptics).put("showTextPreview", settings.showTextPreview).put("geminiKeySet", keys.first).put("openrouterKeySet", keys.second).put("groqKeySet", keys.third).put("openSettings", show))
            } catch (_: Exception) { call.reject("Could not read voice settings") }
        }
    }
    @PluginMethod fun save(call: PluginCall) {
        if (VoiceSession.state.value.busy) { call.reject("Finish recording before changing settings"); return }
        val prompt = call.getString("systemPrompt") ?: VoicePreferences.state.value.systemPrompt
        if (prompt.length > app.odicto.mobile.dictation.AiSelection.MAX_PROMPT) { call.reject("System prompt must be at most 8,000 characters"); return }
        val mode = call.getString("mode") ?: VoicePreferences.state.value.mode
        val provider = call.getString("provider") ?: VoicePreferences.state.value.provider
        if (mode !in listOf("raw", "ai", "live") || provider !in listOf("gemini", "openrouter")) { call.reject("Unsupported voice setting"); return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val keys = SecureCredentialStore(context)
                    for (name in listOf("groq", "gemini", "openrouter")) {
                        val key = call.getString("${name}Key")
                        if (key != null) { if (key.isBlank()) keys.remove("voice-$name") else keys.put("voice-$name", key.trim()) }
                    }
                    VoicePreferences.save(context, VoicePreferences.state.value.copy(systemPrompt = prompt, enabled = call.getBoolean("enabled") ?: VoicePreferences.state.value.enabled, mode = mode, provider = provider, model = call.getString("model")?.trim() ?: VoicePreferences.state.value.model, liveModel = call.getString("liveModel")?.trim()?.ifBlank { "gemini-3.5-transcribe-live" } ?: VoicePreferences.state.value.liveModel, haptics = call.getBoolean("haptics") ?: VoicePreferences.state.value.haptics, showTextPreview = call.getBoolean("showTextPreview") ?: VoicePreferences.state.value.showTextPreview))
                }
                context.startService(Intent(context, VoiceOverlayService::class.java).setAction(VoiceOverlayService.ACTION_SHOW))
                call.resolve()
            } catch (_: Exception) { call.reject("Could not save voice settings") }
        }
    }
    @PluginMethod fun history(call: PluginCall) { scope.launch {
        try {
            val rows = withContext(Dispatchers.IO) { HistoryDatabase.open(context).history().recent() }
            val list = JSArray(); rows.forEach { list.put(JSObject().put("id", it.id).put("text", it.text).put("outcome", it.outcome).put("createdAt", it.createdAt)) }
            call.resolve(JSObject().put("entries", list))
        } catch (_: Exception) { call.reject("Could not read local history") }
    } }
    override fun handleOnDestroy() { scope.cancel(); super.handleOnDestroy() }
}
