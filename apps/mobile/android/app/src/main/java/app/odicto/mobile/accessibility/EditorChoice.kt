package app.odicto.mobile.accessibility

/**
 * Chooses an editor when the accessible focus cannot be read.
 *
 * Compose and other modern toolkits rebuild their accessibility tree asynchronously, so the input
 * focus can be missing for a moment while the editor itself is present in the window. Only an
 * unambiguous answer may be used: a node that reports the input focus itself, or the single editor
 * in the window. Guessing between several editors would risk typing into the wrong field.
 */
internal object EditorChoice {
    fun picks(count: Int, hasFocusedEditor: Boolean): Boolean = count > 0 && (hasFocusedEditor || count == 1)
}
