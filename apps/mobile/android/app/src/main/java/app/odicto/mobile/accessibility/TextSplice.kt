package app.odicto.mobile.accessibility

/** Pure cursor-aware splice so accessibility insertion never depends on the clipboard. */
object TextSplice {
    fun merge(existing: String?, selectionStart: Int, selectionEnd: Int, insert: String): String? {
        if (existing == null) return null
        val start = selectionStart.coerceIn(0, existing.length)
        val end = selectionEnd.coerceIn(start, existing.length)
        return existing.substring(0, start) + insert + existing.substring(end)
    }

    fun caret(selectionStart: Int, existingLength: Int, insertLength: Int) = selectionStart.coerceIn(0, existingLength) + insertLength
}
