package app.odicto.mobile.ime

/**
 * How a held backspace speeds up.
 *
 * The first [LETTERS_BEFORE_WORDS] deletions remove one character so a short hold is easy to stop.
 * Everything after that removes the word before the cursor, including the space that separated it,
 * so a continued hold clears a dictated paragraph instead of crawling one letter at a time.
 */
internal object BackspacePace {
    const val LETTERS_BEFORE_WORDS = 10
    /** Wait before the first repeat. A normal tap is shorter than this and deletes once. */
    const val HOLD_MS = 260L
    const val LETTER_INTERVAL_MS = 42L
    const val WORD_INTERVAL_MS = 34L
    /** How much text before the cursor a word-sized delete is willing to read. */
    const val WORD_LOOKBEHIND = 96

    fun removesWord(ordinal: Int): Boolean = ordinal >= LETTERS_BEFORE_WORDS

    /** Delay before sending [nextOrdinal]. Ordinal 0 is the touch-down and has already been sent. */
    fun delayUntil(nextOrdinal: Int): Long = when {
        nextOrdinal <= 1 -> HOLD_MS
        nextOrdinal < LETTERS_BEFORE_WORDS -> LETTER_INTERVAL_MS
        else -> WORD_INTERVAL_MS
    }

    /**
     * UTF-16 units to delete from the end of [before].
     * Letter steps remove one code point. Word steps remove the trailing word and the spaces after it.
     */
    fun charCountToDelete(before: String, ordinal: Int): Int {
        if (before.isEmpty()) return 0
        if (!removesWord(ordinal)) return Character.charCount(before.codePointBefore(before.length))
        return wordCharCount(before)
    }

    private fun wordCharCount(before: String): Int {
        val end = before.length
        var start = end
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        // A lookbehind can begin on the second half of a character. Leave that unit untouched.
        if (start == 0 && Character.isLowSurrogate(before[0])) start = 1
        val count = end - start
        return if (count > 0) count else Character.charCount(before.codePointBefore(end))
    }
}
