package app.odicto.mobile.storage

import app.odicto.mobile.dictation.PromptMode
import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

const val DEFAULT_POLISH_MODEL = "poolside/laguna-xs-2.1"

/**
 * Keyboard feel settings.
 *
 * [hapticLevel] replaces the old boolean so the user can pick a strength, and [keySize] plus
 * [keyFont] let the grid match personal preference. [pinnedEmoji] is the user's favourites row.
 */
data class KeySettings(
    val hapticLevel: Int = 2,
    val keySize: String = "medium",
    val keyFont: String = "instrument",
    val pinnedEmoji: List<String> = emptyList(),
    val recentEmoji: List<String> = emptyList(),
    val pinnedClips: List<String> = emptyList(),
    val clipHistory: List<String> = emptyList(),
    val tapTolerance: Int = 2,
    val autocorrectEnabled: Boolean = false,
) {
    companion object {
        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"
        const val FONT_SYSTEM = "system"
        /** Instrument Sans, the brand face shared with the app screens. */
        const val FONT_BRAND = "instrument"
        /** Retired key faces. A stored "sansflex" or "lora" value is read as the brand face. */
        const val FONT_FLEX = "sansflex"
        const val FONT_LORA = "lora"
        val SIZES = listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE)
        val FONTS = listOf(FONT_SYSTEM, FONT_BRAND)

        /**
         * The key face to use for a stored value. Lora, then Google Sans Flex, were earlier faces;
         * both read as the brand face now. Only a deliberate, recorded "system" choice stays off it.
         */
        fun resolveFont(stored: String?, choiceRecorded: Boolean): String =
            if (stored == FONT_SYSTEM && choiceRecorded) FONT_SYSTEM else FONT_BRAND
    }
}

data class VoiceSettings(
    val enabled: Boolean = false,
    val mode: String = "raw",
    val provider: String = "gemini",
    val model: String = "",
    val haptics: Boolean = true,
    val x: Int = -1,
    val y: Int = -1,
    /**
     * Where the floating microphone sits as a share (0..1,000,000, so a pixel is never lost to rounding) of the room it can move in, so it keeps its
     * place across rotation, display-size changes and unfolding. -1 until the user places it; x and y are
     * the pixel position of the same placement and are only used when these are missing.
     */
    val anchorX: Int = -1,
    val anchorY: Int = -1,
    val liveModel: String = "gemini-3.5-transcribe-live",
    val systemPrompt: String = "",
    /** "append" adds [systemPrompt] after Odicto's rules; "replace" uses it instead. See [app.odicto.mobile.dictation.PromptMode]. */
    val systemPromptMode: String = "append",
    val polishModel: String = DEFAULT_POLISH_MODEL,
    val polishSystemPrompt: String = "",
    val polishSystemPromptMode: String = "append",
    val showTextPreview: Boolean = true,
    val paused: Boolean = false,
    val keys: KeySettings = KeySettings(),
)

object VoicePreferences {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(VoiceSettings())
    val state = mutable.asStateFlow()
    /** False until DataStore has emitted once, so callers never act on the default settings of a fresh process. */
    private val loaded = MutableStateFlow(false)
    val ready = loaded.asStateFlow()
    private var started = false
    /** Test seam for a stream that fails before it can emit. */
    internal var stream: (Context) -> Flow<Preferences> = { it.dataStore.data }

    /** Pins are capped so the favourites row always fits without scrolling. */
    const val MAX_PINNED = 5
    const val MAX_RECENT = 16
    const val MAX_CLIPS = 50
    const val HAPTIC_OFF = 0
    const val DEFAULT_LEVEL = 2

    /**
     * The strength the whole app uses. A store written before the levels existed only has the
     * boolean, so `false` means off and `true` means the old default rather than off.
     */
    fun levelOf(settings: VoiceSettings): Int {
        if (settings.keys.hapticLevel == DEFAULT_LEVEL && !settings.haptics) return HAPTIC_OFF
        return settings.keys.hapticLevel
    }

    fun toleranceOf(settings: VoiceSettings): Int = settings.keys.tapTolerance.coerceIn(0, 2)

