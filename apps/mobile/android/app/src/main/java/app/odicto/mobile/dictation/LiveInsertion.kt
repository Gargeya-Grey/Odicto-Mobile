package app.odicto.mobile.dictation

/** Owns one composing range. Never repositions the cursor or appends a final duplicate. */
class LiveInsertion(private val initial: AiSelection) {
    var text = ""; private set
    private var wrote = false
    private var stopped = false
    fun update(current: AiSelection?, preceding: String, next: String, compose: (String) -> Boolean): Boolean {
        if (stopped) return false
        val cursor = minOf(initial.start, initial.end) + text.length
        val matches = if (!wrote) initial.matches(current) else
            current?.start == cursor && current.end == cursor && preceding == text
        if (!matches) { stopped = true; return false }
        if (wrote && next == text) return true
        if (!compose(next)) { stopped = true; return false }
        text = next; wrote = true
        return true
    }
    fun stop() { stopped = true }
}
