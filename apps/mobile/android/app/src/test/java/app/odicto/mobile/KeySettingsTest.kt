package app.odicto.mobile

import app.odicto.mobile.ime.Haptics
import app.odicto.mobile.storage.KeySettings
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.storage.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyboard feel settings: strength, size, font, and the pinned emoji row. */
class KeySettingsTest {
    @Test fun levelsAreClampedAndMapToDistinctStrengths() {
        assertEquals(Haptics.OFF, Haptics.clamp(-4))
        assertEquals(Haptics.STRONG, Haptics.clamp(9))
        // The three strengths must differ in amplitude, or the slider is a lie.
        val amplitudes = listOf(Haptics.LIGHT, Haptics.MEDIUM, Haptics.STRONG).map { Haptics.strengthFor(it).second }
        assertEquals("each strength must feel different", amplitudes.size, amplitudes.toSet().size)
        // And they must get stronger, not just differ.
        assertTrue("stronger must vibrate harder", amplitudes[0] < amplitudes[1] && amplitudes[1] < amplitudes[2])
    }

    @Test fun aLegacyStoreWithoutALevelStillRespectsTheBoolean() {
        // A user who had turned haptics off must not suddenly get them back after the update.
        val off = VoiceSettings(haptics = false, keys = KeySettings(hapticLevel = VoicePreferences.DEFAULT_LEVEL))
        assertEquals(VoicePreferences.HAPTIC_OFF, VoicePreferences.levelOf(off))
        val on = VoiceSettings(haptics = true, keys = KeySettings(hapticLevel = VoicePreferences.DEFAULT_LEVEL))
        assertEquals(VoicePreferences.DEFAULT_LEVEL, VoicePreferences.levelOf(on))
    }

    @Test fun anExplicitOffSurvivesTheLegacyBoolean() {
        val chosen = VoiceSettings(haptics = true, keys = KeySettings(hapticLevel = Haptics.OFF))
        assertEquals(Haptics.OFF, VoicePreferences.levelOf(chosen))
    }

    @Test fun pinnedEmojiAreCappedDeduplicatedAndToggleable() {
        val base = KeySettings()
        var keys = base
        for (entry in listOf("a", "b", "c", "d", "e", "f")) {
            keys = VoicePreferences.withPinned(entry, keys)
        }
        assertEquals("the row must not grow past five", VoicePreferences.MAX_PINNED, keys.pinnedEmoji.size)
        assertEquals("pins must stay unique", keys.pinnedEmoji.size, keys.pinnedEmoji.toSet().size)

        val before = keys.pinnedEmoji
        val removed = VoicePreferences.withPinned(before.first(), keys)
        assertFalse(removed.pinnedEmoji.contains(before.first()))
        // Toggling something already pinned removes it rather than duplicating.
        val readded = VoicePreferences.withPinned(before.first(), removed)
        assertTrue(readded.pinnedEmoji.contains(before.first()))
    }

    @Test fun aBlankPinIsIgnored() {
        val keys = KeySettings()
        assertEquals(keys.pinnedEmoji, VoicePreferences.withPinned("  ", keys).pinnedEmoji)
    }
}