    @Synchronized fun initialize(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // An unreadable store must still release ready; otherwise the overlay waits on it forever.
        scope.launch {
            stream(app)
                .catch { emit(emptyPreferences()) }
                .collect { preferences ->
                    val haptics = preferences[booleanPreferencesKey("voice_haptics")] ?: true
                    val savedPrompt = preferences[stringPreferencesKey("voice_system_prompt")] ?: ""
                    val savedPolishPrompt = preferences[stringPreferencesKey("voice_polish_system_prompt")]?.takeIf { it.length <= 8_000 } ?: ""
                    val fontChoiceRecorded = preferences[booleanPreferencesKey("voice_key_font_migrated")] ?: false
                    val rawFont = preferences[stringPreferencesKey("voice_key_font")]
                    val keyFont = KeySettings.resolveFont(rawFont, fontChoiceRecorded)
                    mutable.value = VoiceSettings(
                        enabled = preferences[booleanPreferencesKey("voice_enabled")] ?: false,
                        mode = preferences[stringPreferencesKey("voice_mode")] ?: "raw",
                        provider = preferences[stringPreferencesKey("voice_provider")]
                            ?.takeIf { it == "gemini" || it == "openrouter" } ?: "gemini",
                        model = preferences[stringPreferencesKey("voice_model")] ?: "",
                        haptics = haptics,
                        x = preferences[intPreferencesKey("voice_x")] ?: -1,
                        y = preferences[intPreferencesKey("voice_y")] ?: -1,
                        anchorX = readAnchor(preferences, "voice_anchor_fx", "voice_anchor_x"),
                        anchorY = readAnchor(preferences, "voice_anchor_fy", "voice_anchor_y"),
                        liveModel = preferences[stringPreferencesKey("voice_live_model")] ?: "gemini-3.5-transcribe-live",
                        systemPrompt = savedPrompt,
                        systemPromptMode = PromptMode.forStored(preferences[stringPreferencesKey("voice_system_prompt_mode")], savedPrompt).wire,
                        polishModel = preferences[stringPreferencesKey("voice_polish_model")]
                            ?.takeIf { it.isNotBlank() && it.length <= 200 } ?: DEFAULT_POLISH_MODEL,
                        polishSystemPrompt = savedPolishPrompt,
                        polishSystemPromptMode = PromptMode.forStored(preferences[stringPreferencesKey("voice_polish_system_prompt_mode")], savedPolishPrompt).wire,
                        showTextPreview = preferences[booleanPreferencesKey("voice_show_text_preview")] ?: true,
                        paused = preferences[booleanPreferencesKey("voice_paused")] ?: false,
                        keys = KeySettings(
                            // A store written before levels existed only has the boolean.
                            hapticLevel = (preferences[intPreferencesKey("voice_haptic_level")]
                                ?: if (haptics) DEFAULT_LEVEL else HAPTIC_OFF).coerceIn(HAPTIC_OFF, 3),
                            keySize = preferences[stringPreferencesKey("voice_key_size")]
                                ?.takeIf { it in KeySettings.SIZES } ?: KeySettings.SIZE_MEDIUM,
                            keyFont = keyFont,
                            tapTolerance = ((preferences.asMap().entries.firstOrNull { it.key.name == "voice_tap_tolerance" }?.value as? Int) ?: 2).coerceIn(0, 2),
                            autocorrectEnabled = preferences[booleanPreferencesKey("voice_autocorrect_enabled")] ?: false,
                            pinnedEmoji = readList(preferences, "voice_pinned_emoji", SEPARATOR, MAX_PINNED),
                            recentEmoji = readList(preferences, "voice_recent_emoji", SEPARATOR, MAX_RECENT),
                            pinnedClips = readList(preferences, "voice_pinned_clips", CLIP_SEPARATOR, MAX_PINNED),
                            clipHistory = readList(preferences, "voice_clip_history", CLIP_SEPARATOR, MAX_CLIPS),
                        ),
                    )
                    loaded.value = true
                }
        }
    }

    /** The fine share, or the older thousandths share scaled up, so an existing placement is kept. */
    private fun readAnchor(preferences: Preferences, fine: String, legacy: String): Int =
        preferences[intPreferencesKey(fine)]?.coerceIn(-1, 1_000_000)
            ?: (preferences[intPreferencesKey(legacy)] ?: -1).coerceIn(-1, 1000).let { if (it < 0) -1 else it * 1000 }

