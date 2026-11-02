package app.odicto.mobile

import app.odicto.mobile.storage.VocabularyHints
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class VocabularyHintsTest {
    @Test fun tagsKeepPhrasesNormalizeUnicodeAndRemoveDuplicates() {
        assertEquals(listOf("Odicto", "New Delhi", "café", "नमस्ते"),
            VocabularyHints.normalize(listOf(" Odicto, odicto ", "New Delhi", "cafe\u0301", "café", "नमस्ते\nनमस्ते", "")))
        assertEquals("Odicto, New Delhi", VocabularyHints.prompt(listOf("Odicto", "New Delhi")))
    }

    @Test fun byteLimitIncludesSeparatorsAndUnicodeWithoutTruncation() {
        assertTrue(VocabularyHints.fits(listOf("x".repeat(224))))
        assertFalse(VocabularyHints.fits(listOf("x".repeat(225))))
        assertTrue(VocabularyHints.fits(listOf("é".repeat(112))))
        assertFalse(VocabularyHints.fits(listOf("é".repeat(113))))
        assertFalse(VocabularyHints.fits(listOf("x".repeat(222), "y")))
        assertNull(VocabularyHints.fromJson(JSONArray().put("x".repeat(225))))
    }

    @Test fun corruptedStoredOrBridgeValuesCannotBecomePromptText() {
        assertNull(VocabularyHints.fromJson(null))
        assertNull(VocabularyHints.fromJson(JSONArray().put(42)))
        assertEquals(emptyList<String>(), VocabularyHints.forStored("not json"))
        assertEquals(emptyList<String>(), VocabularyHints.forStored("[42]"))
        assertEquals(listOf("Odicto"), VocabularyHints.forStored("[\"Odicto\",\"odicto\"]"))
    }
}
