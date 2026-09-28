package app.odicto.mobile.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import app.odicto.mobile.ime.EditorPolicy

/**
 * One decision for a node, shared by session tracking and by insertion, so a field that opens a
 * session can never be refused later when the transcript arrives. The node's own capabilities are
 * the evidence: Compose and custom editors often report no input class and no editable flag.
 */
internal object EditorDecision {
    fun reason(node: AccessibilityNodeInfo): String = EditorPolicy.nodeReason(
        inputType = node.inputType,
        isPassword = node.isPassword,
        isEditable = node.isEditable,
        isFocused = node.isFocused,
        supportsText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
        className = node.className?.toString(),
    )

    fun permits(node: AccessibilityNodeInfo): Boolean = reason(node) == EditorPolicy.OK
}
