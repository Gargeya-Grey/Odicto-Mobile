package app.odicto.mobile.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Which editor each capability may act in.
 *
 * Voice keeps its conservative rule set, because dictation is the one capability that can move text
 * nobody can see being typed. Ordinary typing is allowed wherever a keyboard is expected to work,
 * including numeric and phone fields, so refusing those would leave the user with no way to enter
 * their own account number. Emoji and clipboard sit between the two: they write into the editor, so
 * they stay out of password fields and anything marked as not wanting personalized learning.
 */
object EditorPolicy {
    /** Voice dictation. Unchanged: text fields only, and never secure, numeric, or phone. */
    fun permits(info: EditorInfo?): Boolean {
        if (info == null) return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        return permits(info.inputType, false)
    }

    /** Same field rules for an accessibility node, which exposes inputType and isPassword but no imeOptions. */
    fun permits(inputType: Int, isPassword: Boolean): Boolean {
        if (isPassword) return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )) return false
        return inputClass == InputType.TYPE_CLASS_TEXT
    }

    /**
     * Whether the keyboard may show its letter and symbol pages here. A keyboard that refuses a
     * numeric field would strand the user, so numeric, phone, and datetime fields are allowed; a
     * password field still gets a keyboard, but only one that cannot see what is typed into it.
     */
    fun manualPermits(info: EditorInfo?): Boolean {
        if (info == null) return false
        return !isPasswordField(info) && !refusesLearning(info)
    }

    fun typingAssistPermits(info: EditorInfo?): Boolean =
        info != null && manualPermits(info) && (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT

    /** Emoji and clipboard both write text, so they are refused wherever voice is. */
    fun clipboardPermits(info: EditorInfo?): Boolean = permits(info)

    fun emojiPermits(info: EditorInfo?): Boolean = permits(info)

    /**
     * Why a capability cannot be used here, in words the user can act on, or null when it is allowed.
     *
     * A bare "not available" was reported as a bug because it read like the feature was missing. The
     * refusal almost always depends on the field, so the field is named.
     */
    fun clipboardRefusal(info: EditorInfo?): String? = refusal(info, "Clipboard")

    /**
     * [connected] is true when the keyboard still has a live input connection. The cached editor
     * info can be cleared while that connection is still the focused field, and emoji must not
     * pretend the field went away.
     */
    fun emojiRefusal(info: EditorInfo?, connected: Boolean = false): String? {
        if (info == null) return if (connected) null else "Emoji needs a focused text field."
        return refusal(info, "Emoji")
    }

    fun voiceRefusal(info: EditorInfo?): String? = refusal(info, "Voice")

    private fun refusal(info: EditorInfo?, feature: String): String? {
        if (info == null) return "$feature needs a focused text field."
        if (isPasswordField(info)) return "$feature is off in password fields."
        if (refusesLearning(info)) return "$feature is off in fields that ask not to learn from typing."
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER) return "$feature is off in number fields. You can still type."
        if (inputClass == InputType.TYPE_CLASS_PHONE) return "$feature is off in phone-number fields. You can still type."
        if (inputClass == InputType.TYPE_CLASS_DATETIME) return "$feature is off in date and time fields. You can still type."
        if (inputClass != InputType.TYPE_CLASS_TEXT) return "$feature is off in this kind of field. You can still type."
        return null
    }

    /**
     * A password field is still typeable, but nothing may read it back: no clipboard, no emoji
     * preview, and no surrounding text. A password variant is detected from the input type because
     * an IME is not told separately.
     */
    fun isPasswordField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        if (inputClass(inputType) != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }

    private fun refusesLearning(info: EditorInfo): Boolean =
        (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /** Accessibility nodes can omit the input class; trust the editable flag there, but never for secure or numeric fields. */
    fun permitsNode(inputType: Int, isPassword: Boolean, isEditable: Boolean): Boolean = nodeReason(inputType, isPassword, isEditable) == OK

    /** Why a node is accepted or refused, so a "the mic never activated" report names its own cause. */
    fun nodeReason(
        inputType: Int,
        isPassword: Boolean,
        isEditable: Boolean,
        isFocused: Boolean = false,
        supportsText: Boolean = false,
        className: String? = null,
    ): String {
        if (isPassword) return "password"
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER) return "numeric"
        if (inputClass == InputType.TYPE_CLASS_PHONE) return "phone"
        // Compose and other custom editors can report no input class and no editable flag while still
        // being a focused text widget that accepts text. The widget class plus the text action is proof
        // that insertion works there; an unidentified field is otherwise refused.
        val textWidget = isFocused && supportsText && className?.contains("EditText") == true
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if (variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                )) return "password-variation"
            return if (isEditable || textWidget) OK else "not-editable"
        }
        if (inputClass == 0) {
            if (isEditable) return OK
            return if (textWidget) OK else "not-editable"
        }
        return if (isEditable) "unsupported" else "not-editable"
    }

    private fun inputClass(inputType: Int) = inputType and InputType.TYPE_MASK_CLASS

    const val OK = "ok"
}
