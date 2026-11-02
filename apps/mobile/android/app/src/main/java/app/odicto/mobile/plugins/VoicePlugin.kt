package app.odicto.mobile.plugins

import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.ime.FieldPolishClient
import app.odicto.mobile.ime.Haptics
import app.odicto.mobile.network.HttpClients
import app.odicto.mobile.network.GeminiCatalogException
import app.odicto.mobile.network.GeminiModelCatalog
import app.odicto.mobile.overlay.VoiceOverlayController
import app.odicto.mobile.storage.*
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@CapacitorPlugin(name = "OdictoVoice")
class VoicePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val openRouterClient by lazy { HttpClients.shared.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
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
                val keys = withContext(Dispatchers.IO) { val store = SecureCredentialStore(context); listOf("gemini", "openrouter", "groq", "openai").map { !store.get("voice-$it").isNullOrEmpty() } }
                val show = activity?.intent?.getBooleanExtra("voice_settings", false) == true; activity?.intent?.removeExtra("voice_settings")
                val model = settings.model.ifBlank { if (settings.provider == "openrouter") "poolside/laguna-xs-2.1:free" else "" }
                call.resolve(JSObject().put("enabled", settings.enabled).put("mode", settings.mode).put("provider", settings.provider).put("model", model).put("liveModel", settings.liveModel).put("liveProvider", settings.liveProvider).put("openaiLiveModel", settings.openaiLiveModel).put("spokenLanguage", settings.spokenLanguage).put("vocabularyHints", JSArray(settings.vocabularyHints)).put("systemPrompt", settings.systemPrompt).put("systemPromptMode", settings.systemPromptMode).put("polishProvider", settings.polishProvider).put("polishModel", settings.polishModel).put("polishSystemPrompt", settings.polishSystemPrompt).put("polishSystemPromptMode", settings.polishSystemPromptMode).put("haptics", settings.haptics).put("hapticLevel", VoicePreferences.levelOf(settings)).put("keySize", settings.keys.keySize).put("keyFont", settings.keys.keyFont).put("tapTolerance", VoicePreferences.toleranceOf(settings)).put("autocorrectEnabled", settings.keys.autocorrectEnabled).put("pinnedEmoji", JSArray().apply { settings.keys.pinnedEmoji.forEach { put(it) } }).put("showTextPreview", settings.showTextPreview).put("paused", settings.paused).put("geminiKeySet", keys[0]).put("openrouterKeySet", keys[1]).put("groqKeySet", keys[2]).put("openaiKeySet", keys[3]).put("openSettings", show))
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
    @PluginMethod fun geminiModels(call: PluginCall) { scope.launch {
        try {
            val models = withContext(Dispatchers.IO) {
                val key = SecureCredentialStore(context).get("voice-gemini").orEmpty()
                GeminiModelCatalog().fetch(key)
            }
            call.resolve(JSObject().put("models", JSArray().apply {
                models.forEach { put(JSObject().put("id", it.id).put("name", it.name)) }
            }))
        } catch (failure: GeminiCatalogException) {
            call.reject(failure.message ?: "Could not load Gemini models")
        } catch (_: Exception) {
            call.reject("Could not load Gemini models. Try again.")
        }
    } }
    @PluginMethod fun save(call: PluginCall) {
        if (VoiceSession.state.value.busy) { call.reject("Finish recording before changing settings"); return }
        val suppliedLiveProvider = call.data.opt("liveProvider")
        if (call.data.has("liveProvider") && (suppliedLiveProvider !is String || suppliedLiveProvider !in listOf("gemini", "openai"))) {
            call.reject("Choose Gemini or OpenAI for Live"); return
        }
        val liveProvider = suppliedLiveProvider as? String ?: VoicePreferences.state.value.liveProvider
        val suppliedOpenaiModel = call.data.opt("openaiLiveModel")
        if (call.data.has("openaiLiveModel") && (suppliedOpenaiModel !is String || suppliedOpenaiModel !in LiveProvider.openaiModels)) {
            call.reject("Choose a supported OpenAI Live model"); return
        }
        val openaiLiveModel = suppliedOpenaiModel as? String ?: VoicePreferences.state.value.openaiLiveModel
        val suppliedLanguage = call.data.opt("spokenLanguage")
        if (call.data.has("spokenLanguage") && (suppliedLanguage !is String || !SpokenLanguage.isSupported(suppliedLanguage))) {
            call.reject("Choose a supported spoken language or Automatic"); return
        }
        val spokenLanguage = suppliedLanguage as? String ?: VoicePreferences.state.value.spokenLanguage
        val vocabularyHints = if (call.data.has("vocabularyHints")) {
            VocabularyHints.fromJson(call.data.opt("vocabularyHints")) ?: run {
                call.reject("Keep vocabulary hints short and use text terms only"); return
            }
        } else VoicePreferences.state.value.vocabularyHints
        val prompt = call.getString("systemPrompt") ?: VoicePreferences.state.value.systemPrompt
        if (prompt.length > app.odicto.mobile.dictation.AiSelection.MAX_PROMPT) { call.reject("System prompt must be at most 8,000 characters"); return }
        val polishPrompt = call.getString("polishSystemPrompt") ?: VoicePreferences.state.value.polishSystemPrompt
        if (polishPrompt.length > FieldPolishClient.MAX_PROMPT_LENGTH) { call.reject("Text polish prompt must be at most 8,000 characters"); return }
        val promptMode = call.getString("systemPromptMode")?.let { app.odicto.mobile.dictation.PromptMode.parseOrNull(it)?.wire ?: run { call.reject("Unsupported prompt mode"); return } } ?: VoicePreferences.state.value.systemPromptMode
        val polishPromptMode = call.getString("polishSystemPromptMode")?.let { app.odicto.mobile.dictation.PromptMode.parseOrNull(it)?.wire ?: run { call.reject("Unsupported prompt mode"); return } } ?: VoicePreferences.state.value.polishSystemPromptMode
        val polishProvider = call.getString("polishProvider") ?: VoicePreferences.state.value.polishProvider
        if (polishProvider !in listOf("gemini", "openrouter")) { call.reject("Unsupported text polish provider"); return }
        val polishModel = (call.getString("polishModel") ?: if (polishProvider != VoicePreferences.state.value.polishProvider) {
            if (polishProvider == "gemini") app.odicto.mobile.storage.DEFAULT_GEMINI_POLISH_MODEL else app.odicto.mobile.storage.DEFAULT_POLISH_MODEL
        } else VoicePreferences.state.value.polishModel).trim()
        if (polishModel.isBlank() || polishModel.length > 200) { call.reject("Choose a valid text polish model ID"); return }
        val mode = call.getString("mode") ?: VoicePreferences.state.value.mode
        val provider = call.getString("provider") ?: VoicePreferences.state.value.provider
        if (mode !in listOf("raw", "ai", "live") || provider !in listOf("gemini", "openrouter")) { call.reject("Unsupported voice setting"); return }
        val currentKeys = VoicePreferences.state.value.keys
        val hapticLevel = (call.getInt("hapticLevel") ?: currentKeys.hapticLevel).coerceIn(0, 3)
        val keySize = (call.getString("keySize") ?: currentKeys.keySize).takeIf { it in KeySettings.SIZES } ?: currentKeys.keySize
        val keyFont = (call.getString("keyFont") ?: currentKeys.keyFont).takeIf { it in KeySettings.FONTS } ?: currentKeys.keyFont
        val suppliedTolerance = (call.data.opt("tapTolerance") as? Number)?.toDouble()
        val tapTolerance = suppliedTolerance?.takeIf { it.isFinite() && it % 1.0 == 0.0 }
            ?.coerceIn(0.0, 2.0)?.toInt() ?: currentKeys.tapTolerance.coerceIn(0, 2)
        val pinned = call.getArray("pinnedEmoji")?.toList<String>()
            ?: currentKeys.pinnedEmoji
        val nextKeys = currentKeys.copy(
            hapticLevel = hapticLevel,
            keySize = keySize,
            keyFont = keyFont,
            tapTolerance = tapTolerance,
            autocorrectEnabled = call.data.opt("autocorrectEnabled") as? Boolean ?: currentKeys.autocorrectEnabled,
            pinnedEmoji = pinned.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(VoicePreferences.MAX_PINNED),
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val keys = SecureCredentialStore(context)
                    for (name in listOf("groq", "gemini", "openrouter", "openai")) {
                        val key = call.getString("${name}Key")
                        if (key != null) { if (key.isBlank()) keys.remove("voice-$name") else keys.put("voice-$name", key.trim()) }
                    }
                    val requestedModel = call.getString("model")?.trim()
                    val model = when {
                        requestedModel != null -> if (provider == "openrouter") requestedModel.ifBlank { "poolside/laguna-xs-2.1:free" } else requestedModel
                        provider == "openrouter" && provider != VoicePreferences.state.value.provider -> "poolside/laguna-xs-2.1:free"
                        else -> VoicePreferences.state.value.model
                    }
                    VoicePreferences.save(context, VoicePreferences.state.value.copy(liveProvider = liveProvider, openaiLiveModel = openaiLiveModel, vocabularyHints = vocabularyHints, spokenLanguage = spokenLanguage, systemPrompt = prompt, systemPromptMode = promptMode, polishProvider = polishProvider, polishModel = polishModel, polishSystemPrompt = polishPrompt, polishSystemPromptMode = polishPromptMode, enabled = call.getBoolean("enabled") ?: VoicePreferences.state.value.enabled, paused = call.getBoolean("paused") ?: VoicePreferences.state.value.paused, keys = nextKeys, mode = mode, provider = provider, model = model, liveModel = call.getString("liveModel")?.trim()?.ifBlank { "gemini-3.5-transcribe-live" } ?: VoicePreferences.state.value.liveModel, haptics = call.getBoolean("haptics") ?: VoicePreferences.state.value.haptics, showTextPreview = call.getBoolean("showTextPreview") ?: VoicePreferences.state.value.showTextPreview))
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
