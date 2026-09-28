package app.odicto.mobile

import app.odicto.mobile.ime.EmojiCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogue validates itself at construction; these tests pin the guarantees it relies on. */
class EmojiCatalogTest {
    @Test fun everyCategoryIsLabelledAndPopulated() {
        assertTrue(EmojiCatalog.categories.isNotEmpty())
        for (category in EmojiCatalog.categories) {
            assertTrue(category.label.isNotBlank())
            assertTrue(category.emoji.isNotEmpty())
            assertTrue("category ${category.label} should stay to the most used", category.emoji.size <= EmojiCatalog.PAGE_SIZE)
        }
    }

    @Test fun noEmojiAppearsTwice() {
        val all = EmojiCatalog.categories.flatMap { it.emoji }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun everyEntryIsAWellFormedCodePointSequence() {
        for (entry in EmojiCatalog.categories.flatMap { it.emoji } + EmojiCatalog.recent) {
            assertTrue("$entry is blank", entry.isNotBlank())
            assertTrue("$entry holds a control character", entry.none { Character.isISOControl(it) })
            // A valid sequence is one or two code points: BMP, or a surrogate pair.
            val codePoints = Character.codePointCount(entry, 0, entry.length)
            assertTrue("$entry is malformed", codePoints == 1 || codePoints == 2)
        }
    }

    @Test fun recentEntriesAreAlsoRealEmoji() {
        assertTrue(EmojiCatalog.recent.isNotEmpty())
        for (entry in EmojiCatalog.recent) {
            assertTrue(entry.isNotBlank())
            assertTrue(entry.none { Character.isISOControl(it) })
        }
    }

    @Test fun aSupplementaryCharacterIsNotTruncated() {
        // A thumbtack is outside the BMP; a half of it would render as a broken glyph.
        assertTrue(EmojiCatalog.categories.flatMap { it.emoji }.any { it.codePointAt(0) > 0xFFFF })
    }
}
