package app.odicto.mobile.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * Event gating for the accessibility hot path. Selection changes arrive on every cursor move, and
 * resolving the focused editor is a cross-process read, so neither belongs on every event.
 */
internal object AccessibilityEventPolicy {
    /** A selection change only needs the focus scan while no editor session is open. */
    fun needsFocusScan(eventType: Int, hasSession: Boolean): Boolean =
        eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED || !hasSession

    /** Selection bounds come from the event source and only matter while an AI rewrite is pending. */
    fun readsSelection(eventType: Int, hasAiSelection: Boolean): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED && hasAiSelection
}
