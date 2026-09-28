package app.odicto.mobile.ime

/**
 * Cursor movement arithmetic over editor text, kept free of Android types so every edge is testable.
 *
 * Movement is expressed as a new selection range rather than as key events, because editors disagree
 * about what a DPAD key means. Word boundaries use Unicode word-break rules so a jump lands where a
 * reader expects instead of at an arbitrary character class change.
 */
internal object CursorNavigator {
    /**
     * Moves [steps] words, clamped to the text. A negative count moves left.
     * Returns the resulting (start, end) pair, or null when there is nothing to do.
     */
    fun word(text: String, selectionStart: Int, selectionEnd: Int, steps: Int): Pair<Int, Int>? {
        if (steps == 0 || text.isEmpty()) return null
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        val anchor = if (end > start) start else end
        val target = if (steps > 0) wordForward(text, anchor, steps) else wordBackward(text, anchor, -steps)
        return target to target
    }

    /**
     * Extends an existing selection by [steps] words. Positive extends to the right, negative to the
     * left, and the moving edge never crosses the fixed one.
     */
    fun extendWords(text: String, selectionStart: Int, selectionEnd: Int, steps: Int): Pair<Int, Int>? {
        if (steps == 0 || text.isEmpty()) return null
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        if (steps > 0) {
            val moved = wordForward(text, end, steps)
            return start to moved.coerceAtLeast(start)
        }
        val moved = wordBackward(text, start, -steps)
        return moved.coerceAtMost(end) to end
    }

    /** Moves [steps] grapheme clusters without ever splitting a surrogate pair or a combining sequence. */
    fun character(text: String, selectionStart: Int, selectionEnd: Int, steps: Int): Pair<Int, Int>? {
        if (steps == 0 || text.isEmpty()) return null
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        if (end > start) {
            // A live selection collapses to its far edge before it starts walking, which is how every
            // editor the user already knows behaves: the words stay put and the cursor leaves them.
            val moved = shiftCluster(text, end, steps)
            return moved to moved
        }
        val moved = shiftCluster(text, end, steps)
        return moved to moved
    }

    fun characterInWindow(text: String, startOffset: Int, cursor: Int, steps: Int): Pair<Int, Int>? {
        if (startOffset < 0 || cursor < startOffset || cursor > startOffset + text.length) return null
        val moved = character(text, cursor - startOffset, cursor - startOffset, steps) ?: return null
        return (startOffset + moved.first) to (startOffset + moved.second)
    }

    fun extendCharacters(text: String, selectionStart: Int, selectionEnd: Int, steps: Int): Pair<Int, Int>? {
        if (steps == 0 || text.isEmpty()) return null
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        if (steps > 0) {
            val moved = shiftCluster(text, end, steps)
            return start to moved.coerceAtLeast(start)
        }
        val moved = shiftCluster(text, start, steps)
        return moved.coerceAtMost(end) to end
    }

    /** One character means one grapheme cluster, not one UTF-16 unit. */
    private fun shiftCluster(text: String, from: Int, steps: Int): Int {
        var at = from
        var remaining = steps
        while (remaining > 0) {
            if (at >= text.length) return text.length
            at = forwardByOneCluster(text, at)
            remaining--
        }
        while (remaining < 0) {
            if (at <= 0) return 0
            at = backwardByOneCluster(text, at)
            remaining++
        }
        return at
    }

    /** Moves forward to the start of the next word, crossing any separators after it. */
    private fun nextBreak(text: String, from: Int): Int {
        if (from >= text.length) return text.length
        var index = from
        // Leave any run of separators, then take the word, then leave the space after it: a step
        // should land where the next word begins, not in the middle of a separator run.
        while (index < text.length && !isWordChar(text, index)) index = forwardByOneCluster(text, index)
        while (index < text.length && isWordChar(text, index)) index = forwardByOneCluster(text, index)
        while (index < text.length && !isWordChar(text, index)) index = forwardByOneCluster(text, index)
        return index.coerceIn(from, text.length)
    }

    /** Moves back to the start of the word before [from]. */
    private fun previousBreak(text: String, from: Int): Int {
        if (from <= 0) return 0
        // Step off the current character first, otherwise a cursor already sitting on a word would
        // report no movement and a repeated left-press would stall.
        var index = backwardByOneCluster(text, from)
        // Leave the separators, then leave the word, landing on its first character.
        while (index > 0 && !isWordCharBefore(text, index)) index = backwardByOneCluster(text, index)
        while (index > 0 && isWordCharBefore(text, index)) index = backwardByOneCluster(text, index)
        return index.coerceIn(0, from)
    }

    private fun isWordChar(text: String, index: Int): Boolean {
        if (index < 0 || index >= text.length) return false
        return Character.isLetterOrDigit(text.codePointAt(index))
    }

    private fun isWordCharBefore(text: String, index: Int): Boolean {
        if (index <= 0 || index > text.length) return false
        val before = text.codePointBefore(index)
        return Character.isLetterOrDigit(before)
    }

    /** One grapheme forward: past a combining mark, a ZWJ sequence, and a surrogate pair. */
    private fun forwardByOneCluster(text: String, from: Int): Int {
        var index = from
        val first = codePointAt(text, index)
        index += Character.charCount(first)
        if (first == 13 && index < text.length && text[index] == '\n') return index + 1
        if (isRegionalIndicator(first) && index < text.length && isRegionalIndicator(text.codePointAt(index))) {
            return index + Character.charCount(text.codePointAt(index))
        }
        while (index < text.length) {
            val next = codePointAt(text, index)
            val continues = next == 0x200D || Character.getType(next).toByte() == Character.NON_SPACING_MARK.toByte() ||
                Character.getType(next).toByte() == Character.COMBINING_SPACING_MARK.toByte() ||
                next in 0x1F3FB..0x1F3FF || Character.getType(next) == Character.ENCLOSING_MARK.toInt()
            if (!continues) break
            index += Character.charCount(next)
            if (next == 0x200D && index < text.length) index += Character.charCount(codePointAt(text, index))
        }
        return index.coerceAtMost(text.length)
    }

    /** One grapheme backward, used for a leftward step. */
    private fun backwardByOneCluster(text: String, from: Int): Int {
        if (from <= 0) return 0
        var index = 0
        var previous = 0
        while (index < from) {
            previous = index
            index = forwardByOneCluster(text, index)
        }
        return previous
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

    private fun codePointAt(text: String, index: Int): Int {
        if (index < 0 || index >= text.length) return -1
        return text.codePointAt(index)
    }

    private fun wordForward(text: String, from: Int, steps: Int): Int {
        var at = from
        repeat(steps) { at = nextBreak(text, at) }
        return at
    }

    private fun wordBackward(text: String, from: Int, steps: Int): Int {
        var at = from
        repeat(steps) { at = previousBreak(text, at) }
        return at
    }
}
