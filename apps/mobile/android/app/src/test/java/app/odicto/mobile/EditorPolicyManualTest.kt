package app.odicto.mobile

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.ime.EditorPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A keyboard that refused numeric fields would strand the user, but voice, emoji, and clipboard all
 * write text and must stay out of secure fields. These tests keep the two rule sets from drifting.
 */
class EditorPolicyManualTest {
    private fun editor(type: Int, noLearning: Boolean = false) = EditorInfo().apply {
        inputType = type
        if (noLearning) imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    @Test fun manualTypingWorksWhereverAKeyboardIsExpected() {
        assertTrue(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT)))
        assertTrue(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_NUMBER)))
        assertTrue(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_PHONE)))
        assertTrue(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_DATETIME)))
        assertTrue(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)))
        assertTrue(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)))
    }

    @Test fun passwordAndProtectedFieldsRefuseEverything() {
        assertFalse(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertFalse(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertFalse(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertFalse(EditorPolicy.manualPermits(editor(InputType.TYPE_CLASS_TEXT, noLearning = true)))
        assertFalse(EditorPolicy.manualPermits(null))
    }

    @Test fun emojiAndClipboardFollowTheSameRuleAsVoice() {
        val numeric = editor(InputType.TYPE_CLASS_NUMBER)
        assertTrue(EditorPolicy.manualPermits(numeric))
        assertFalse(EditorPolicy.emojiPermits(numeric))
        assertFalse(EditorPolicy.clipboardPermits(numeric))

        val plain = editor(InputType.TYPE_CLASS_TEXT)
        assertTrue(EditorPolicy.emojiPermits(plain))
        assertTrue(EditorPolicy.clipboardPermits(plain))

        val password = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(EditorPolicy.emojiPermits(password))
        assertFalse(EditorPolicy.clipboardPermits(password))
    }

    @Test fun refusalsNameTheFieldSoTheMessageIsActionable() {
        val numeric = editor(InputType.TYPE_CLASS_NUMBER)
        val reason = EditorPolicy.clipboardRefusal(numeric)
        assertTrue("a refusal must explain itself, was: $reason", reason != null)
        assertTrue("the refusal must name the field, was: $reason", reason!!.contains("number", ignoreCase = true))
        // The message must also make clear that ordinary typing still works.
        assertTrue("the refusal should reassure that typing works, was: $reason", reason.contains("type", ignoreCase = true))
    }

    @Test fun aPlainTextFieldRefusesNothing() {
        val plain = editor(InputType.TYPE_CLASS_TEXT)
        assertNull(EditorPolicy.clipboardRefusal(plain))
        assertNull(EditorPolicy.emojiRefusal(plain))
        assertNull(EditorPolicy.emojiRefusal(null, connected = true))
        assertTrue(EditorPolicy.emojiRefusal(null)!!.contains("focused", ignoreCase = true))
    }

    @Test fun eachRefusedFieldKindGetsItsOwnReason() {
        val password = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(EditorPolicy.emojiRefusal(password)!!.contains("password", ignoreCase = true))
        val phone = editor(InputType.TYPE_CLASS_PHONE)
        assertTrue(EditorPolicy.clipboardRefusal(phone)!!.contains("phone", ignoreCase = true))
        val protected = editor(InputType.TYPE_CLASS_TEXT, noLearning = true)
        assertTrue(EditorPolicy.clipboardRefusal(protected)!!.contains("learn", ignoreCase = true))
    }

    @Test fun aPasswordFieldIsStillTypeableButNotReadable() {
        val password = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // The keyboard can show letters, but nothing may read the field back.
        assertFalse(EditorPolicy.isPasswordField(editor(InputType.TYPE_CLASS_TEXT)))
        assertTrue(EditorPolicy.isPasswordField(password))
        assertFalse(EditorPolicy.emojiPermits(password))
        assertFalse(EditorPolicy.clipboardPermits(password))
    }
}
