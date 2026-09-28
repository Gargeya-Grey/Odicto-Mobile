package app.odicto.mobile.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the bottom-right key should do for this field.
 *
 * A search box, a Go button, or Send is an editor action. Enter inserts a newline when the field
 * is multiline, when it asks for no action, or when it sets [EditorInfo.IME_FLAG_NO_ENTER_ACTION].
 */
internal object ImeActions {
    enum class Kind(val label: String?, val code: Int) {
        NEWLINE(null, EditorInfo.IME_ACTION_NONE),
        GO("Go", EditorInfo.IME_ACTION_GO),
        SEARCH("Search", EditorInfo.IME_ACTION_SEARCH),
        SEND("Send", EditorInfo.IME_ACTION_SEND),
        NEXT("Next", EditorInfo.IME_ACTION_NEXT),
        DONE("Done", EditorInfo.IME_ACTION_DONE),
        PREVIOUS("Prev", EditorInfo.IME_ACTION_PREVIOUS),
    }

    fun resolve(info: EditorInfo?): Kind {
        if (info == null) return Kind.NEWLINE
        return resolve(info.inputType, info.imeOptions)
    }

    fun resolve(inputType: Int, imeOptions: Int): Kind {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Kind.NEWLINE
        val multiline = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> Kind.GO
            EditorInfo.IME_ACTION_SEARCH -> Kind.SEARCH
            EditorInfo.IME_ACTION_SEND -> Kind.SEND
            EditorInfo.IME_ACTION_NEXT -> Kind.NEXT
            EditorInfo.IME_ACTION_DONE -> Kind.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> Kind.PREVIOUS
            EditorInfo.IME_ACTION_NONE -> Kind.NEWLINE
            else -> if (multiline) Kind.NEWLINE else Kind.DONE
        }
    }

    /** A short custom action label from the field, when it will fit on the key. */
    fun displayLabel(info: EditorInfo?, kind: Kind): String? {
        if (kind == Kind.NEWLINE) return null
        val custom = info?.actionLabel?.toString()?.trim().orEmpty()
        return if (custom.isNotEmpty() && custom.length <= 10) custom else kind.label
    }
}
