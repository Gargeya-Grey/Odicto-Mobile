package app.odicto.mobile.ime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HapticsPreparationTest.CountingVibrator::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CursorMovementFeedbackTest {
    private val requests get() = HapticsPreparationTest.CountingVibrator.requests

    @Before fun reset() {
        HapticsPreparationTest.CountingVibrator.rejectCapability = false
        HapticsPreparationTest.CountingVibrator.rejectRequest = false
        requests.clear()
    }

    @Test fun coalescedMovementAndReversalRequestEachAcceptedGraphemeSynchronously() {
        DelayedEditorReplayFixture("a\uD83D\uDE00e\u0301z").use { f ->
            f.press("Space"); f.advance(260, notify = false)
            requests.clear()
            f.moveSpace(-3)
            assertEquals(listOf(5, 3, 1), f.editor.selections)
            assertEquals(3, requests.size)
            f.moveSpace(-2)
            assertEquals(listOf(5, 3, 1, 3), f.editor.selections)
            assertEquals(4, requests.size)
        }
    }

    @Test fun boundaryAndRejectedSelectionRequestNoMovementFeedback() {
        DelayedEditorReplayFixture("ab").use { f ->
            f.press("Space"); f.advance(260, notify = false)
            requests.clear()
            f.moveSpace(1)
            assertTrue(f.editor.selections.isEmpty())
            assertTrue(requests.isEmpty())
            f.editor.rejectWrites = true
            f.moveSpace(-1)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun vibrationFailureDoesNotInterruptAcceptedMovement() {
        DelayedEditorReplayFixture("abcd").use { f ->
            f.press("Space"); f.advance(260, notify = false)
            requests.clear()
            HapticsPreparationTest.CountingVibrator.rejectRequest = true
            f.moveSpace(-3)
            assertEquals(listOf(3, 2, 1), f.editor.selections)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun replacementDuringCursorReadCannotMoveOldConnection() {
        DelayedEditorReplayFixture("abc").use { f ->
            f.press("Space"); f.advance(260, notify = false)
            requests.clear()
            f.editor.beforeRead = { f.connection = DelayedEditorReplayFixture.Editor(android.view.View(f.service), "new") }
            f.moveSpace(-1)
            assertTrue(f.editor.selections.isEmpty())
            assertTrue(requests.isEmpty())
        }
    }
}
