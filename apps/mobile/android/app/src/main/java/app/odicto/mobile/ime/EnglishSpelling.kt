package app.odicto.mobile.ime

import android.content.Context
import java.util.Locale

/**
 * Local English spelling suggestions. [words] is ordered from most to least frequent.
 *
 * Lookup generates at most 53 * (word length + 1) single-edit candidates and probes a hash table;
 * it does not scan the dictionary or store typed words. Suggestions require an explicit user tap.
 * Automatic correction uses only the small, reviewed typo table below, never the nearest word.
 */
internal class EnglishSpelling(words: List<String>) {
    private val ranks: Map<String, Int> = buildMap {
        words.forEachIndexed { index, word ->
            val normalized = word.lowercase(Locale.ROOT)
            if (normalized.length in 1..MAX_WORD_LENGTH && normalized.all { it in 'a'..'z' } && normalized !in this) {
                put(normalized, index)
            }
        }
    }

    /** Up to three complete-word replacements, with adjacent transpositions treated as one edit. */
    fun suggest(word: String): List<String> {
        val lower = normalizedInput(word) ?: return emptyList()
        if (lower in ranks) return emptyList()
        val found = hashSetOf<String>()
        fun consider(candidate: String) {
            if (candidate != lower && candidate in ranks) found.add(candidate)
        }
        val curated = TYPOS[lower]
        curated?.let(::consider)
        for (index in lower.indices) {
            consider(lower.removeRange(index, index + 1))
            if (index + 1 < lower.length && lower[index] != lower[index + 1]) {
                consider(lower.substring(0, index) + lower[index + 1] + lower[index] + lower.substring(index + 2))
            }
            for (letter in 'a'..'z') {
                if (letter != lower[index]) consider(lower.substring(0, index) + letter + lower.substring(index + 1))
            }
        }
        for (index in 0..lower.length) {
            for (letter in 'a'..'z') consider(lower.substring(0, index) + letter + lower.substring(index))
        }
        return found.sortedWith(compareBy<String> { if (it == curated) 0 else 1 }.thenBy { ranks.getValue(it) })
            .take(3).map { preserveCase(word, it) }
    }

    /** A supplied dictionary can explicitly protect a word, including a locally recognized name. */
    fun correction(word: String): String? {
        val lower = normalizedInput(word) ?: return null
        return if (lower in ranks) null else typoCorrection(word)
    }

    companion object {
        private const val MAX_WORD_LENGTH = 24
        private const val ASSET = "spelling/english-40000.txt"
        @Volatile private var bundled: EnglishSpelling? = null

        /** Load once off the UI thread. The process cache contains dictionary data, never user text. */
        fun load(context: Context): EnglishSpelling = bundled ?: synchronized(this) {
            bundled ?: context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                EnglishSpelling(reader.readLines()).also { bundled = it }
            }
        }

        /** Immediate automatic correction; no asset loading, Android service, I/O, or learning. */
        fun typoCorrection(word: String): String? {
            val lower = normalizedInput(word) ?: return null
            return TYPOS[lower]?.let { preserveCase(word, it) }
        }

        private fun normalizedInput(word: String): String? {
            if (word.length !in 3..MAX_WORD_LENGTH) return null
            val lower = word.all { it in 'a'..'z' }
            val title = word[0] in 'A'..'Z' && word.drop(1).all { it in 'a'..'z' }
            if (!lower && !title) return null
            return word.lowercase(Locale.ROOT)
        }

        private fun preserveCase(original: String, replacement: String): String =
            if (original[0] in 'A'..'Z') replacement[0].uppercaseChar() + replacement.substring(1) else replacement

        // Deliberately excludes valid words such as form/from, its/it's, were/we're, and cant/can't;
        // names, acronyms and unfamiliar words must never be changed merely for being unfamiliar.
        // Keep every source key out of the bundled vocabulary when extending this reviewed list.
        private val TYPOS = mapOf(
            "teh" to "the", "thsi" to "this", "taht" to "that",
            "recieve" to "receive", "recieved" to "received", "recieving" to "receiving",
            "becuase" to "because", "beacuse" to "because", "becasue" to "because", "becouse" to "because",
            "definately" to "definitely", "definatly" to "definitely", "definetly" to "definitely", "definitley" to "definitely",
            "tomorow" to "tomorrow", "tommorow" to "tomorrow", "tommorrow" to "tomorrow",
            "seperate" to "separate", "seperately" to "separately", "seperated" to "separated",
            "wierd" to "weird", "thier" to "their",
            "acheive" to "achieve", "acheived" to "achieved", "acheiving" to "achieving",
            "accomodate" to "accommodate", "acommodate" to "accommodate", "acomodate" to "accommodate",
            "occured" to "occurred", "occurence" to "occurrence",
            "sucess" to "success", "succesful" to "successful", "successfull" to "successful",
            "untill" to "until", "comming" to "coming", "begining" to "beginning",
            "beleive" to "believe", "beleived" to "believed", "freind" to "friend", "freinds" to "friends",
            "posession" to "possession", "tounge" to "tongue", "buisness" to "business",
            "foward" to "forward", "realy" to "really", "adress" to "address",
            "relevent" to "relevant", "arguement" to "argument", "enviroment" to "environment",
            "goverment" to "government", "responsibilty" to "responsibility",
            "thnaks" to "thanks", "tahnks" to "thanks",
        )
    }
}
