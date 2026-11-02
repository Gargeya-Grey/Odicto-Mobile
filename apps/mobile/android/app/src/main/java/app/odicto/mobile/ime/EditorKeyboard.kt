package app.odicto.mobile.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal object EditorKeyboard {
    fun page(info: EditorInfo?): OdictoKeyboardView.Page {
        val type = info?.inputType ?: return OdictoKeyboardView.Page.LETTERS
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> OdictoKeyboardView.Page.PHONE
            InputType.TYPE_CLASS_NUMBER -> {
                val signed = type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
                val decimal = type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
                when {
                    signed && decimal -> OdictoKeyboardView.Page.NUMPAD_SIGNED_DECIMAL
                    signed -> OdictoKeyboardView.Page.NUMPAD_SIGNED
                    decimal -> OdictoKeyboardView.Page.NUMPAD_DECIMAL
                    else -> OdictoKeyboardView.Page.NUMPAD
                }
            }
            else -> OdictoKeyboardView.Page.LETTERS
        }
    }

    fun usesNumpad(info: EditorInfo?): Boolean = page(info) != OdictoKeyboardView.Page.LETTERS
}
