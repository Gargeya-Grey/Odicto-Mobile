package app.odicto.mobile.ime

/**
 * Typing habits that do not need an editor connection: when a letter should come out as a capital,
 * and when a second space should become a period.
 *
 * Holding a letter does not swap in a symbol. Digits already have their own row, and a silent swap
 * (Q becoming 1) read as the key changing under the finger.
 */
internal object TypingAssist {
    private val sentenceEnd = Regex(".*[.!?]\\s+", RegexOption.DOT_MATCHES_ALL)

    fun shouldAutoCap(before: String): Boolean {
        if (before.isEmpty()) return true
        if (before.last() == '\n') return true
        return before.matches(sentenceEnd)
    }

    /** True when [before] already ends in a space that follows a letter, so the next space is ". ". */
    fun periodForSecondSpace(before: String): Boolean {
        if (before.length < 2 || !before.endsWith(' ')) return false
        return before[before.length - 2].isLetter()
    }
}
