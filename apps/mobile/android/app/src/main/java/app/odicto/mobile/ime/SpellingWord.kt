package app.odicto.mobile.ime

/** A bounded word in a known cursor epoch, never persisted or used as provider context. */
internal data class SpellingWord(val text: String, val end: Int, val epoch: Long) {
    companion object {
        fun atCursor(tail: String?, end: Int, epoch: Long): SpellingWord? {
            if (tail == null || end < 0) return null
            val text = tail.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
            if (text.length !in 2..32 || text.length > end) return null
            val prefix = tail.dropLast(text.length)
            // Require a real word boundary. A truncated suffix and URL/email/identifier fragments
            // must not become a plausible English word just because they end in ASCII letters.
            if (prefix.isEmpty() && end != text.length) return null
            if (prefix.isNotEmpty() && !prefix.last().isWhitespace() && prefix.last() !in "(\"“") return null
            return SpellingWord(text, end, epoch)
        }
    }
}
