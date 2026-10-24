package app.odicto.mobile

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.EditorUndoCoordinator
import app.odicto.mobile.ime.UndoEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditorUndoCoordinatorTest {
    private class Editor(var text: String = "AB", var undoText: String = "ABC") : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
        val events = mutableListOf<KeyEvent>()
        var reads = 0
        var contextActions = 0
        var commits = 0
        var ignore = false
        var reject = false
        var delayMs = 0L
        var onRead: () -> Unit = {}
        private var visibleBefore = text
        private var readyAt = 0L
        private fun visible() = if (System.nanoTime() / 1_000_000 < readyAt) visibleBefore else text
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            reads++; onRead()
            return ExtractedText().apply { text = visible(); startOffset = 0; partialStartOffset = -1; selectionStart = text.length; selectionEnd = text.length }
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = visible().takeLast(length)
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events += KeyEvent(event)
            if (reject) return false
            if (!ignore && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                visibleBefore = text
                readyAt = System.nanoTime() / 1_000_000 + delayMs
                text = undoText
            }
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean { contextActions++; return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { commits++; return true }
    }
    private fun info(type: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply { inputType = type; packageName = "test.host" }

    @Test fun undoUsesOneShortcutAndRestoresTheHostsDeletion() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor()
            val messages = Channel<String>(Channel.UNLIMITED)
            var dispatched = 0
            val undo = EditorUndoCoordinator(scope, { UndoEditor(1, editor, info()) }, { true },
                { message, busy -> if (!busy && message.isNotEmpty()) messages.trySend(message) }, { dispatched++ }, { _, _ -> })
            undo.undo()
            assertEquals("Text undone", withTimeout(5_000) { messages.receive() })
            assertEquals("ABC", editor.text)
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), editor.events.map { it.action })
            assertTrue(editor.events.all { it.keyCode == KeyEvent.KEYCODE_Z && it.isCtrlPressed })
            assertEquals(1, dispatched)
            assertEquals(0, editor.contextActions)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun delayedReadbackAndDoubleTapNeverSendAnotherUndo() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor().apply { delayMs = 60 }
            val messages = Channel<String>(Channel.UNLIMITED)
            val undo = EditorUndoCoordinator(scope, { UndoEditor(1, editor, info()) }, { true },
                { message, busy -> if (!busy && message.isNotEmpty()) messages.trySend(message) }, {}, { _, _ -> })
            undo.undo()
            withTimeout(5_000) { while (editor.events.size < 2) delay(1) }
            undo.undo()
            assertEquals("Text undone", withTimeout(5_000) { messages.receive() })
            assertEquals(2, editor.events.size)
            assertFalse(undo.busy.value)
        } finally { scope.cancel() }
    }

    @Test fun anAcceptedNoOpDoesNotFallBackOrClaimSuccess() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor().apply { ignore = true }
            val messages = Channel<String>(Channel.UNLIMITED)
            val undo = EditorUndoCoordinator(scope, { UndoEditor(1, editor, info()) }, { true },
                { message, busy -> if (!busy && message.isNotEmpty()) messages.trySend(message) }, {}, { _, _ -> })
            undo.undo()
            assertTrue(withTimeout(5_000) { messages.receive() }.startsWith("No text change confirmed"))
            assertEquals("AB", editor.text)
            assertEquals(2, editor.events.size)
            assertEquals(0, editor.contextActions)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun protectedAndUnsupportedFieldsAreRefusedBeforeReadingOrDispatch() = runBlocking {
        for (type in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_NULL)) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val editor = Editor()
                val undo = EditorUndoCoordinator(scope, { UndoEditor(1, editor, info(type)) }, { true }, { _, _ -> }, {}, { _, _ -> })
                undo.undo()
                assertEquals(0, editor.reads)
                assertTrue(editor.events.isEmpty())
            } finally { scope.cancel() }
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor()
            val protected = info().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
            EditorUndoCoordinator(scope, { UndoEditor(1, editor, protected) }, { true }, { _, _ -> }, {}, { _, _ -> }).undo()
            assertEquals(0, editor.reads)
            assertTrue(editor.events.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun numericAndOwnEditorsUseTheShortcutWithoutReadingTheirText() = runBlocking {
        for (targetInfo in listOf(info(InputType.TYPE_CLASS_NUMBER), info(InputType.TYPE_CLASS_PHONE), info().apply { packageName = BuildConfig.APPLICATION_ID })) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val editor = Editor()
                val messages = Channel<String>(Channel.UNLIMITED)
                val undo = EditorUndoCoordinator(scope, { UndoEditor(1, editor, targetInfo) }, { true },
                    { message, busy -> if (!busy && message.isNotEmpty()) messages.trySend(message) }, {}, { _, _ -> })
                undo.undo()
                assertEquals("Undo requested. Check the field.", withTimeout(5_000) { messages.receive() })
                assertEquals(0, editor.reads)
                assertEquals(2, editor.events.size)
            } finally { scope.cancel() }
        }
    }

    @Test fun changingTargetDuringTheInitialReadPreventsAllDispatch() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val first = Editor()
            val second = Editor("Other", "Unrelated")
            var current = first
            first.onRead = { current = second }
            val undo = EditorUndoCoordinator(scope, { UndoEditor(if (current === first) 1 else 2, current, info()) }, { true }, { _, _ -> }, {}, { _, _ -> })
            undo.undo()
            withTimeout(5_000) { while (undo.busy.value) delay(1) }
            assertTrue(first.events.isEmpty())
            assertTrue(second.events.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun cancelStopsPendingConfirmationWithoutDispatchingOrAnnouncingAgain() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor().apply { ignore = true }
            val messages = mutableListOf<String>()
            val undo = EditorUndoCoordinator(scope, { UndoEditor(1, editor, info()) }, { true },
                { message, _ -> if (message.isNotEmpty()) messages += message }, {}, { _, _ -> })
            undo.undo()
            withTimeout(5_000) { while (editor.events.size < 2) delay(1) }
            undo.cancel()
            val count = messages.size
            delay(200)
            assertEquals(count, messages.size)
            assertEquals(2, editor.events.size)
            assertFalse(undo.busy.value)
        } finally { scope.cancel() }
    }
}
