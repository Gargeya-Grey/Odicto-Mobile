package app.odicto.mobile.ime

internal class EditorTail(private val max: Int = 96) {
    private var tail: String? = null
    var complete = false
        private set

    fun clear() { tail = null; complete = false }
    fun knownSuffix(): String? = tail
    fun seed(value: String, cursor: Int) {
        tail = value.takeLast(max)
        complete = cursor >= 0 && cursor == tail!!.length
    }
    fun textBefore(limit: Int, read: () -> String?): String {
        if (tail == null) read()?.let { if (tail == null) seed(it, -1) }
        return tail?.takeLast(limit).orEmpty()
    }
    fun afterInsert(collapsedAt: Int, text: String): Int? {
        tail = (tail.orEmpty() + text).takeLast(max)
        if (tail?.length == max) complete = false
        return if (collapsedAt < 0) null else collapsedAt + text.length
    }
    fun afterReplace(start: Int, text: String): Int? {
        tail = text.takeLast(max)
        complete = start == 0 && text.length <= max
        return if (start < 0) null else start + text.length
    }
    fun afterDelete(collapsedAt: Int, charCount: Int): Int? {
        tail = tail?.dropLast(charCount)
        if (tail == "" && !complete) tail = null
        return if (collapsedAt < 0) null else (collapsedAt - charCount).coerceAtLeast(0)
    }
}
