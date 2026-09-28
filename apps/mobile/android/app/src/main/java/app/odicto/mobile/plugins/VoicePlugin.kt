package app.odicto.mobile.plugins

import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.ime.FieldPolishClient
import app.odicto.mobile.ime.Haptics
import app.odicto.mobile.overlay.VoiceOverlayController
import app.odicto.mobile.storage.*
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@CapacitorPlugin(name = "OdictoVoice")
class VoicePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val openRouterClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    override fun load() {
        VoicePreferences.initialize(context)
        VoiceOverlayController.sync(context, scope)
    }
    @PluginMethod fun feedback(call: PluginCall) {
        val host = activity ?: return call.reject("Odicto is not in the foreground")
        host.runOnUiThread { Haptics.tap(context, VoicePreferences.levelOf(VoicePreferences.state.value)); call.resolve() }
    }
    @PluginMethod fun status(call: PluginCall) {
        scope.launch {
            try {
                VoicePreferences.ready.first { it }
                val settings = VoicePreferences.state.value
                val keys = withContext(Dispatchers.IO) { val store = SecureCredentialStore(context); Triple(!store.get("voice-gemini").isNullOrEmpty(), !store.get("voice-openrouter").isNullOrEmpty(), !store.get("voice-groq").isNullOrEmpty()) }
                val show = activity?.intent?.getBooleanExtra("voice_settings", false) == true; activity?.intent?.removeExtra("voice_settings")
                val model = settings.model.ifBlank { if (settings.provider == "openrouter") "poolside/laguna-xs-2.1:free" else "" }
                call.resolve(JSObject().put("enabled", settings.enabled).put("mode", settings.mode).put("provider", settings.provider).put("model", model).put("liveModel", settings.liveModel).put("systemPrompt", settings.systemPrompt).put("polishModel", settings.polishModel).put("polishSystemPrompt", settings.polishSystemPrompt).put("haptics", settings.haptics).put("hapticLevel", VoicePreferences.levelOf(settings)).put("keySize", settings.keys.keySize).put("keyFont", settings.keys.keyFont).put("pinnedEmoji", JSArray().apply { settings.keys.pinnedEmoji.forEach { put(it) } }).put("showTextPreview", settings.showTextPreview).put("paused", settings.paused).put("geminiKeySet", keys.first).put("openrouterKeySet", keys.second).put("groqKeySet", keys.third).put("openSettings", show))
            } catch (_: Exception) { call.reject("Could not read voice settings") }
        }
    }
    @PluginMethod fun openrouterModels(call: PluginCall) { scope.launch {
        try {
            val key = withContext(Dispatchers.IO) { SecureCredentialStore(context).get("voice-openrouter").orEmpty() }
            if (key.isBlank()) { call.reject("Save an OpenRouter key first"); return@launch }
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/models?output_modalities=text&input_modalities=text&sort=latency-low-to-high")
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .get().build()
            val result = withContext(Dispatchers.IO) { openRouterClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("OpenRouter models request failed (${response.code})")
                JSONObject(response.body?.string() ?: throw IllegalStateException("OpenRouter returned no models"))
            } }
            val models = JSArray()
            val data = result.optJSONArray("data") ?: org.json.JSONArray()
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val pricing = model.optJSONObject("pricing")
                val reasoning = model.optJSONObject("reasoning")
                models.put(JSObject()
                    .put("id", model.optString("id"))
                    .put("name", model.optString("name", model.optString("id")))
                    .put("free", pricing?.optString("prompt") == "0" && pricing.optString("completion") == "0")
                    .put("contextLength", model.optInt("context_length", 0))
                    .put("reasoning", reasoning != null))
            }
            call.resolve(JSObject().put("models", models))
        } catch (error: Exception) { call.reject(error.message ?: "Could not load OpenRouter models") }
    } }
    @PluginMethod fun save(call: PluginCall) {
        if (VoiceSession.state.value.busy) { call.reject("Finish recording before changing settings"); return }
        val prompt = call.getString("systemPrompt") ?: VoicePreferences.state.value.systemPrompt
        if (prompt.length > app.odicto.mobile.dictation.AiSelection.MAX_PROMPT) { call.reject("System prompt must be at most 8,000 characters"); return }
        val polishPrompt = call.getString("polishSystemPrompt") ?: VoicePreferences.state.value.polishSystemPrompt
        if (polishPrompt.length > FieldPolishClient.MAX_PROMPT_LENGTH) { call.reject("Text polish prompt must be at most 8,000 characters"); return }
        val polishModel = (call.getString("polishModel") ?: VoicePreferences.state.value.polishModel).trim()
        if (polishModel.isBlank() || polishModel.length > 200) { call.reject("Choose a valid text polish model ID"); return }
        val mode = call.getString("mode") ?: VoicePreferences.state.value.mode
        val provider = call.getString("provider") ?: VoicePreferences.state.value.provider
        if (mode !in listOf("raw", "ai", "live") || provider !in listOf("gemini", "openrouter")) { call.reject("Unsupported voice setting"); return }
        val currentKeys = VoicePreferences.state.value.keys
        val hapticLevel = (call.getInt("hapticLevel") ?: currentKeys.hapticLevel).coerceIn(0, 3)
        val keySize = (call.getString("keySize") ?: currentKeys.keySize).takeIf { it in KeySettings.SIZES } ?: currentKeys.keySize
        val keyFont = (call.getString("keyFont") ?: currentKeys.keyFont).takeIf { it in KeySettings.FONTS } ?: currentKeys.keyFont
        val pinned = call.getArray("pinnedEmoji")?.toList<String>()
            ?: currentKeys.pinnedEmoji
        val nextKeys = currentKeys.copy(
            hapticLevel = hapticLevel,
            keySize = keySize,
            keyFont = keyFont,
            pinnedEmoji = pinned.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(VoicePreferences.MAX_PINNED),
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val keys = SecureCredentialStore(context)
                    for (name in listOf("groq", "gemini", "openrouter")) {
                        val key = call.getString("${name}Key")
                        if (key != null) { if (key.isBlank()) keys.remove("voice-$name") else keys.put("voice-$name", key.trim()) }
                    }
                    val requestedModel = call.getString("model")?.trim()
                    val model = if (provider == "openrouter") requestedModel?.ifBlank { "poolside/laguna-xs-2.1:free" } ?: "poolside/laguna-xs-2.1:free" else requestedModel ?: VoicePreferences.state.value.model
                    VoicePreferences.save(context, VoicePreferences.state.value.copy(systemPrompt = prompt, polishModel = polishModel, polishSystemPrompt = polishPrompt, enabled = call.getBoolean("enabled") ?: VoicePreferences.state.value.enabled, paused = call.getBoolean("paused") ?: VoicePreferences.state.value.paused, keys = nextKeys, mode = mode, provider = provider, model = model, liveModel = call.getString("liveModel")?.trim()?.ifBlank { "gemini-3.5-transcribe-live" } ?: VoicePreferences.state.value.liveModel, haptics = call.getBoolean("haptics") ?: VoicePreferences.state.value.haptics, showTextPreview = call.getBoolean("showTextPreview") ?: VoicePreferences.state.value.showTextPreview))
                }
                VoiceOverlayController.sync(context, scope)
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
