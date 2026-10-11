package app.odicto.mobile.ime

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ManualCursorContinuityTest {
    private fun reposition(readable: Boolean, protected: Boolean = false) {
        DelayedEditorReplayFixture("prefix ").use { f ->
            if (protected) f.restart(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            f.tap("q"); f.tap("p")
            f.advance(200, notify = false)
            f.editor.cursor = 7; f.editor.selectionEnd = 7
            f.editor.publishReads()
            f.editor.readable = readable
            f.controller.onSelectionChanged(9, 9, 7, 7, -1, -1)
            val reads = f.editor.readCount
            f.tap("w"); f.tap("e")
            f.advance(200, notify = false)
            assertEquals("prefix weqp", f.editor.text)
            if (protected) assertEquals(reads, f.editor.readCount)
        }
    }

    @Test fun visitedCursorWithNullReadsDoesNotDropManualTaps() = reposition(false)
    @Test fun incompatibleReadableCursorDoesNotDropFirstTap() = reposition(true)
    @Test fun protectedVisitedCursorTypesWithoutReads() = reposition(false, true)

    @Test fun cursorMovementPreservesCompositionHandoffBeforeTyping() {
        DelayedEditorReplayFixture("abcdef").use { f ->
            f.controller.onSelectionChanged(6, 6, 6, 6, 0, 6)
            f.press("Space"); f.advance(260, notify = false)
            f.moveSpace(-1); f.release("Space")
            f.advance(200, notify = false)
            f.tap("q")
            f.advance(200, notify = false)
            assertEquals(1, f.editor.finishCompositionCalls)
            assertEquals("abcdeqf", f.editor.text)
        }
    }

    @Test fun intermediateDragCallbackThenNullReadsDoesNotStallTyping() {
        DelayedEditorReplayFixture("abcdef").use { f ->
            f.press("Space"); f.advance(260, notify = false)
            f.moveSpace(-1); f.moveSpace(-2); f.release("Space")
            f.advance(200, notify = false)
            f.tap("q")
            f.advance(200, notify = false)
            f.controller.onSelectionChanged(6, 6, 5, 5, -1, -1)
            f.editor.readable = false
            f.tap("w"); f.tap("e")
            f.advance(200, notify = false)
            assertEquals("abcdqweef", f.editor.text)
        }
    }
}
