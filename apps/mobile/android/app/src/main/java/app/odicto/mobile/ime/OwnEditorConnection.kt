package app.odicto.mobile.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextSnapshot

/**
 * The connection to an editor in Odicto's own app, such as a settings field.
 *
 * The keyboard and that editor share one main thread. A synchronous read waits on the
 * main thread for an answer that only the same main thread can produce, so every read
 * stalls until its timeout and Android reports the app as not responding. Reads here
 * answer "unknown" at once, which every caller already handles: the keyboard falls back
 * to plain key events, and features that need the field's text refuse. Writes are
 * one-way and pass through.
 */
internal class OwnEditorConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun takeSnapshot(): TextSnapshot? = null
}
