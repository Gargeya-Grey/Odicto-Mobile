package app.odicto.mobile

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.ime.EditorPolicy
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
}
