package app.odicto.mobile.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnEditorConnectionTest {
    /** An editor that answers every read and records every call that reaches it. */
    private fun editor(calls: MutableList<String>): InputConnection =
        Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, method, _ ->
            calls += method.name
            when (method.returnType) {
                CharSequence::class.java -> "text"
                SurroundingText::class.java -> SurroundingText("text", 4, 4, 0)
                ExtractedText::class.java -> ExtractedText().apply { text = "text" }
                Boolean::class.javaPrimitiveType -> true
                Int::class.javaPrimitiveType -> 1
                else -> null
            }
        } as InputConnection

    @Test fun readsAnswerUnknownWithoutReachingTheEditor() {
        val calls = mutableListOf<String>()
        val connection = OwnEditorConnection(editor(calls))
        assertNull(connection.getTextBeforeCursor(64, 0))
        assertNull(connection.getTextAfterCursor(64, 0))
        assertNull(connection.getSelectedText(0))
        assertNull(connection.getSurroundingText(64, 64, 0))
        assertNull(connection.getExtractedText(ExtractedTextRequest(), 0))
        assertNull(connection.takeSnapshot())
        assertEquals(0, connection.getCursorCapsMode(0))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun writesReachTheEditor() {
        val calls = mutableListOf<String>()
        val connection = OwnEditorConnection(editor(calls))
        assertTrue(connection.commitText("a", 1))
        assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
        assertTrue(connection.setComposingText("b", 1))
        assertTrue(connection.finishComposingText())
        assertEquals(listOf("commitText", "deleteSurroundingTextInCodePoints", "setComposingText", "finishComposingText"), calls)
    }
}
