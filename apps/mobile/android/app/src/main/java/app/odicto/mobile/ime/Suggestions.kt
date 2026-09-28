package app.odicto.mobile.ime

/**
 * On-device prefix matches. Nothing here is logged or sent off the phone.
 *
 * The keyboard does not show these while typing. A row that appears and disappears changes the
 * height of the keyboard under the finger. Voice is the primary way text gets into a field, so a
 * completion strip during ordinary typing is not on screen.
 */
internal object Suggestions {
    fun matches(prefix: String, limit: Int = 3): List<String> {
        val needle = prefix.lowercase()
        if (needle.isEmpty() || !needle.all { it.isLetter() }) return emptyList()
        return WORDS.filter { it.startsWith(needle) && it.length > needle.length }
            .sortedWith(compareBy({ it.length }, { it }))
            .take(limit)
    }

    private val WORDS = listOf(
        "about", "after", "again", "all", "also", "and", "any", "are", "around", "back",
        "because", "been", "before", "being", "best", "both", "but", "call", "can", "come",
        "could", "day", "did", "does", "done", "down", "each", "even", "ever", "every",
        "find", "first", "for", "from", "get", "give", "good", "great", "had", "has",
        "have", "her", "here", "him", "his", "home", "how", "into", "its", "just",
        "keep", "know", "last", "let", "like", "little", "look", "made", "make", "many",
        "may", "more", "most", "much", "must", "need", "never", "new", "next", "not",
        "now", "off", "old", "one", "only", "other", "our", "out", "over", "own",
        "part", "people", "place", "please", "put", "right", "said", "same", "see", "she",
        "should", "some", "still", "such", "take", "tell", "than", "thank", "thanks", "that",
        "the", "their", "them", "then", "there", "these", "they", "thing", "think", "this",
        "those", "through", "time", "today", "too", "two", "under", "very", "want", "was",
        "way", "well", "were", "what", "when", "where", "which", "while", "who", "will",
        "with", "work", "would", "year", "yes", "you", "your",
    )
}
