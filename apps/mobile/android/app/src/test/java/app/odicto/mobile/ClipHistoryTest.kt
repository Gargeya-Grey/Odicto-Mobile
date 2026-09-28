package app.odicto.mobile

import app.odicto.mobile.ime.ClipHistory
import app.odicto.mobile.storage.KeySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipHistoryTest {
    @Test fun exactLongDistinctTextSurvivesAndOnlyTheOldestOf51IsEvicted() {
        val text = " \n" + "x".repeat(700) + "\u001F👩🏽‍💻\n "
        var keys = ClipHistory.absorb(KeySettings(), listOf(text, text + "different"))
        assertEquals(listOf(text, text + "different"), keys.clipHistory)
        repeat(49) { keys = ClipHistory.absorb(keys, listOf("copy-$it")) }
        assertEquals(50, keys.clipHistory.size)
        assertTrue(text in keys.clipHistory)
        assertFalse(text + "different" in keys.clipHistory)
    }

    @Test fun moreThanFivePinsSurviveAndUnpinReturnsToBoundedHistory() {
        var keys = KeySettings(clipHistory = (1..50).map { "old-$it" })
        repeat(8) { keys = app.odicto.mobile.storage.VoicePreferences.withPinnedClip(" pin-$it ", keys) }
        assertEquals(8, keys.pinnedClips.size)
        keys = app.odicto.mobile.storage.VoicePreferences.withPinnedClip(" pin-0 ", keys)
        assertEquals(7, keys.pinnedClips.size)
        assertEquals(" pin-0 ", keys.clipHistory.first())
        assertEquals(50, keys.clipHistory.size)
    }

    @Test fun aNewCopyIsAddedInFrontOfThePreviousOne() {
        val first = ClipHistory.absorb(KeySettings(), listOf("alpha"))
        val second = ClipHistory.absorb(first, listOf("beta"))
        assertEquals(listOf("beta", "alpha"), second.clipHistory)
    }

    @Test fun copyingTheSameTextAgainMovesItToTheFront() {
        val keys = KeySettings(clipHistory = listOf("beta", "alpha"))
        val next = ClipHistory.absorb(keys, listOf("alpha"))
        assertEquals(listOf("alpha", "beta"), next.clipHistory)
    }

    @Test fun aPinnedClipIsNotDuplicatedInTheHistory() {
        val keys = KeySettings(pinnedClips = listOf("keep"))
        val next = ClipHistory.absorb(keys, listOf("keep", "fresh"))
        assertEquals(listOf("keep"), next.pinnedClips)
        assertEquals(listOf("fresh"), next.clipHistory)
    }

    @Test fun deleteRemovesOnlyTheSelectedUnpinnedClips() {
        val keys = KeySettings(pinnedClips = listOf("keep"), clipHistory = listOf("one", "two", "three"))
        val next = ClipHistory.delete(keys, setOf("one", "three", "keep"))
        assertEquals(listOf("keep"), next.pinnedClips)
        assertEquals(listOf("two"), next.clipHistory)
    }

    @Test fun selectAllIsTheUnpinnedList() {
        val keys = KeySettings(pinnedClips = listOf("keep"), clipHistory = listOf("one", "two"))
        assertEquals(listOf("one", "two"), ClipHistory.selectable(keys))
        assertFalse(ClipHistory.selectable(keys).contains("keep"))
        assertTrue(ClipHistory.delete(keys, ClipHistory.selectable(keys).toSet()).clipHistory.isEmpty())
        assertEquals(listOf("keep"), ClipHistory.delete(keys, ClipHistory.selectable(keys).toSet()).pinnedClips)
    }
}
