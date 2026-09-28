package app.odicto.mobile

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.ime.EditorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPolicyTest {
    @Test fun allowsPlainText() { assertTrue(EditorPolicy.permits(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })) }
    @Test fun blocksPasswordsPhonesAndNumbers() {
        assertFalse(EditorPolicy.permits(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }))
        assertFalse(EditorPolicy.permits(EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }))
        assertFalse(EditorPolicy.permits(EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }))
    }
    @Test fun nodeReasonsNameTheRefusal() {
        assertEquals("ok", EditorPolicy.nodeReason(InputType.TYPE_CLASS_TEXT, isPassword = false, isEditable = true))
        assertEquals("ok", EditorPolicy.nodeReason(0, isPassword = false, isEditable = true))
        assertEquals("password", EditorPolicy.nodeReason(InputType.TYPE_CLASS_TEXT, isPassword = true, isEditable = true))
        assertEquals("password-variation", EditorPolicy.nodeReason(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, isPassword = false, isEditable = true))
        assertEquals("numeric", EditorPolicy.nodeReason(InputType.TYPE_CLASS_NUMBER, isPassword = false, isEditable = true))
        assertEquals("phone", EditorPolicy.nodeReason(InputType.TYPE_CLASS_PHONE, isPassword = false, isEditable = true))
        assertEquals("not-editable", EditorPolicy.nodeReason(InputType.TYPE_CLASS_TEXT, isPassword = false, isEditable = false))
    }
    @Test fun nodeRefusalMatchesTheReason() {
        // One rule, two views of it: a refusal can never disagree with the reason it reports.
        for (inputType in listOf(InputType.TYPE_CLASS_TEXT, 0, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            for (isPassword in listOf(true, false)) {
                for (isEditable in listOf(true, false)) {
                    assertEquals(EditorPolicy.nodeReason(inputType, isPassword, isEditable) == "ok", EditorPolicy.permitsNode(inputType, isPassword, isEditable))
                }
            }
        }
    }
    @Test fun aFocusedTextWidgetWithoutFlagsIsAccepted() {
        // X (Twitter) exposes its Compose reply field as a focused EditText with no input class and no
        // editable flag; the text action is what proves insertion works there.
        assertEquals("ok", EditorPolicy.nodeReason(
            inputType = 0, isPassword = false, isEditable = false,
            isFocused = true, supportsText = true, className = "android.widget.EditText",
        ))
    }
    @Test fun anUnfocusedOrUnsupportedWidgetIsRefused() {
        assertEquals("not-editable", EditorPolicy.nodeReason(0, false, false, isFocused = false, supportsText = true, className = "android.widget.EditText"))
        assertEquals("not-editable", EditorPolicy.nodeReason(0, false, false, isFocused = true, supportsText = false, className = "android.widget.EditText"))
        assertEquals("not-editable", EditorPolicy.nodeReason(0, false, false, isFocused = true, supportsText = true, className = "android.widget.TextView"))
        assertEquals("not-editable", EditorPolicy.nodeReason(0, false, false, isFocused = false, supportsText = false, className = null))
    }
    @Test fun theTextWidgetPathNeverOverridesSecurityRules() {
        assertEquals("password", EditorPolicy.nodeReason(0, true, false, isFocused = true, supportsText = true, className = "android.widget.EditText"))
        assertEquals("numeric", EditorPolicy.nodeReason(InputType.TYPE_CLASS_NUMBER, false, false, isFocused = true, supportsText = true, className = "android.widget.EditText"))
        assertEquals("phone", EditorPolicy.nodeReason(InputType.TYPE_CLASS_PHONE, false, false, isFocused = true, supportsText = true, className = "android.widget.EditText"))
        assertEquals("password-variation", EditorPolicy.nodeReason(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false, false, isFocused = true, supportsText = true, className = "android.widget.EditText"))
    }
}
