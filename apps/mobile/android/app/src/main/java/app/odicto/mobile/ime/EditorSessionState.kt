package app.odicto.mobile.ime

internal class EditorSessionState {
    data class Snapshot(val start: Int, val end: Int, val suffix: String?, val complete: Boolean = false)
    data class Edit(val after: Snapshot, val units: Int, val word: Boolean, val at: Long)
    enum class Evidence { CURRENT, PROGRESS, WAITING, INCOMPATIBLE }
    var epoch = 0L
        private set
    var connection: Any? = null
        private set
    var observed = Snapshot(-1, -1, null)
        private set
    var predicted = observed
        private set
    var composingStart = -1
    var composingEnd = -1
    var metadataUnsupported = false
    var ambiguous = false
    private val edits = ArrayDeque<Edit>()
    private val recent = ArrayDeque<Snapshot>()
    private val typingRuns = ArrayDeque<IntRange>()
    val pending: Boolean get() = edits.isNotEmpty()
    val pendingTyping: Boolean get() = edits.any { it.units == 0 }
    val deleteCount: Int get() = edits.count { it.units > 0 }
    val wordPending: Boolean get() = edits.any { it.word }
    val oldestAt: Long? get() = edits.firstOrNull()?.at

    fun reset(connection: Any?, start: Int, end: Int) {
        epoch++
        this.connection = connection
        observed = Snapshot(start, end, null)
        predicted = observed
        edits.clear(); recent.clear(); typingRuns.clear()
        composingStart = -1; composingEnd = -1
        metadataUnsupported = false; ambiguous = false
    }

    fun seed(suffix: String, complete: Boolean) {
        if (pending) return
        observed = predicted.copy(suffix = suffix, complete = complete)
        predicted = observed
    }

    fun submitted(snapshot: Snapshot, units: Int, word: Boolean, now: Long) {
        val forwardTyping = units == 0 && predicted.start >= 0 && predicted.start == predicted.end &&
            snapshot.start == snapshot.end && snapshot.start > predicted.start
        if (forwardTyping) {
            val previous = typingRuns.lastOrNull()
            if (previous?.last == predicted.start) typingRuns.removeLast()
            typingRuns.addLast((if (previous?.last == predicted.start) previous.first else predicted.start)..snapshot.start)
            while (typingRuns.size > 32) typingRuns.removeFirst()
            if (edits.lastOrNull()?.units == 0) edits.removeLast()
        }
        predicted = snapshot
        edits.addLast(Edit(snapshot, units, word, now))
        if (edits.size > 32) {
            edits.removeFirst()
            ambiguous = true
        }
        recent.addLast(snapshot)
        while (recent.size > 32) recent.removeFirst()
    }

    fun plausible(start: Int, end: Int): Boolean =
        observed.start == start && observed.end == end ||
            predicted.start == start && predicted.end == end ||
            recent.any { it.start == start && it.end == end } ||
            start == end && typingRuns.any { start in it }

    fun locateUnknownDelete(start: Int, end: Int): Boolean {
        val edit = edits.singleOrNull() ?: return false
        if (edit.units != 1 || edit.after.start >= 0 || start != end || observed.start < 0 || observed.start != observed.end ||
            observed.start - start !in 1..2) return false
        val located = edit.after.copy(start = start, end = end)
        edits.clear()
        edits.addLast(edit.copy(after = located))
        predicted = located
        recent.addLast(located)
        while (recent.size > 32) recent.removeFirst()
        ambiguous = true
        return true
    }

    /**
     * A field that cannot answer reads reports a blind delete only through its new cursor. Accept a
     * collapsed cursor one code point (1..2 units) behind the previous one as the oldest pending
     * blind delete landing, so a held backspace keeps its credit. The previous cursor is the host's
     * own [oldStart] when it gives one, else the last observed cursor; with neither, the report is
     * accepted, since a blind delete removes text wherever the cursor is.
     */
    fun acknowledgeBlindDelete(oldStart: Int, oldEnd: Int, start: Int, end: Int): Boolean {
        val edit = edits.firstOrNull() ?: return false
        if (start != end || start < 0 || edit.units == 0 || edit.after.start >= 0 || edit.after.suffix != null) return false
        val previous = if (oldStart >= 0) Snapshot(oldStart, oldEnd, null) else observed
        if (previous.start >= 0 && (previous.start != previous.end || previous.start - start !in 1..2)) return false
        edits.removeFirst()
        observed = Snapshot(start, end, null)
        if (edits.isEmpty()) predicted = observed
        recent.addLast(observed)
        while (recent.size > 32) recent.removeFirst()
        ambiguous = false
        return true
    }

    fun acknowledgeTyping(oldStart: Int, oldEnd: Int, start: Int, end: Int): Boolean {
        if (start != end || oldStart < 0 || !plausible(oldStart, oldEnd)) return false
        val index = edits.indexOfLast { it.units == 0 && it.after.start == start && it.after.end == end }
        if (index < 0 || edits.take(index + 1).any { it.units != 0 }) return false
        repeat(index + 1) { observed = edits.removeFirst().after }
        return true
    }

    fun observe(value: Snapshot): Evidence {
        if (!pending) {
            if (value.start >= 0 && predicted.start >= 0 && (value.start != predicted.start || value.end != predicted.end)) return Evidence.INCOMPATIBLE
            observed = value
            predicted = value
            ambiguous = false
            return Evidence.CURRENT
        }
        fun matches(expected: Snapshot): Boolean {
            val absolute = value.start >= 0 && expected.start >= 0
            if (absolute && (value.start != expected.start || value.end != expected.end)) return false
            val suffix = expected.suffix ?: return absolute
            val actual = value.suffix ?: return false
            return if (expected.complete) actual == suffix else (absolute || suffix.isNotEmpty()) && actual.endsWith(suffix)
        }
        val matches = edits.withIndex().filter { matches(it.value.after) }
        val baseMatches = matches(observed)
        if (matches.size != 1 || baseMatches) {
            return if (value.start >= 0 && !plausible(value.start, value.end)) Evidence.INCOMPATIBLE else Evidence.WAITING
        }
        val index = matches.single().index
        repeat(index + 1) { edits.removeFirst() }
        observed = value
        ambiguous = false
        if (edits.isEmpty()) {
            predicted = value
            return Evidence.CURRENT
        }
        return Evidence.PROGRESS
    }
}
