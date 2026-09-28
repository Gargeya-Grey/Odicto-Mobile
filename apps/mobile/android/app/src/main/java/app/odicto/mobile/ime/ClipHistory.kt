package app.odicto.mobile.ime

import app.odicto.mobile.storage.KeySettings
import app.odicto.mobile.storage.VoicePreferences

internal object ClipHistory {
    fun absorb(keys: KeySettings, incoming: List<String>, maxHistory: Int = VoicePreferences.MAX_CLIPS): KeySettings {
        var pins = keys.pinnedClips.distinct()
        var history = keys.clipHistory.filter { it !in pins }.distinct()
        for (text in incoming.asReversed()) {
            if (text.isEmpty()) continue
            if (text in pins) pins = listOf(text) + pins.filter { it != text }
            else history = listOf(text) + history.filter { it != text }
        }
        return keys.copy(pinnedClips = pins, clipHistory = history.take(maxHistory))
    }

    fun delete(keys: KeySettings, selected: Set<String>): KeySettings =
        keys.copy(clipHistory = keys.clipHistory.filter { it !in selected && it !in keys.pinnedClips })

    fun selectable(keys: KeySettings): List<String> = keys.clipHistory.filter { it !in keys.pinnedClips }
}