    suspend fun save(context: Context, value: VoiceSettings) {
        context.applicationContext.dataStore.edit { p ->
            p[booleanPreferencesKey("voice_enabled")] = value.enabled; p[stringPreferencesKey("voice_mode")] = value.mode
            p[stringPreferencesKey("voice_provider")] = value.provider; p[stringPreferencesKey("voice_model")] = value.model
            p[booleanPreferencesKey("voice_haptics")] = value.haptics; p[intPreferencesKey("voice_x")] = value.x; p[intPreferencesKey("voice_y")] = value.y
            p[intPreferencesKey("voice_anchor_fx")] = value.anchorX; p[intPreferencesKey("voice_anchor_fy")] = value.anchorY
            p[stringPreferencesKey("voice_live_model")] = value.liveModel
            p[stringPreferencesKey("voice_system_prompt")] = value.systemPrompt
            p[stringPreferencesKey("voice_system_prompt_mode")] = value.systemPromptMode
            p[stringPreferencesKey("voice_polish_model")] = value.polishModel
            p[stringPreferencesKey("voice_polish_system_prompt")] = value.polishSystemPrompt
            p[stringPreferencesKey("voice_polish_system_prompt_mode")] = value.polishSystemPromptMode
            p[booleanPreferencesKey("voice_show_text_preview")] = value.showTextPreview
            p[booleanPreferencesKey("voice_paused")] = value.paused
            p[intPreferencesKey("voice_haptic_level")] = value.keys.hapticLevel
            p[stringPreferencesKey("voice_key_size")] = value.keys.keySize
            p[stringPreferencesKey("voice_key_font")] = value.keys.keyFont
            p[intPreferencesKey("voice_tap_tolerance")] = toleranceOf(value)
            p[booleanPreferencesKey("voice_autocorrect_enabled")] = value.keys.autocorrectEnabled
            p[booleanPreferencesKey("voice_key_font_migrated")] = true
            p[stringPreferencesKey("voice_pinned_emoji")] = value.keys.pinnedEmoji.joinToString(SEPARATOR)
            p[stringPreferencesKey("voice_recent_emoji")] = value.keys.recentEmoji.joinToString(SEPARATOR)
        }
        mutable.value = normalized(value)
    }

    private fun normalized(value: VoiceSettings): VoiceSettings = value.copy(keys = value.keys.copy(tapTolerance = toleranceOf(value)))

    fun update(context: Context, transform: (VoiceSettings) -> VoiceSettings) {
        val value = normalized(transform(mutable.value)); mutable.value = value
        scope.launch { save(context, value) }
    }

    internal fun readList(preferences: Preferences, name: String, separator: String, limit: Int): List<String> {
        val value = preferences.asMap().entries.firstOrNull { it.key.name == name }?.value
        val items = when (value) {
            is String -> value.split(separator)
            is Set<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
        return items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(limit)
    }

    /** Toggles a pin, keeping the row at five and never duplicating an entry. */
    fun withPinned(emoji: String, keys: KeySettings): KeySettings {
        if (emoji.isBlank()) return keys
        val current = keys.pinnedEmoji
        val next = if (current.contains(emoji)) current - emoji else (current + emoji).takeLast(MAX_PINNED)
        return keys.copy(pinnedEmoji = next)
    }

    /** Newest first, capped at two rows. A repeat use moves that emoji back to the front. */
    fun rememberEmoji(emoji: String, keys: KeySettings): KeySettings {
        if (emoji.isBlank()) return keys
        val next = listOf(emoji) + keys.recentEmoji.filter { it != emoji }
        return keys.copy(recentEmoji = next.take(MAX_RECENT))
    }

    /** Pinning lifts a clip to the top and out of the deletable list. Unpinning puts it back. */
    fun withPinnedClip(text: String, keys: KeySettings): KeySettings {
        if (text.isEmpty()) return keys
        return if (text in keys.pinnedClips) {
            keys.copy(
                pinnedClips = keys.pinnedClips - text,
                clipHistory = (listOf(text) + keys.clipHistory.filter { it != text }).take(MAX_CLIPS),
            )
        } else {
            keys.copy(
                pinnedClips = listOf(text) + keys.pinnedClips.filter { it != text },
                clipHistory = keys.clipHistory.filter { it != text },
            )
        }
    }

    private const val SEPARATOR = ","
    private const val CLIP_SEPARATOR = "\u001F"
}
