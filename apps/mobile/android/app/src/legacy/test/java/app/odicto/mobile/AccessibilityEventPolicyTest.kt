package app.odicto.mobile

import android.view.accessibility.AccessibilityEvent
import app.odicto.mobile.accessibility.AccessibilityEventPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventPolicyTest {
    @Test fun selectionChangesSkipTheFocusScanWhileASessionIsOpen() {
        assertFalse(AccessibilityEventPolicy.needsFocusScan(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, hasSession = true))
        assertTrue(AccessibilityEventPolicy.needsFocusScan(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, hasSession = false))
    }

    @Test fun focusAndWindowEventsAlwaysScan() {
        assertTrue(AccessibilityEventPolicy.needsFocusScan(AccessibilityEvent.TYPE_VIEW_FOCUSED, hasSession = true))
        assertTrue(AccessibilityEventPolicy.needsFocusScan(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, hasSession = true))
    }

    @Test fun selectionBoundsAreOnlyReadWhileAnAiSelectionIsPending() {
        assertTrue(AccessibilityEventPolicy.readsSelection(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, hasAiSelection = true))
        assertFalse(AccessibilityEventPolicy.readsSelection(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, hasAiSelection = false))
        assertFalse(AccessibilityEventPolicy.readsSelection(AccessibilityEvent.TYPE_VIEW_FOCUSED, hasAiSelection = true))
    }
}
