package app.odicto.mobile.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import app.odicto.mobile.dictation.AiSelection
import app.odicto.mobile.dictation.DictationTarget
import app.odicto.mobile.ime.EditorPolicy

/**
 * Inserts into the focused editor while a non-Odicto keyboard is active.
 * The node is re-read on every call so a stale reference can never type into the wrong field.
 */
class AccessibilityTarget(
    private val focus: () -> AccessibilityNodeInfo?,
    private val sync: () -> Unit,
    private val clipboard: ClipboardManager,
) : DictationTarget {
    override val id = DictationTarget.ACCESSIBILITY
    override val identity: Any get() = permittedNode() ?: this
    override val selectionVersion: Long get() {
        val node = permittedNode() ?: return -1
        return (node.textSelectionStart.toLong() shl 32) xor (node.textSelectionEnd.toLong() and 0xffffffffL)
    }

    override fun refresh() = sync()

    override fun readSelection(): AiSelection? {
        val node = permittedNode() ?: return null
        val rawText = node.text?.toString().orEmpty()
        // Empty fields and search-style placeholders have no exposed selection,
        // but they are still valid AI insertion targets.
        val text = rawText.takeUnless { isPlaceholderLikeText(node, it) }.orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return AiSelection(0, 0, "").takeIf { text.isEmpty() }
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        return AiSelection(from, to, text.substring(from, to)).takeIf { it.valid }
    }

    override fun insert(text: String): Boolean {
        val node = permittedNode() ?: return false
        val rawExisting = node.text?.toString()
        // Some editors expose their empty-field prompt through text instead of hintText.
        // Treat that prompt as empty so ACTION_SET_TEXT replaces it instead of appending to it.
        val existing = rawExisting?.takeUnless { isPlaceholderLikeText(node, it) }
        val merged = TextSplice.merge(existing, node.textSelectionStart, node.textSelectionEnd, text) ?: text
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
            })) {
            if (existing != null) {
                val caret = TextSplice.caret(node.textSelectionStart, existing.length, text.length)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
                })
            }
            return true
        }
        // A few custom editors only support paste. Use the clipboard briefly for those
        // fields, then restore whatever the user had there before dictation.
        val previous = clipboard.primaryClip
        val temporary = ClipData.newPlainText("Odicto", text)
        clipboard.setPrimaryClip(temporary)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Handler(Looper.getMainLooper()).postDelayed({
            val current = clipboard.primaryClip
            val stillTemporary = current?.description?.label == temporary.description.label &&
                current.itemCount == 1 && current.getItemAt(0).text?.toString() == text
            if (!stillTemporary) return@postDelayed
            if (previous != null) clipboard.setPrimaryClip(previous)
            else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }, 150L)
        return pasted
    }

    override fun selectAll(): Boolean {
        val node = permittedNode() ?: return false
        val text = node.text?.toString() ?: return false
        if (text.isEmpty()) return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            })
        } catch (_: Exception) { false }
    }

    private fun permittedNode(): AccessibilityNodeInfo? = focus()?.takeIf { EditorDecision.permits(it) }

    private fun nodeTextOrNull(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: return null
        if (isPlaceholderLikeText(node, text)) return null
        return text
    }

    private fun isPlaceholderLikeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (text.isBlank()) return true
        val trimmed = text.trim()
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (hint.isNotBlank() && trimmed.equals(hint, ignoreCase = true)) return true
        if (description.isNotBlank() && trimmed.equals(description, ignoreCase = true) && trimmed.length < 120) return true
        val normalized = trimmed.lowercase().replace(Regex("\\s+"), " ")
        if (normalized in setOf(
            "ask google",
            "ask anything",
            "ask me anything",
            "search",
            "search here",
            "type a message",
            "write a message",
            "start a chat",
        )) return true
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return false
        return start == end && (start == 0 || start == trimmed.length) && normalized.endsWith("…")
    }
}
