package app.odicto.mobile.ime

import android.content.Context
import java.util.Locale
import kotlin.math.ln

/**
 * Local English spelling: autocorrect on space, manual suggestions and prefix completion.
 * [words] is ordered from most to least frequent. Everything is pure and in memory; typed words are
 * never stored or logged, and the dictionary is read-only.
 *
 * Autocorrect policy (see [autocorrect]). A word is replaced only when ALL of these hold:
 *  1. Case: lowercase words qualify. A Title-case word qualifies only at a sentence start (elsewhere it is
 *     probably a name). ALL CAPS, mixed case (iPhone, OpenAI, NASA), digits and non-ASCII never qualify.
 *     A replacement keeps the input case: Title in, Title out.
 *  2. "i" becomes "I". Any other word under 3 letters is left alone, except the contractions below.
 *  3. A contraction typed without its apostrophe (dont, im, youre ...) becomes the apostrophe form. Words
 *     that are valid English as written (cant, lets, ill, id, were, well, shell, wed, its) are not in that
 *     table. Listed contractions win even over a dictionary hit (wont, hes, shes, whats, wheres).
 *  4. A dictionary word, and anything in the never-correct list (slang, tech words), is left alone.
 *  5. The reviewed typo table wins when it has the word (multi-error misspellings such as definately).
 *  6. Otherwise every dictionary word one edit away is scored. Lower is better:
 *       score = editCost + 0.15 * log2(rank + 2)         (rank 0 is the most frequent word)
 *     Edit costs model how people actually mistype on a QWERTY keyboard:
 *       adjacent transposition 0.9 | neighbour-key substitution 1.2 | vowel-for-vowel substitution 1.5
 *       (words of 5+ letters) | deleting a doubled letter 0.2 | inserting a doubled letter 0.2 (helo -> hello)
 *       | deleting a letter that sits next to its own neighbour key 1.8 | inserting any other missed letter 1.6
 *       | deleting any other letter 2.4 | substituting a non-neighbour key 3.2 (2.6 for words of 7+ letters).
 *     The frequency term is small (0.15 per halving of frequency) so it separates close edits without letting
 *     a common word win through an implausible edit.
 *  7. Acceptance. A candidate must score at most [MAX_SCORE] (3.3). Title-case words need 0.5 less (names
 *     are more likely there). Words of 3 or 4 letters are stricter: score at most [SHORT_MAX_SCORE] (2.7),
 *     candidate within the [SHORT_RANK_LIMIT] (3,000) most frequent words, and only transposition,
 *     neighbour-key substitution, doubled-letter deletion/insertion or neighbour-key deletion are allowed.
 *     A doubled letter deleted at the very end of a short word (heyy, yess) is treated as emphasis, not a typo.
 *  8. Ambiguity. If any other candidate (of any edit kind, even one the word length forbids) scores within
 *     [MARGIN] (0.2) of the winner, nothing is changed.
 *
 * Cost per call: at most about 27 * (length + 1) + 26 * length hash probes, no dictionary scan.
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

    // Alphabetical copy of the vocabulary for prefix completion (binary search for the prefix range).
    private val sortedWords: Array<String>
    private val sortedRanks: IntArray

    init {
        val entries = ranks.entries.sortedBy { it.key }
        sortedWords = Array(entries.size) { entries[it].key }
        sortedRanks = IntArray(entries.size) { entries[it].value }
    }

    /**
     * Up to three complete-word replacements, with adjacent transpositions treated as one edit.
     * When [autocorrect] (sentence start allowed) has a replacement, it is first.
     */
    fun suggest(word: String): List<String> {
        val auto = autocorrect(word, sentenceStart = true)
        val lower = normalizedInput(word) ?: return listOfNotNull(auto)
        if (lower in ranks) return listOfNotNull(auto)
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
        val ordered = found.sortedWith(compareBy<String> { if (it == curated) 0 else 1 }.thenBy { ranks.getValue(it) })
            .map { preserveCase(word, it) }
        val result = LinkedHashSet<String>()
        auto?.let(result::add)
        result.addAll(ordered)
        return result.take(3)
    }

    /** Kept for existing callers: the replacement for a word at a sentence start. */
    fun correction(word: String): String? = autocorrect(word, sentenceStart = true)

    /**
     * The replacement to apply when the user presses space, or null to leave the word. [word] is the run of
     * ASCII letters before the cursor. [sentenceStart] says whether a Title-case word begins a sentence.
     */
    fun autocorrect(word: String, sentenceStart: Boolean): String? {
        val kind = classify(word)
        if (kind == CASE_NONE) return null
        val title = kind == CASE_TITLE
        if (title && !sentenceStart) return null
        builtinReplacement(word, title)?.let { return it }
        // Replacements above are table hits. A table typo key never reaches here when it is a dictionary
        // word, because a supplied dictionary word is protected first.
        if (word.length < 3 || word.length > MAX_WORD_LENGTH) return null
        val lower = word.lowercase(Locale.ROOT)
        if (lower in NEVER_CORRECT || lower in ranks) return null
        TYPOS[lower]?.let { return preserveCase(word, it) }
        return nearestWord(lower, title)?.let { preserveCase(word, it) }
    }

    /**
     * Up to [limit] dictionary words that start with [prefix] and are longer than it, most frequent first.
     * A lowercase prefix gives lowercase words, a Title-case prefix gives Title-case words, other case
     * shapes give nothing. The prefix itself is never returned.
     */
    fun complete(prefix: String, limit: Int = 3): List<String> {
        if (limit <= 0 || prefix.isEmpty() || prefix.length >= MAX_WORD_LENGTH) return emptyList()
        // A lone capital letter is a Title-case prefix (classify rejects it as a word).
        val kind = if (prefix.length == 1 && prefix[0] in 'A'..'Z') CASE_TITLE else classify(prefix)
        if (kind == CASE_NONE) return emptyList()
        val lower = prefix.lowercase(Locale.ROOT)
        var low = lowerBound(lower)
        val high = lowerBound(lower + '{') // '{' sorts right after 'z'
        val bestIndex = IntArray(limit)
        val bestRank = IntArray(limit)
        var count = 0
        while (low < high) {
            val rank = sortedRanks[low]
            // Keep the lowest ranks in a short sorted array; the range is small enough to scan.
            if (sortedWords[low].length > lower.length && (count < limit || rank < bestRank[count - 1])) {
                var slot = if (count < limit) count else count - 1
                while (slot > 0 && bestRank[slot - 1] > rank) {
                    bestRank[slot] = bestRank[slot - 1]; bestIndex[slot] = bestIndex[slot - 1]; slot--
                }
                bestRank[slot] = rank; bestIndex[slot] = low
                if (count < limit) count++
            }
            low++
        }
        return List(count) {
            val result = sortedWords[bestIndex[it]]
            if (kind == CASE_TITLE) result[0].uppercaseChar() + result.substring(1) else result
        }
    }

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = sortedWords.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedWords[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    private class Hit(var any: Double, var allowed: Double)

    /** Best single-edit dictionary word for [lower], or null when none is plausible and unambiguous. */
    private fun nearestWord(lower: String, title: Boolean): String? {
        val length = lower.length
        val short = length <= SHORT_LENGTH
        val hits = HashMap<String, Hit>(8)
        fun offer(candidate: String, kind: Int, cost: Double, atEnd: Boolean = false) {
            val rank = ranks[candidate] ?: return
            val score = cost + FREQUENCY_WEIGHT * ln(rank + 2.0) / LN2
            val allowed = !short || (kind in SHORT_KINDS && rank < SHORT_RANK_LIMIT && !(kind == DEL_DOUBLE && atEnd))
            val hit = hits[candidate]
            if (hit == null) hits[candidate] = Hit(score, if (allowed) score else Double.MAX_VALUE)
            else {
                if (score < hit.any) hit.any = score
                if (allowed && score < hit.allowed) hit.allowed = score
            }
        }
        for (index in 0 until length) {
            val typed = lower[index]
            if (index + 1 < length && typed != lower[index + 1]) {
                val swapped = lower.substring(0, index) + lower[index + 1] + typed + lower.substring(index + 2)
                offer(swapped, TRANSPOSE, COST_TRANSPOSE)
            }
            for (letter in 'a'..'z') {
                if (letter == typed) continue
                val kind: Int
                val cost: Double
                when {
                    NEAR[typed - 'a'][letter - 'a'] -> { kind = SUB_NEAR; cost = COST_SUB_NEAR }
                    length >= 5 && typed in VOWELS && letter in VOWELS -> { kind = SUB_VOWEL; cost = COST_SUB_VOWEL }
                    else -> { kind = SUB_FAR; cost = if (length >= 7) COST_SUB_FAR_LONG else COST_SUB_FAR }
                }
                offer(lower.substring(0, index) + letter + lower.substring(index + 1), kind, cost)
            }
            val doubled = (index > 0 && lower[index - 1] == typed) || (index + 1 < length && lower[index + 1] == typed)
            val next = (index > 0 && NEAR[lower[index - 1] - 'a'][typed - 'a']) ||
                (index + 1 < length && NEAR[lower[index + 1] - 'a'][typed - 'a'])
            val deletion = lower.removeRange(index, index + 1)
            when {
                doubled -> offer(deletion, DEL_DOUBLE, COST_DEL_DOUBLE, atEnd = index == length - 1 || index == length - 2 && lower[index + 1] == typed)
                next -> offer(deletion, DEL_NEAR, COST_DEL_NEAR)
                else -> offer(deletion, DEL_FAR, COST_DEL_FAR)
            }
        }
        for (index in 0..length) {
            for (letter in 'a'..'z') {
                val doubled = (index > 0 && lower[index - 1] == letter) || (index < length && lower[index] == letter)
                if (doubled) offer(lower.substring(0, index) + letter + lower.substring(index), INS_DOUBLE, COST_INS_DOUBLE)
                else offer(lower.substring(0, index) + letter + lower.substring(index), INS_OTHER, COST_INS)
            }
        }
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for ((candidate, hit) in hits) {
            if (hit.allowed < bestScore) { best = candidate; bestScore = hit.allowed }
        }
        if (best == null) return null
        val limit = (if (short) minOf(MAX_SCORE, SHORT_MAX_SCORE) else MAX_SCORE) - if (title) TITLE_PENALTY else 0.0
        if (bestScore > limit) return null
        for ((candidate, hit) in hits) {
            if (candidate != best && hit.any - bestScore < MARGIN) return null
        }
        return best
    }

    companion object {
        private const val MAX_WORD_LENGTH = 24
        private const val ASSET = "spelling/english-40000.txt"
        private const val CASE_NONE = 0
        private const val CASE_LOWER = 1
        private const val CASE_TITLE = 2

        // Edit kinds and costs; see the class comment.
        private const val TRANSPOSE = 0
        private const val SUB_NEAR = 1
        private const val SUB_VOWEL = 2
        private const val SUB_FAR = 3
        private const val DEL_DOUBLE = 4
        private const val DEL_NEAR = 5
        private const val DEL_FAR = 6
        private const val INS_DOUBLE = 7
        private const val INS_OTHER = 8
        private const val COST_TRANSPOSE = 0.9
        private const val COST_SUB_NEAR = 1.2
        private const val COST_SUB_VOWEL = 1.5
        private const val COST_SUB_FAR = 3.2
        private const val COST_SUB_FAR_LONG = 2.6
        private const val COST_DEL_DOUBLE = 0.2
        private const val COST_DEL_NEAR = 1.8
        private const val COST_DEL_FAR = 2.4
        private const val COST_INS_DOUBLE = 0.2
        private const val COST_INS = 1.6
        private const val FREQUENCY_WEIGHT = 0.15
        private const val MAX_SCORE = 3.3
        private const val SHORT_MAX_SCORE = 2.7
        private const val TITLE_PENALTY = 0.5
        private const val MARGIN = 0.2
        private const val SHORT_LENGTH = 4
        private const val SHORT_RANK_LIMIT = 3_000
        private val LN2 = ln(2.0)
        private val SHORT_KINDS = setOf(TRANSPOSE, SUB_NEAR, DEL_DOUBLE, DEL_NEAR, INS_DOUBLE)
        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

        // QWERTY key adjacency (same row +-1, or the row above/below within one key width, allowing the
        // physical row stagger). Built once.
        private val NEAR: Array<BooleanArray> = run {
            val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val stagger = doubleArrayOf(0.0, 0.25, 0.75)
            val x = DoubleArray(26)
            val y = IntArray(26)
            rows.forEachIndexed { row, letters ->
                letters.forEachIndexed { column, letter -> x[letter - 'a'] = column + stagger[row]; y[letter - 'a'] = row }
            }
            Array(26) { a -> BooleanArray(26) { b -> a != b && kotlin.math.abs(y[a] - y[b]) <= 1 && kotlin.math.abs(x[a] - x[b]) <= 1.01 } }
        }

        @Volatile private var bundled: EnglishSpelling? = null

        /** Load once off the UI thread. The process cache contains dictionary data, never user text. */
        fun load(context: Context): EnglishSpelling = bundled ?: synchronized(this) {
            bundled ?: context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                EnglishSpelling(reader.readLines()).also { bundled = it }
            }
        }

        /** The bundled instance when [load] has already run; never loads, never blocks on I/O. */
        fun loaded(): EnglishSpelling? = bundled

        /** Immediate automatic correction; no asset loading, Android service, I/O, or learning. */
        fun typoCorrection(word: String): String? {
            val lower = normalizedInput(word) ?: return null
            return TYPOS[lower]?.let { preserveCase(word, it) }
        }

        /**
         * Dictionary-free subset of autocorrect, for use before the vocabulary has loaded: "i", the
         * contraction table and the reviewed typo table. Same case rules as [EnglishSpelling.autocorrect].
         */
        fun builtinCorrection(word: String, sentenceStart: Boolean): String? {
            val kind = classify(word)
            if (kind == CASE_NONE) return null
            val title = kind == CASE_TITLE
            if (title && !sentenceStart) return null
            builtinReplacement(word, title)?.let { return it }
            return if (word.length >= 3) TYPOS[word.lowercase(Locale.ROOT)]?.let { preserveCase(word, it) } else null
        }

        /** "i", then contractions; null when [word] is not one of them. [word] has passed [classify]. */
        private fun builtinReplacement(word: String, title: Boolean): String? {
            if (word.length == 1) return if (word == "i") "I" else null
            val contraction = CONTRACTIONS[word.lowercase(Locale.ROOT)] ?: return null
            return if (contraction[0] == 'I' || !title) contraction else contraction[0].uppercaseChar() + contraction.substring(1)
        }

        /** [CASE_LOWER] for a-z only, [CASE_TITLE] for one capital then lowercase, else [CASE_NONE]. */
        private fun classify(word: String): Int {
            if (word.isEmpty() || word.length > 32) return CASE_NONE
            val firstLower = word[0] in 'a'..'z'
            val firstUpper = word[0] in 'A'..'Z'
            if (!firstLower && !firstUpper) return CASE_NONE
            for (index in 1 until word.length) if (word[index] !in 'a'..'z') return CASE_NONE
            return if (firstLower) CASE_LOWER else if (word.length >= 2) CASE_TITLE else CASE_NONE
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

        // Contractions typed without the apostrophe. Deliberately excludes words that are valid as written:
        // cant, lets, ill, id, were, well, shell, wed, its. I'm and I've always carry a capital I.
        private val CONTRACTIONS = mapOf(
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't", "wasnt" to "wasn't",
            "arent" to "aren't", "werent" to "weren't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
            "wouldnt" to "wouldn't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
            "wont" to "won't", "im" to "I'm", "ive" to "I've",
            "youre" to "you're", "theyre" to "they're", "thats" to "that's", "whats" to "what's",
            "wheres" to "where's", "theres" to "there's", "heres" to "here's", "hes" to "he's", "shes" to "she's",
            "weve" to "we've", "youve" to "you've", "theyve" to "they've", "youll" to "you'll",
            "theyll" to "they'll", "itll" to "it'll", "youd" to "you'd", "theyd" to "they'd",
            "couldve" to "could've", "shouldve" to "should've", "wouldve" to "would've",
        )

        // Slang, abbreviations, brands and tech words that are not in the dictionary but are not typos.
        private val NEVER_CORRECT = hashSetOf(
            "lol", "lmao", "omg", "brb", "btw", "idk", "imo", "imho", "tbh", "ty", "thx", "pls", "plz", "ok",
            "okay", "yeah", "yep", "nope", "nah", "gonna", "wanna", "gotta", "haha", "hehe", "hmm", "umm", "ugh",
            "wow", "yay", "bro", "app", "apps", "email", "emails", "online", "wifi", "url", "emoji", "emojis",
            "dev", "devs", "tho", "thru", "cuz", "coz", "kinda", "sorta", "dunno", "gimme", "lemme", "gotcha",
            "fyi", "asap", "wtf", "smh", "irl", "dms", "pov", "lmk", "ily", "nvm", "ikr", "idc", "fomo", "tmi",
            "rip", "hbd", "mhm", "uhh", "uhm", "api", "apk", "sms", "usb", "pdf", "ios", "html", "css", "http",
            "https", "www", "gpt", "chatgpt", "openai", "github", "linkedin", "iphone", "ipad", "android", "google",
            "youtube", "instagram", "facebook", "whatsapp", "tiktok", "twitter", "selfie", "podcast", "vlog",
            "bitcoin", "crypto", "covid", "bluetooth", "gmail", "anthropic", "claude", "odicto",
            // Chat abbreviations: short, so one edit often reaches a common word (ofc -> of).
            "ofc", "ngl", "ppl", "istg", "tbf", "iirc", "afaik", "jk", "omw", "wyd", "hbu", "wbu", "np", "rofl",
            "ffs", "bc", "cya", "hmu", "tfw", "yolo", "bday", "convo", "pic", "pics", "vid", "vids", "txt", "msg",
            "msgs", "abt", "kk", "ya", "yall", "sus", "fr", "rn", "ig", "nm", "gm", "gn", "tysm", "ttyl", "imu",
            "luv", "thnx", "thanx", "pfp", "dm", "irl", "bff", "otw", "eta", "tldr", "ama", "aka", "diy", "faq",
        )

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
            "happend" to "happened", "wich" to "which", "cemetary" to "cemetery", "collegue" to "colleague",
            "concious" to "conscious", "curiousity" to "curiosity", "existance" to "existence",
            "liason" to "liaison", "maintainance" to "maintenance", "noticable" to "noticeable",
            "persistant" to "persistent", "publically" to "publicly", "truely" to "truly",
            "yesturday" to "yesterday", "experiance" to "experience", "independant" to "independent",
            "embarass" to "embarrass", "occassion" to "occasion", "neccessary" to "necessary",
            "necesary" to "necessary", "recomend" to "recommend", "reccomend" to "recommend",
            "foriegn" to "foreign", "gaurd" to "guard", "immediatly" to "immediately",
            "knowlege" to "knowledge", "prefered" to "preferred", "refered" to "referred",
            "surprize" to "surprise", "suprise" to "surprise", "writting" to "writing",
            "occuring" to "occurring", "begginning" to "beginning", "intresting" to "interesting",
            "diffrent" to "different", "diffrence" to "difference", "definitly" to "definitely",
            "proably" to "probably", "excellant" to "excellent", "accross" to "across",
            "apparantly" to "apparently", "basicly" to "basically", "catagory" to "category",
            "completly" to "completely", "congradulations" to "congratulations", "conveniant" to "convenient",
            "dissapoint" to "disappoint", "dissapear" to "disappear", "disapear" to "disappear",
            "familar" to "familiar", "grammer" to "grammar", "harrass" to "harass", "hieght" to "height",
            "interupt" to "interrupt", "lenght" to "length", "libary" to "library", "lisence" to "license",
            "oppurtunity" to "opportunity", "peice" to "piece", "percieve" to "perceive",
            "pronounciation" to "pronunciation", "religous" to "religious", "rythm" to "rhythm",
            "strenght" to "strength", "tendancy" to "tendency", "unfortunatly" to "unfortunately",
            "vegatable" to "vegetable", "wellcome" to "welcome", "noticible" to "noticeable",
        )
    }
}
