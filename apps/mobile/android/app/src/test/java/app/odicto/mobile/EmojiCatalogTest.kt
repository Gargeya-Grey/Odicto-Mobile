package app.odicto.mobile

import app.odicto.mobile.ime.EmojiCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    @Test fun everydayTopicsHaveFullPagesAndRoomToBrowse() {
        val labels = EmojiCatalog.categories.map { it.label }
        assertTrue(labels.containsAll(listOf("Smileys", "Gestures", "Hearts", "Nature", "Food", "Travel", "Activities", "Objects", "Symbols")))
        assertEquals("category buttons need distinct labels", labels.size, labels.toSet().size)
        assertEquals("show four rows of eight choices", 32, EmojiCatalog.PAGE_SIZE)
        for (category in EmojiCatalog.categories) {
            assertTrue(category.label.isNotBlank())
            assertTrue("${category.label} needs a full page of choices", category.emoji.size >= 32)
        }
        assertTrue("the picker should offer at least 400 choices", EmojiCatalog.categories.sumOf { it.emoji.size } >= 400)
        assertTrue("most topics need more than one page", EmojiCatalog.categories.count { it.emoji.size > 32 } >= 6)
    }

    @Test fun commonChoicesExistInTheirExpectedTopics() {
        val examples = mapOf(
            "Smileys" to listOf("😂", "🙂", "🥰", "🤔", "😢", "😡"),
            "Gestures" to listOf("👍", "👎", "🙏", "👋", "🤷", "👩‍💻"),
            "Hearts" to listOf("❤️", "💔", "💕", "💋", "🌹"),
            "Nature" to listOf("🐶", "🐱", "🌻", "☀️", "🌧️"),
            "Food" to listOf("🍎", "🍕", "🍜", "🎂", "☕"),
            "Travel" to listOf("🚗", "🚆", "✈️", "🏠", "🏖️"),
            "Activities" to listOf("⚽", "🏏", "🎮", "🎵", "🎉"),
            "Objects" to listOf("📱", "💻", "💡", "🎁", "📚"),
            "Symbols" to listOf("✅", "❌", "❓", "➡️", "🏳️‍🌈"),
        )
        for ((label, expected) in examples) {
            val actual = EmojiCatalog.categories.single { it.label == label }.emoji
            assertTrue("$label is missing an everyday choice", actual.containsAll(expected))
        }
    }

    @Test fun noEmojiAppearsTwiceAcrossCategories() {
        val all = EmojiCatalog.categories.flatMap { it.emoji }
        assertEquals(all.size, all.toSet().size)
        // A presentation selector must not disguise the same emoji as a second choice.
        assertEquals(all.size, all.map { it.replace("\uFE0F", "") }.toSet().size)
    }

    @Test fun everyEntryHasCompleteUnicodeCharacters() {
        for (entry in EmojiCatalog.categories.flatMap { it.emoji } + EmojiCatalog.recent) {
            assertTrue("blank entry", entry.isNotBlank())
            assertFalse("$entry holds a control character", entry.any { Character.isISOControl(it) })
            var index = 0
            while (index < entry.length) {
                val character = entry[index]
                if (Character.isHighSurrogate(character)) {
                    assertTrue("$entry has a split surrogate pair", index + 1 < entry.length && Character.isLowSurrogate(entry[index + 1]))
                    index += 2
                } else {
                    assertFalse("$entry has a lone low surrogate", Character.isLowSurrogate(character))
                    assertFalse("$entry has a replacement character", character == '\uFFFD')
                    index++
                }
            }
            assertFalse("$entry starts with a sequence marker", entry.first() in listOf('\u200D', '\uFE0F'))
            assertFalse("$entry ends with an incomplete join", entry.endsWith("\u200D"))
        }
    }

    @Test fun defaultPageIsFullUniqueAndAvailableInTheCatalog() {
        val all = EmojiCatalog.categories.flatMap { it.emoji }.toSet()
        assertEquals(32, EmojiCatalog.recent.size)
        assertEquals(32, EmojiCatalog.recent.toSet().size)
        assertTrue(all.containsAll(EmojiCatalog.recent))
        assertTrue(EmojiCatalog.recent.containsAll(listOf("😂", "❤️", "👍", "🙏", "🎉", "✅")))
    }

    @Test fun completeJoinedAndPresentationSequencesArePreserved() {
        val all = EmojiCatalog.categories.flatMap { it.emoji }
        assertTrue(all.contains("\uD83D\uDC69\u200D\uD83D\uDCBB")) // Woman technologist.
        assertTrue(all.contains("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08")) // Rainbow flag.
        assertTrue(all.contains("\u2764\uFE0F")) // Red heart with emoji presentation.
    }

    @Test fun categorySearchAndAliasesReachTheNewTopics() {
        for (category in EmojiCatalog.categories) {
            assertEquals(listOf(category), EmojiCatalog.matching("  ${category.label.uppercase()}  "))
        }
        for ((query, label) in mapOf("hand" to "Gestures", "love" to "Hearts", "animals" to "Nature", "coffee" to "Food", "plane" to "Travel", "music" to "Activities", "computer" to "Objects", "arrow" to "Symbols")) {
            assertEquals(label, EmojiCatalog.matching(query).single().label)
        }
        assertEquals(EmojiCatalog.categories, EmojiCatalog.matching(null))
        assertEquals(EmojiCatalog.categories, EmojiCatalog.matching("  "))
        assertEquals(EmojiCatalog.categories, EmojiCatalog.matching(" ALL "))
        assertTrue(EmojiCatalog.matching("no such emoji category").isEmpty())
    }
}
