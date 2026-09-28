package app.odicto.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.odicto.mobile.dictation.DictationCoordinator
import app.odicto.mobile.dictation.DictationTarget
import app.odicto.mobile.ime.EditorPolicy
import app.odicto.mobile.overlay.VoiceOverlayController
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** How long an unknown focus may persist during a window transition before the session is closed. */
private const val FOCUS_GRACE_MS = 350L

/** How long the secure input-method setting is trusted before it is read again. */
private const val IME_CHECK_MS = 500L

/** Node budget for the debug-only probe that looks for an unreported editor. */
private const val EDITABLE_PROBE_LIMIT = 200

/**
 * Fallback insertion channel for when a non-Odicto keyboard is the active input method.
 * It only tracks focus and inserts text; it stands down whenever the Odicto IME owns the editor.
 */
class OdictoAccessibilityService : AccessibilityService() {
    private var focusKey: String? = null
    private var imeSelected = false
    private var imeCheckedAt = 0L
    /** Per-event chatter is useful while developing and noise in a release log. */
    private val verbose by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private val handler = Handler(Looper.getMainLooper())
    private var pendingClose: Runnable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val target by lazy {
        AccessibilityTarget(::focusedEditor, { evaluate(focusedEditor()) }, getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        VoicePreferences.initialize(this)
        DictationCoordinator.registerTarget(target)
        // App updates and OEM cleanup leave the overlay dead; this system-bound service is the reliable resurrection path.
        VoiceOverlayController.sync(this, scope)
        // Protected apps refuse to run while an accessibility service is enabled, so a pause disables this
        // service. Android requires the user to turn it back on; the tile and app deep-link to that screen.
        scope.launch { VoicePreferences.state.collect { if (it.paused) disableSelf() } }
        // A restarted process can connect while a field is already focused, which emits no focus event.
        evaluate(focusedEditor())
    }

    override fun onDestroy() {
        close()
        DictationCoordinator.unregisterTarget(DictationTarget.ACCESSIBILITY)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (verbose) android.util.Log.i("OdictoA11y", "event type=$type pkg=${event.packageName}")
        if (AccessibilityEventPolicy.readsSelection(type, DictationCoordinator.aiSelection != null)) {
            event.source?.let { DictationCoordinator.selectionUpdated(it.textSelectionStart, it.textSelectionEnd) }
        }
        if (!AccessibilityEventPolicy.needsFocusScan(type, DictationCoordinator.activeEditorSession != null)) return
        // Only the live input focus of the active window may open or keep a session: an event source
        // from a field being torn down (X saves the compose draft on close) would resurrect a dead editor.
        // Cursor moves arrive per keystroke, so the editor scan they would trigger is skipped for them.
        evaluate(focusedEditor(allowScan = type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED))
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        close()
        DictationCoordinator.unregisterTarget(DictationTarget.ACCESSIBILITY)
        return super.onUnbind(intent)
    }

    private fun evaluate(node: AccessibilityNodeInfo?) {
        // The app WebView can report itself as an editable input with inputType=0. It is not a
        // system-wide destination, and accepting it here would reopen a session while closing a
        // stale external editor causes the overlay to refresh.
        val ownApp = node?.packageName?.toString() == packageName
        val reason = when {
            ownApp -> "own-app"
            node == null -> "no-focused-node"
            else -> EditorDecision.reason(node)
        }
        val eligible = node != null && reason == EditorPolicy.OK
        if (verbose) android.util.Log.i("OdictoA11y", "evaluate node=${node != null} reason=$reason eligible=$eligible focusKey=$focusKey session=${DictationCoordinator.activeEditorSession} ime=${DictationCoordinator.imeAttached}")
        if (DictationCoordinator.imeAttached || odictoImeSelected()) { close(); return }
        when {
            eligible -> {
                settle()
                val next = keyOf(node!!)
                if (next != focusKey) {
                    if (DictationCoordinator.activeEditorSession != null) DictationCoordinator.editorClosed()
                    DictationCoordinator.editorReady()
                    focusKey = next
                }
            }
            // Focus is unknown while a window transition settles; verify once instead of keeping a stale session.
            node == null -> deferClose()
            else -> close(protected = node.isEditable || node.isPassword)
        }
    }

    /**
     * A field that no longer reports focus must not keep the microphone ready on a screen with
     * nowhere to write. The check is deferred so a brief null during a window switch cannot flicker.
     */
    private fun deferClose() {
        if (pendingClose != null || focusKey == null) return
        android.util.Log.i("OdictoA11y", "deferClose scheduled")
        pendingClose = Runnable {
            pendingClose = null
            if (DictationCoordinator.imeAttached || odictoImeSelected()) { close(); return@Runnable }
            val node = focusedEditor()
            if (node != null && EditorPolicy.permitsNode(node.inputType, node.isPassword, node.isEditable)) evaluate(node)
            else close(protected = node?.isEditable == true || node?.isPassword == true)
        }.also { handler.postDelayed(it, FOCUS_GRACE_MS) }
    }

    private fun settle() { pendingClose?.let { handler.removeCallbacks(it) }; pendingClose = null }

    private fun close(protected: Boolean = false) {
        settle()
        android.util.Log.i("OdictoA11y", "close protected=$protected hadSession=${focusKey != null}")
        // Clear the session off any field, and never leave a protected flag stuck after leaving a secure field.
        val hadSession = focusKey != null
        focusKey = null
        // When Odicto owns the editor, its IME session is authoritative. The accessibility service
        // must stand down without closing that session while the two services transition.
        val imeOwnsEditor = DictationCoordinator.imeAttached || odictoImeSelected()
        if (!imeOwnsEditor && (hadSession || protected || DictationCoordinator.protectedField.value)) {
            DictationCoordinator.editorClosed(protected)
        }
    }

    // The IME flag is authoritative once it reports input; the secure setting closes the gap before that.
    // Reading it is a provider call, so the answer is reused briefly instead of once per event.
    private fun odictoImeSelected(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - imeCheckedAt < IME_CHECK_MS) return imeSelected
        imeCheckedAt = now
        imeSelected = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith(packageName + "/")
        } catch (_: Exception) { false }
        return imeSelected
    }

    private fun focusedEditor(): AccessibilityNodeInfo? = focusedEditor(allowScan = true)

    private fun focusedEditor(allowScan: Boolean): AccessibilityNodeInfo? = try {
        val active = rootInActiveWindow
        active?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: focusedWindowEditor(active, allowScan)
    } catch (_: Exception) { null }

    /**
     * Chrome and other multi-window apps keep the edited field in a window other than the active one,
     * so the active root alone reports no focus. Only a window that holds input focus may answer:
     * a background window must never become the insertion target.
     */
    private fun focusedWindowEditor(active: AccessibilityNodeInfo?, allowScan: Boolean): AccessibilityNodeInfo? {
        val focused = windows.firstOrNull { it.isFocused }
        val root = focused?.root ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        if (!allowScan) return null
        val editors = editableNodes(root)
        val chosen = editors.firstOrNull { it.isFocused } ?: editors.singleOrNull()
        if (verbose) android.util.Log.i("OdictoA11y", "focus miss active=${active?.packageName} windows=${windows.size} focused=${root.packageName} editors=${editors.size} chosen=${chosen != null}")
        return chosen
    }

    /** Bounded scan for editors in a window, used only when the focus itself cannot be read. */
    private fun editableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val found = mutableListOf<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < EDITABLE_PROBE_LIMIT) {
            val node = queue.removeFirst(); visited++
            if (node.isEditable) found.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { queue.addLast(it) }
        }
        return found
    }

    private fun keyOf(node: AccessibilityNodeInfo): String = EditorIdentity.key(
        node.windowId,
        if (Build.VERSION.SDK_INT >= 33) node.uniqueId else null,
        node.viewIdResourceName,
        node.className?.toString(),
    )
}
