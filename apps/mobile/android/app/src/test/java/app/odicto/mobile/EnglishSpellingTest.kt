package app.odicto.mobile

import app.odicto.mobile.ime.EnglishSpelling
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class EnglishSpellingTest {
    private val common = listOf("the", "this", "that", "because", "receive", "definitely", "tomorrow", "hello", "world", "spelling", "friend", "accommodate")

    private val bundledWords: List<String> by lazy { File("src/main/assets/spelling/english-40000.txt").readLines(Charsets.UTF_8) }
    private val real: EnglishSpelling by lazy { EnglishSpelling(bundledWords) }

    private val neverCorrect = listOf(
        "lol", "lmao", "omg", "brb", "btw", "idk", "imo", "imho", "tbh", "ty", "thx", "pls", "plz", "ok", "okay", "yeah", "yep",
        "nope", "nah", "gonna", "wanna", "gotta", "haha", "hehe", "hmm", "umm", "ugh", "wow", "yay", "bro", "app", "apps", "email",
        "emails", "online", "wifi", "url", "emoji", "emojis",
    )

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
            assertNull(word, real.autocorrect(word, sentenceStart = true))
            assertNull(word, real.autocorrect(word, sentenceStart = false))
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
        assertTrue("no prefix completion in suggestions", EnglishSpelling(listOf("catalogue")).suggest("cata").isEmpty())
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
            assertNull(word, engine.autocorrect(word, sentenceStart = true))
            assertNull(word, engine.autocorrect(word, sentenceStart = false))
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
            assertEquals("I", real.autocorrect("i", false))
            assertEquals("I'm", real.autocorrect("im", false))
            assertEquals("Don't", real.autocorrect("Dont", true))
            assertEquals("Help", real.complete("Hel").first())
        } finally { Locale.setDefault(previous) }
    }

    @Test fun bundledFrequencyListIsUsefulAndNeverAutocorrectsItsKnownWords() {
        val words = bundledWords
        assertTrue(words.size in 20_000..50_000)
        assertEquals(words.size, words.toSet().size)
        assertEquals("the", words.first())
        assertTrue(words.containsAll(common))
        for (word in words) {
            assertTrue(word, word.length in 1..24 && word.all { it in 'a'..'z' })
            assertNull("a known word must never be a curated typo: $word", EnglishSpelling.typoCorrection(word))
            // Only "i" and the reviewed contraction table (wont, hes, whats ...) may replace a dictionary word.
            val replaced = real.autocorrect(word, sentenceStart = false)
            assertTrue("a known word must stay: $word -> $replaced", replaced == null || replaced.contains('\'') || (word == "i" && replaced == "I"))
        }
        for ((typed, expected) in mapOf("teh" to "the", "recieve" to "receive", "becuase" to "because", "definately" to "definitely", "tomorow" to "tomorrow", "speling" to "spelling")) {
            assertTrue("$expected must be reachable for $typed", expected in real.suggest(typed))
        }
    }

    // ---- Autocorrect on space ----

    @Test fun correctsCommonMistypingsWithTheRealDictionary() {
        val expected = mapOf(
            "helo" to "hello", "wrod" to "word", "thw" to "the", "abd" to "and", "jsut" to "just", "knwo" to "know",
            "wuth" to "with", "tge" to "the", "yhe" to "the", "goign" to "going", "whne" to "when", "probelm" to "problem",
            "apprecaite" to "appreciate", "beautful" to "beautiful", "teh" to "the", "recieve" to "receive",
            "dont" to "don't", "im" to "I'm", "i" to "I",
        )
        for ((typed, fixed) in expected) assertEquals(typed, fixed, real.autocorrect(typed, sentenceStart = false))
    }

    @Test fun correctsOtherPlausibleSingleKeyMistakesButNotAmbiguousOnes() {
        // Missed or extra letters, neighbouring keys and swaps in ordinary words.
        for ((typed, fixed) in mapOf("hte" to "the", "adn" to "and", "waht" to "what", "liek" to "like", "wiht" to "with", "meetign" to "meeting", "mesage" to "message", "finaly" to "finally", "togehter" to "together")) {
            assertEquals(typed, fixed, real.autocorrect(typed, sentenceStart = false))
        }
        // "wnat" is as close to "what" as to "want": ambiguous, so it stays.
        assertNull(real.autocorrect("wnat", sentenceStart = false))
    }

    @Test fun titleCaseIsCorrectedOnlyAtASentenceStartAndKeepsItsCase() {
        assertEquals("The", real.autocorrect("Teh", sentenceStart = true))
        assertEquals("Don't", real.autocorrect("Dont", sentenceStart = true))
        assertEquals("I'm", real.autocorrect("Im", sentenceStart = true))
        assertEquals("Hello", real.autocorrect("Helo", sentenceStart = true))
        assertNull(real.autocorrect("Teh", sentenceStart = false))
        assertNull(real.autocorrect("Dont", sentenceStart = false))
        assertNull(real.autocorrect("Helo", sentenceStart = false))
        assertNull("only lowercase i is fixed", real.autocorrect("I", sentenceStart = true))
        assertNull(real.autocorrect("TEH", sentenceStart = true))
        assertNull(real.autocorrect("DONT", sentenceStart = true))
    }

    @Test fun neverChangesValidWordsNamesTechTermsOrSlang() {
        for (word in listOf("from", "form", "were", "its", "cant", "won", "hello", "mika", "zzqxv", "kotlin", "lets", "ill", "id", "well", "shell", "wed")) {
            assertNull(word, real.autocorrect(word, sentenceStart = false))
        }
        for (word in listOf("Mika", "Gargeya", "Priya", "Aarav", "Kotlin")) {
            assertNull(word, real.autocorrect(word, sentenceStart = false))
        }
        for (word in listOf("Gargeya", "Priya", "Aarav", "Kiara", "Zzqxv", "Kotlin")) {
            assertNull("$word even at a sentence start", real.autocorrect(word, sentenceStart = true))
        }
        for (word in listOf("NASA", "iPhone", "OpenAI", "TEH", "McDonald")) {
            assertNull(word, real.autocorrect(word, sentenceStart = true))
        }
    }

    @Test fun slangAndTechWordsAreNeverCorrected() {
        for (word in neverCorrect) {
            assertNull(word, real.autocorrect(word, sentenceStart = false))
            assertNull(word, real.autocorrect(word, sentenceStart = true))
            assertNull(word, real.autocorrect(word.replaceFirstChar { it.uppercaseChar() }, sentenceStart = true))
            assertNull(word, real.autocorrect(word.uppercase(Locale.ROOT), sentenceStart = true))
        }
        // Short chat abbreviations sit one edit from very common words (ofc -> of).
        for (word in listOf("ofc", "ngl", "ppl", "rn", "istg", "tbf", "omw", "wyd", "hmu", "pic", "convo", "sus", "abt")) {
            assertNull(word, real.autocorrect(word, sentenceStart = false))
        }
    }

    @Test fun contractionsGetTheirApostropheAndValidWordsDoNot() {
        val contractions = mapOf(
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't",
            "werent" to "weren't", "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "havent" to "haven't",
            "hasnt" to "hasn't", "hadnt" to "hadn't", "wont" to "won't", "im" to "I'm", "ive" to "I've", "youre" to "you're",
            "theyre" to "they're", "thats" to "that's", "whats" to "what's", "wheres" to "where's", "theres" to "there's", "hes" to "he's",
            "shes" to "she's", "weve" to "we've", "youve" to "you've", "theyve" to "they've", "youll" to "you'll", "theyll" to "they'll",
            "itll" to "it'll", "youd" to "you'd", "theyd" to "they'd",
        )
        for ((typed, fixed) in contractions) {
            assertEquals(typed, fixed, real.autocorrect(typed, sentenceStart = false))
            assertEquals(typed, fixed, EnglishSpelling.builtinCorrection(typed, sentenceStart = false))
            // The sentence-start form capitalises; I'm and I've already start with the capital I.
            assertEquals(typed, fixed.replaceFirstChar { it.uppercaseChar() }, real.autocorrect(typed.replaceFirstChar { it.uppercaseChar() }, sentenceStart = true))
        }
        for (word in listOf("cant", "lets", "ill", "id", "were", "well", "shell", "wed", "its")) {
            assertNull(word, real.autocorrect(word, sentenceStart = false))
            assertNull(word, EnglishSpelling.builtinCorrection(word, sentenceStart = false))
        }
    }

    @Test fun builtinCorrectionWorksBeforeTheDictionaryLoads() {
        assertEquals("I", EnglishSpelling.builtinCorrection("i", sentenceStart = false))
        assertEquals("The", EnglishSpelling.builtinCorrection("Teh", sentenceStart = true))
        assertNull(EnglishSpelling.builtinCorrection("Teh", sentenceStart = false))
        assertNull(EnglishSpelling.builtinCorrection("jsut", sentenceStart = false)) // needs the dictionary
        assertNull(EnglishSpelling.builtinCorrection("TEH", sentenceStart = true))
    }

    @Test fun shortWordsNeedAStrictlyPlausibleEditAndAFrequentTarget() {
        // Non-neighbour substitution is never enough for a short word, however frequent the target.
        val engine = EnglishSpelling(listOf("the", "and", "cat", "hot"))
        assertNull(engine.autocorrect("tha", sentenceStart = false)) // a is not next to e
        assertNull(engine.autocorrect("hat", sentenceStart = false)) // a is not next to o
        assertEquals("the", engine.autocorrect("thw", sentenceStart = false))
        // A target outside the top 3,000 words is not trusted for a 3-4 letter word.
        val padding = List(3_000) { index -> "q" + ('a' + index / 676) + ('a' + index / 26 % 26) + ('a' + index % 26) }
        assertEquals(3_000, padding.toSet().size)
        assertEquals("bird", EnglishSpelling(padding.take(2_000) + "bird").autocorrect("brid", sentenceStart = false)) // swapped letters, rank 2000
        assertNull(EnglishSpelling(padding + "bird").autocorrect("brid", sentenceStart = false)) // same edit, rank 3000
        // Elongated emphasis is not a typo.
        assertNull(real.autocorrect("heyy", sentenceStart = false))
        assertNull(real.autocorrect("yess", sentenceStart = false))
    }

    @Test fun twoCloseCandidatesLeaveTheWordAlone() {
        // "cit" sits one neighbour key from both "cot" and "cut", which are equally frequent.
        val engine = EnglishSpelling(listOf("cot", "cut"))
        assertNull(engine.autocorrect("cit", sentenceStart = false))
        // With a clearly more frequent winner the correction is applied.
        val clear = EnglishSpelling(listOf("cut") + List(400) { "xq" + ('a' + it / 26) + ('a' + it % 26) } + "cot")
        assertEquals("cut", clear.autocorrect("cit", sentenceStart = false))
    }

    @Test fun suggestPutsTheAutocorrectResultFirst() {
        assertEquals("hello", real.suggest("helo").first())
        assertEquals("the", real.suggest("thw").first())
        assertEquals("the", real.suggest("teh").first())
        assertEquals("Hello", real.suggest("Helo").first())
        assertEquals("don't", real.suggest("dont").first())
        assertEquals("I", real.suggest("i").first())
        assertTrue(real.suggest("helo").size <= 3)
        assertEquals(real.suggest("helo").size, real.suggest("helo").distinct().size)
        assertTrue(real.suggest("hello").isEmpty())
    }

    @Test fun correctionKeepsMatchingSentenceStartBehaviour() {
        assertEquals(real.autocorrect("Teh", true), real.correction("Teh"))
        assertEquals("hello", real.correction("helo"))
        assertNull(real.correction("hello"))
    }

    @Test fun loadedIsNullUntilTheBundledDictionaryHasBeenLoaded() {
        // load() needs an Android Context, which JVM unit tests do not have, so the loaded state is untestable
        // here. A fresh JVM never loads the bundled instance.
        assertNull(EnglishSpelling.loaded())
    }

    @Test fun autocorrectIsFastForLongWords() {
        val rounds = 200
        for (warm in 1..50) real.autocorrect("apprecaitions", false)
        val started = System.nanoTime()
        repeat(rounds) { assertNull(real.autocorrect("zqxwvbnmasdf", sentenceStart = false)) }
        repeat(rounds) { real.autocorrect("apprecaitions", sentenceStart = false) }
        val averageMillis = (System.nanoTime() - started) / 1e6 / (2 * rounds)
        assertTrue("average $averageMillis ms per call", averageMillis < 5.0)
    }

    // ---- Prefix completion ----

    @Test fun completesPrefixesInFrequencyOrder() {
        val result = real.complete("hel")
        assertEquals(3, result.size)
        assertTrue(result.toString(), "help" in result)
        assertEquals(result, result.sortedBy { bundledWords.indexOf(it) })
        assertTrue(result.all { it.startsWith("hel") && it.length > 3 })
        assertEquals(listOf("help", "held", "helpful"), result)
    }

    @Test fun completionKeepsTitleCaseAndRejectsOtherShapes() {
        assertEquals(real.complete("hel").map { it.replaceFirstChar { c -> c.uppercaseChar() } }, real.complete("Hel"))
        assertEquals(real.complete("h").map { it.replaceFirstChar { c -> c.uppercaseChar() } }, real.complete("H"))
        for (prefix in listOf("HEL", "hEl", "zzq", "", "he1", "h-l", "hel ", "né", "a".repeat(24))) {
            assertTrue(prefix, real.complete(prefix).isEmpty())
        }
    }

    @Test fun completionNeverReturnsThePrefixAndHonoursTheLimit() {
        for (prefix in listOf("the", "help", "hello", "a", "th")) assertFalse(prefix, prefix in real.complete(prefix, 10))
        assertEquals(2, real.complete("th", 2).size)
        assertEquals(1, real.complete("th", 1).size)
        assertEquals(10, real.complete("th", 10).size)
        assertTrue(real.complete("th", 0).isEmpty())
        assertTrue(real.complete("th", -1).isEmpty())
        // Fewer matches than the limit: only what exists, no padding.
        val small = EnglishSpelling(listOf("cat", "catalogue", "cats", "dog"))
        assertEquals(listOf("catalogue", "cats"), small.complete("cat", 5))
        assertEquals(listOf("catalogue"), small.complete("cat", 1))
        assertTrue(small.complete("dog").isEmpty())
    }

    @Test fun completionIsFast() {
        val started = System.nanoTime()
        repeat(1_000) {
            real.complete("s")
            real.complete("com")
            real.complete("Hel")
        }
        val millis = (System.nanoTime() - started) / 1e6
        assertTrue("3000 completions took $millis ms", millis < 2_000)
    }
}
