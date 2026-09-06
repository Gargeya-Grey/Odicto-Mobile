package app.odicto.mobile.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VoiceSettings(val enabled: Boolean = false, val mode: String = "raw", val provider: String = "gemini", val model: String = "", val haptics: Boolean = true, val x: Int = -1, val y: Int = -1, val liveModel: String = "gemini-3.5-transcribe-live", val systemPrompt: String = "", val showTextPreview: Boolean = true)

object VoicePreferences {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(VoiceSettings())
    val state = mutable.asStateFlow()
    private var started = false
    @Synchronized fun initialize(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch { app.dataStore.data.collect { p -> mutable.value = VoiceSettings(
            p[booleanPreferencesKey("voice_enabled")] ?: false,
            p[stringPreferencesKey("voice_mode")] ?: "raw",
            p[stringPreferencesKey("voice_provider")]?.takeIf { it == "gemini" || it == "openrouter" } ?: "gemini",
            p[stringPreferencesKey("voice_model")] ?: "",
            p[booleanPreferencesKey("voice_haptics")] ?: true,
            p[intPreferencesKey("voice_x")] ?: -1, p[intPreferencesKey("voice_y")] ?: -1,
            p[stringPreferencesKey("voice_live_model")] ?: "gemini-3.5-transcribe-live",
            p[stringPreferencesKey("voice_system_prompt")] ?: "",
            p[booleanPreferencesKey("voice_show_text_preview")] ?: true
        ) } }
    }
    suspend fun save(context: Context, value: VoiceSettings) {
        context.applicationContext.dataStore.edit { p ->
            p[booleanPreferencesKey("voice_enabled")] = value.enabled; p[stringPreferencesKey("voice_mode")] = value.mode
            p[stringPreferencesKey("voice_provider")] = value.provider; p[stringPreferencesKey("voice_model")] = value.model
            p[booleanPreferencesKey("voice_haptics")] = value.haptics; p[intPreferencesKey("voice_x")] = value.x; p[intPreferencesKey("voice_y")] = value.y
            p[stringPreferencesKey("voice_live_model")] = value.liveModel
            p[stringPreferencesKey("voice_system_prompt")] = value.systemPrompt
            p[booleanPreferencesKey("voice_show_text_preview")] = value.showTextPreview
        }
        mutable.value = value
    }
    fun update(context: Context, transform: (VoiceSettings) -> VoiceSettings) {
        val value = transform(mutable.value); mutable.value = value
        scope.launch { save(context, value) }
    }
}
