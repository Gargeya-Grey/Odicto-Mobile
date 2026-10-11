package app.odicto.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parent-operated, generated-query trial in the already focused Google search field.
 * No activity launch, search submission, provider/voice action, screenshot, or host text write.
 * All input uses exact semantic actions in Odicto's IME; output contains only Boolean checks.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSearchAutocorrectDeviceTest {
    @Test fun emptyFocusedGoogleSearchCorrectsOnSpaceAndUndoRestores() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val checks = Bundle()
        var trial: GoogleTrial? = null
        var automation: UiAutomation? = null
        var originalFlags: Int? = null
        var passed = false
        try {
            VoicePreferences.initialize(instrumentation.targetContext)
            val ready = withTimeout(5_000) { VoicePreferences.ready.first { it } }
            checks.putBoolean("preferencesReady", ready)
            val enabled = VoicePreferences.state.value.keys.autocorrectEnabled
            checks.putBoolean("autocorrectEnabled", enabled)
            if (ready && enabled) {
                val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                automation = ui
                val info = ui.serviceInfo
                originalFlags = info.flags
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                ui.serviceInfo = info
                val runTrial = GoogleTrial(ui, checks)
                trial = runTrial
                passed = runTrial.run()
            }
        } catch (_: Exception) {
            checks.putBoolean("unexpectedError", true)
        } finally {
            val cleanup = runCatching { trial?.cleanup() ?: true }.getOrDefault(false)
            checks.putBoolean("cleanupSafe", cleanup)
            passed = passed && cleanup
            runCatching { trial?.close() }
            val uiToRestore = automation
            val flagsToRestore = originalFlags
            if (uiToRestore != null && flagsToRestore != null) runCatching {
                val info = uiToRestore.serviceInfo
                info.flags = flagsToRestore
                uiToRestore.serviceInfo = info
            }
            checks.putBoolean("passed", passed)
            instrumentation.sendStatus(0, checks)
        }
        assertTrue("Focused empty Google search autocorrect and guarded cleanup passed", passed)
    }

    @Suppress("DEPRECATION")
    private class GoogleTrial(private val ui: UiAutomation, private val checks: Bundle) {
        private var anchor: AccessibilityNodeInfo? = null
        private var generatedAttempted = false
        private var expandedByTrial = false
        private val generatedStates = setOf("", "t", "T", "te", "Te", "teh", "Teh", "teh ", "Teh ", "th", "Th", "the", "The", "the ", "The ")

        fun run(): Boolean {
            // Interactive-window delivery is asynchronous just after UiAutomation connects.
            val focusDeadline = SystemClock.uptimeMillis() + 3_000
            do {
                anchor = focusedGoogleField()
                if (anchor != null) break
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < focusDeadline)
            if (!check("focusedGoogleField", anchor != null)) return false
            if (!check("initialFieldEmpty", state(setOf("")) == "")) return false
            if (!check("keyboardPresentationReady", awaitPresentation())) return false
            if (!hasKey("Hide keyboard")) {
                expandedByTrial = true
                if (!check("expandedBySemanticAction", clickKey("Show keyboard", setOf("")))) return false
            }
            if (!check("typingKeysReady", awaitKey("t", setOf("")) && hasKey("Hide keyboard"))) return false
            if (!clickKey("t", setOf(""), generatedInput = true)) return check("firstLetterApplied", false)
            val first = awaitState(setOf(""), setOf("t", "T")) ?: return check("firstLetterApplied", false)
            check("firstLetterApplied", true)
            if (!clickKey("e", setOf(first), generatedInput = true)) return check("secondLetterApplied", false)
            val second = first + "e"
            if (!check("secondLetterApplied", awaitState(setOf(first), setOf(second)) == second)) return false
            if (!clickKey("h", setOf(second), generatedInput = true)) return check("thirdLetterApplied", false)
            val typed = second + "h"
            if (!check("beforeSpaceUnchanged", awaitState(setOf(second), setOf(typed)) == typed)) return false
            if (!check("spaceActivated", clickKey("Space", setOf(typed)))) return false
            val corrected = if (first == "T") "The " else "the "
            if (!check("spaceCorrected", awaitState(setOf(typed, "$typed "), setOf(corrected)) == corrected)) return false
            if (!check("undoAvailable", awaitKey("Undo autocorrect", setOf(corrected)))) return false
            if (!check("undoActivated", clickKey("Undo autocorrect", setOf(corrected)))) return false
            return check("undoRestored", awaitState(setOf(corrected), setOf(typed)) == typed)
        }

        /** Deletes only exact generated states from the original still-focused node, one key at a time. */
        fun cleanup(): Boolean {
            var safe = true
            if (generatedAttempted) {
                var current = state(generatedStates)
                var attempts = 0
                while (current != null && current.isNotEmpty() && attempts++ < 6) {
                    if (!clickKey("Backspace", setOf(current))) { safe = false; break }
                    val ordinary = current.dropLast(1)
                    // Backspace may first undo a confirmed correction before ordinary deletion.
                    val next = mutableSetOf(ordinary)
                    if (current == "The ") next.add("Teh")
                    if (current == "the ") next.add("teh")
                    current = awaitState(setOf(current), next)
                }
                safe = safe && current == ""
                check("generatedTextRemoved", safe)
            }
            if (expandedByTrial) {
                // A changed field is never touched, including presentation actions during cleanup.
                val permitted = if (generatedAttempted) generatedStates else setOf("")
                val sameField = state(permitted) != null
                val collapsed = sameField && (hasKey("Show keyboard") ||
                    clickKey("Hide keyboard", permitted) && awaitKey("Show keyboard", permitted))
                check("originalCollapseRestored", collapsed)
                safe = safe && collapsed
            }
            return safe
        }

        fun close() { anchor?.recycle(); anchor = null }

        private fun check(name: String, value: Boolean): Boolean {
            checks.putBoolean(name, value)
            return value
        }

        /** No other app tree is traversed: findFocus is used only on the focused Google window. */
        private fun focusedGoogleField(): AccessibilityNodeInfo? {
            if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
            var found: AccessibilityNodeInfo? = null
            var ambiguous = false
            for (window in ui.windows) {
                try {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused) continue
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() != GOOGLE) continue
                        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                        val normalText = field.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                            field.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_NORMAL
                        if (field.packageName?.toString() == GOOGLE && field.isFocused && field.isEditable &&
                            field.isEnabled && field.isVisibleToUser && !field.isPassword && normalText) {
                            if (found == null) found = field else { field.recycle(); ambiguous = true }
                        } else field.recycle()
                    } finally { root.recycle() }
                } finally { window.recycle() }
            }
            if (ambiguous) { found?.recycle(); return null }
            return found
        }

        /** Returns a known fixture value, never a copy of unknown or personal content. */
        private fun state(allowed: Set<String>): String? {
            val expected = anchor ?: return null
            val current = focusedGoogleField() ?: return null
            try {
                if (!checks.containsKey("initialNodeIdentityStable")) {
                    checks.putBoolean("initialNodeIdentityStable", current == expected)
                    checks.putBoolean("initialNodeTextNull", current.text == null)
                    checks.putBoolean("initialNodeTextEmpty", current.text?.isEmpty() == true)
                    checks.putBoolean("initialNodeShowingHint", current.isShowingHintText)
                    checks.putBoolean("initialNodeHintMatchesText", current.hintText != null && current.hintText.toString() == current.text?.toString())
                    checks.putBoolean("initialNodeCaretAtZero", current.textSelectionStart == 0 && current.textSelectionEnd == 0)
                    checks.putBoolean("initialNodeCaretUnknown", current.textSelectionStart < 0 || current.textSelectionEnd < 0)
                }
                if (current != expected) return null
                // Google exposes its empty field as the hint, with selection -1/-1. An explicit
                // hint matching the reported text proves emptiness; unreadable text alone does not.
                val emptyCaret = (current.textSelectionStart == 0 && current.textSelectionEnd == 0) ||
                    (current.textSelectionStart == -1 && current.textSelectionEnd == -1)
                if (current.isShowingHintText && current.hintText != null &&
                    current.hintText.toString() == current.text?.toString() && emptyCaret) {
                    return "".takeIf { it in allowed }
                }
                val text = current.text ?: return null
                if (text.length > 4 || current.textSelectionStart != text.length || current.textSelectionEnd != text.length) return null
                return allowed.firstOrNull { it.contentEquals(text) }
            } finally { current.recycle() }
        }

        private fun awaitState(before: Set<String>, after: Set<String>): String? {
            val deadline = SystemClock.uptimeMillis() + 3_000
            do {
                val value = state(before + after) ?: return null
                if (value in after) return value
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            return null
        }

        private fun awaitKey(label: String, before: Set<String>): Boolean {
            val deadline = SystemClock.uptimeMillis() + 3_000
            do {
                if (state(before) == null) return false
                if (hasKey(label)) return true
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            return false
        }

        private fun awaitPresentation(): Boolean {
            val deadline = SystemClock.uptimeMillis() + 3_000
            do {
                if (state(setOf("")) == null) return false
                if (hasKey("Show keyboard") || hasKey("Hide keyboard")) return true
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            return false
        }

        private fun hasKey(label: String): Boolean = key(label)?.let { it.recycle(); true } ?: false

        private fun clickKey(label: String, before: Set<String>, generatedInput: Boolean = false): Boolean {
            if (label !in ACTIONS || state(before) == null) return false
            val target = key(label) ?: return false
            try {
                if (state(before) == null) return false
                if (generatedInput) generatedAttempted = true
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally { target.recycle() }
        }

        /** Content descriptions only, bounded to visible Odicto IME nodes; never application text. */
        private fun key(label: String): AccessibilityNodeInfo? {
            if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
            val matches = mutableListOf<AccessibilityNodeInfo>()
            var remaining = 2_048
            fun visit(node: AccessibilityNodeInfo) {
                if (--remaining < 0) return
                if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    label.contentEquals(node.contentDescription ?: "")) matches.add(AccessibilityNodeInfo.obtain(node))
                for (index in 0 until node.childCount) {
                    if (remaining < 0) break
                    node.getChild(index)?.let { child -> try { visit(child) } finally { child.recycle() } }
                }
            }
            for (window in ui.windows) {
                try {
                    if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val root = window.root ?: continue
                    try { if (root.packageName?.toString() == ODICTO) visit(root) }
                    finally { root.recycle() }
                } finally { window.recycle() }
            }
            if (remaining >= 0 && matches.size == 1) return matches.single()
            matches.forEach { it.recycle() }
            return null
        }

        companion object {
            private const val GOOGLE = "com.google.android.googlequicksearchbox"
            private const val ODICTO = "app.odicto.mobile"
            private val ACTIONS = setOf("Show keyboard", "Hide keyboard", "t", "e", "h", "Space", "Undo autocorrect", "Backspace")
        }
    }
}
