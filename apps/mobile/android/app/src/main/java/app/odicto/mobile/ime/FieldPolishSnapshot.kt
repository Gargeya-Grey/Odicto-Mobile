package app.odicto.mobile.ime

import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

data class FieldPolishSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    val hasSelection: Boolean get() = selectionStart != selectionEnd
    val rangeStart: Int get() = if (hasSelection) minOf(selectionStart, selectionEnd) else 0
    val rangeEnd: Int get() = if (hasSelection) maxOf(selectionStart, selectionEnd) else text.length
    val payload: String get() = text.substring(rangeStart, rangeEnd)

    fun replacementSelection(revised: String): Pair<Int, Int> = when {
        !hasSelection -> FieldPolishSnapshotReader.mapSelection(this, revised)
        selectionStart > selectionEnd -> rangeStart + revised.length to rangeStart
        else -> rangeStart to rangeStart + revised.length
    }
}

internal object FieldPolishSnapshotReader {
    const val MAX_CHARS = 20_000

    fun capture(connection: InputConnection): FieldPolishSnapshot? {
        val original = read(connection) ?: return null
        return try {
            val before = connection.getTextBeforeCursor(MAX_CHARS + 1, 0)?.toString() ?: return null
            val selected = if (original.selectionStart == original.selectionEnd) ""
                else connection.getSelectedText(0)?.toString() ?: return null
            val after = connection.getTextAfterCursor(MAX_CHARS + 1, 0)?.toString() ?: return null
            if (before.length != minOf(original.selectionStart, original.selectionEnd) ||
                selected.length != maxOf(original.selectionStart, original.selectionEnd) - minOf(original.selectionStart, original.selectionEnd) ||
                before + selected + after != original.text) return null
            read(connection)?.takeIf { it == original }
        } catch (_: Exception) { null }
    }

    private fun read(connection: InputConnection): FieldPolishSnapshot? {
        return try {
            val request = ExtractedTextRequest().apply {
                hintMaxChars = MAX_CHARS + 1
                flags = InputConnection.GET_TEXT_WITH_STYLES
            }
            val extracted = connection.getExtractedText(request, 0) ?: return null
            val content = extracted.text ?: return null
            if (content is Spanned && content.getSpans(0, content.length, Any::class.java)
                    .any { it is CharacterStyle || it is ParagraphStyle }) return null
            val text = content.toString()
            if (text.isEmpty() || text.length > MAX_CHARS || extracted.startOffset != 0 || extracted.partialStartOffset != -1 ||
                extracted.selectionStart !in 0..text.length || extracted.selectionEnd !in 0..text.length) return null
            fun splitsSurrogate(at: Int) = at > 0 && at < text.length &&
                text[at - 1].isHighSurrogate() && text[at].isLowSurrogate()
            if (splitsSurrogate(extracted.selectionStart) || splitsSurrogate(extracted.selectionEnd)) return null
            FieldPolishSnapshot(text, extracted.selectionStart, extracted.selectionEnd)
        } catch (_: Exception) { null }
    }

    fun mapSelection(original: FieldPolishSnapshot, revised: String): Pair<Int, Int> {
        val source = original.text
        var prefix = 0
        while (prefix < source.length && prefix < revised.length && source[prefix] == revised[prefix]) prefix++
        var suffix = 0
        while (suffix < source.length - prefix && suffix < revised.length - prefix &&
            source[source.length - suffix - 1] == revised[revised.length - suffix - 1]) suffix++
        fun mapped(position: Int, forward: Boolean): Int {
            val raw = when {
                position <= prefix -> position
                position >= source.length - suffix -> revised.length - (source.length - position)
                forward -> revised.length - suffix
                else -> prefix
            }
            return boundary(revised, raw.coerceIn(0, revised.length), forward)
        }
        if (original.selectionStart == 0 && original.selectionEnd == source.length) return 0 to revised.length
        val selection = original.selectionEnd > original.selectionStart
        val start = mapped(original.selectionStart, !selection)
        val end = if (selection) mapped(original.selectionEnd, true) else start
        return minOf(start, end) to maxOf(start, end)
    }

    private fun boundary(text: String, target: Int, forward: Boolean): Int {
        var at = 0
        while (at < target) {
            val next = CursorNavigator.character(text, at, at, 1)?.first ?: break
            if (next >= target) return if (next == target || forward) next else at
            at = next
        }
        return target
    }
}
