package app.odicto.mobile

import app.odicto.mobile.ime.EnglishSpelling
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class EnglishSpellingTest {
    private val common = listOf("the", "this", "that", "because", "receive", "definitely", "tomorrow", "hello", "world", "spelling", "friend", "accommodate")

    @Test fun commonTyposHaveImmediateConservativeCorrections() {
        for ((typed, expected) in mapOf("teh" to "the", "recieve" to "receive", "becuase" to "because", "definately" to "definitely", "tomorow" to "tomorrow")) {
            assertEquals(expected, EnglishSpelling.typoCorrection(typed))
            assertEquals(expected, EnglishSpelling(common).correction(typed))
            assertEquals(expected, EnglishSpelling(common).suggest(typed).first())
        }
    }

    @Test fun supportsTitleCaseWithoutChangingAcronymsOrMixedCase() {
        assertEquals("The", EnglishSpelling.typoCorrection("Teh"))
        assertEquals("Receive", EnglishSpelling(common).suggest("Recieve").first())
        for (word in listOf("TEH", "RECIEVE", "beCUase", "iPhone", "OpenAI", "NASA")) {
            assertNull(word, EnglishSpelling.typoCorrection(word))
            assertTrue(word, EnglishSpelling(common).suggest(word).isEmpty())
        }
    }

    @Test fun validWordsNamesAndSlangAreNeverNearestMatchAutocorrections() {
        val engine = EnglishSpelling(common + listOf("from", "form", "were", "its", "cant", "won", "mike", "cat", "hey"))
        for (word in listOf("from", "form", "were", "its", "cant", "won", "hello", "Gargeya", "Priya", "Aarav", "Mika", "mika", "gonna", "lol", "brb", "ok", "zzqxv")) {
            assertNull(word, EnglishSpelling.typoCorrection(word))
            assertNull(word, engine.correction(word))
        }
        assertTrue(engine.suggest("hello").isEmpty())
        assertTrue("manual suggestions can be offered without automatic replacement", engine.suggest("mika").contains("mike"))
    }

    @Test fun generatesDeletionInsertionSubstitutionAndTranspositionSuggestions() {
        val engine = EnglishSpelling(listOf("hello", "spelling", "world", "friend"))
        assertEquals(listOf("hello"), engine.suggest("helllo"))
        assertEquals(listOf("hello"), engine.suggest("helo"))
        assertEquals(listOf("hello"), engine.suggest("hallo"))
        assertEquals(listOf("world"), engine.suggest("wrold"))
        assertEquals(listOf("spelling"), engine.suggest("speling"))
    }

    @Test fun ranksByFrequencyLimitsChoicesAndDoesNotPredictPrefixes() {
        val engine = EnglishSpelling(listOf("cot", "cut", "cat", "coat", "cot", "catalogue"))
        assertEquals(listOf("cot", "cut", "cat"), engine.suggest("cit"))
        assertEquals(3, engine.suggest("cit").distinct().size)
        assertTrue(engine.suggest("cat").isEmpty())
        assertTrue("no prefix completion", EnglishSpelling(listOf("catalogue")).suggest("cata").isEmpty())
    }

    @Test fun curatedMultipleEditTyposRemainAvailableAsSuggestions() {
        val engine = EnglishSpelling(listOf("accommodate", "tomorrow", "definitely"))
        assertEquals("accommodate", engine.suggest("acomodate").first())
        assertEquals("tomorrow", engine.suggest("tommorow").first())
    }

    @Test fun rejectsNonWordsLongInputsAndMixedScripts() {
        val engine = EnglishSpelling(common)
        for (word in listOf("", "a", "I", "teh ", " teh", "teh!", "teh42", "foo@bar", "don't", "user_name", "tеh", "नमस्ते", "café", "a".repeat(25))) {
            assertTrue(word, engine.suggest(word).isEmpty())
            assertNull(word, EnglishSpelling.typoCorrection(word))
        }
    }

    @Test fun suppliedKnownWordsOverrideAutomaticTypos() {
        val engine = EnglishSpelling(listOf("teh", "the"))
        assertNull(engine.correction("teh"))
        assertTrue(engine.suggest("teh").isEmpty())
    }

    @Test fun casingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("Definitely", EnglishSpelling.typoCorrection("Definately"))
            assertEquals(listOf("Spelling"), EnglishSpelling(listOf("spelling")).suggest("Speling"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun bundledFrequencyListIsUsefulAndNeverAutocorrectsItsKnownWords() {
        val words = File("src/main/assets/spelling/english-40000.txt").readLines(Charsets.UTF_8)
        assertTrue(words.size in 20_000..50_000)
        assertEquals(words.size, words.toSet().size)
        assertEquals("the", words.first())
        assertTrue(words.containsAll(common))
        for (word in words) {
            assertTrue(word, word.length in 1..24 && word.all { it in 'a'..'z' })
            assertNull("a known word must never be a curated typo: $word", EnglishSpelling.typoCorrection(word))
        }
        val engine = EnglishSpelling(words)
        for ((typed, expected) in mapOf("teh" to "the", "recieve" to "receive", "becuase" to "because", "definately" to "definitely", "tomorow" to "tomorrow", "speling" to "spelling")) {
            assertTrue("$expected must be reachable for $typed", expected in engine.suggest(typed))
        }
    }
}
