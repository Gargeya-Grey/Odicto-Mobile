package app.odicto.mobile.dictation

/** Request-scoped selection only; never includes the surrounding document. */
data class AiSelection(val start: Int, val end: Int, val text: String) {
    val valid get() = start >= 0 && end >= 0 && kotlin.math.abs(end - start) == text.length && text.length <= MAX_TEXT
    fun matches(other: AiSelection?) = valid && other != null && other.valid &&
        minOf(start, end) == minOf(other.start, other.end) &&
        maxOf(start, end) == maxOf(other.start, other.end) && text == other.text
    companion object { const val MAX_TEXT = 20_000; const val MAX_PROMPT = 8_000 }
}
