package app.odicto.mobile.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardInteractionReplayTest {
    private val paragraph = (1..200).joinToString(" ") { "word$it" }

    @Test fun sustainedHoldSurvivesDelayedApplicationAndLatestOnlyNotifications() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.press("Backspace")
            f.advance(6000)
            f.release("Backspace")
            f.advance(200)
            assertTrue("hold must reach word phase, not stop on stale selection", f.editor.appliedDeleteSizes.count { it > 1 } > 15)
            assertTrue("hold must refill its bounded suffix", paragraph.length - f.editor.text.length > 200)
            assertTrue("at most two destructive operations may be unapplied", f.editor.maxPendingDeletes <= 2)
            assertEquals(0, f.editor.batchDepth)
            assertTrue(!f.editor.wordOverlapped)
            var expected = paragraph.dropLast(10)
            repeat(f.editor.appliedDeleteSizes.size - 10) { expected = expected.replace(Regex("\\S+\\s*$"), "") }
            assertEquals(expected, f.editor.text)
            assertEquals(expected.length, f.editor.cursor)
            assertTrue(f.editor.submissionTimes.zipWithNext().all { (a, b) -> b - a >= BackspacePace.WORD_INTERVAL_MS })
        }
    }

    @Test fun sameFingerResumesAfterTemporaryApplicationAndReadLag() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.press("Backspace")
            f.advance(500)
            f.advance(1000, apply = false, read = false, notify = false)
            val paused = f.editor.submittedDeletes
            f.advance(500, apply = false, read = false, notify = false)
            assertEquals(paused, f.editor.submittedDeletes)
            f.advance(3000)
            assertTrue("same hold must resume without a new down", f.editor.submittedDeletes > paused + 10)
            f.release("Backspace")
            val released = f.editor.submittedDeletes
            f.advance(500)
            assertEquals(released, f.editor.submittedDeletes)
        }
    }

    @Test fun overlappingThumbsPreserveOrderAcrossFramesAndBothReleaseOrders() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            repeat(80) { f.twoThumbs("q", "p", it % 2 == 0) }
            f.advance(500)
            assertEquals("prefix " + "qp".repeat(80), f.editor.text)
            f.press("Backspace")
            f.advance(1500)
            f.release("Backspace")
            f.advance(200)
            assertTrue(f.editor.appliedDeleteSizes.size > 10)
        }
    }

    @Test fun readableContextSupportsHeldDeletionWithoutSelectionMetadata() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.editor.metadata = false
            f.press("Backspace")
            f.advance(6000)
            f.release("Backspace")
            f.advance(200)
            assertTrue("unsupported metadata must not permanently stop a readable editor", f.editor.appliedDeleteSizes.count { it > 1 } > 15)
        }
    }

    @Test fun restartEndsOldHoldEvenWhenConnectionObjectIsReused() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.press("Backspace")
            f.advance(500)
            f.restart()
            val submitted = f.editor.submittedDeletes
            f.advance(1000)
            assertEquals(submitted, f.editor.submittedDeletes)
            assertEquals(0, f.editor.batchDepth)
        }
    }

    @Test fun staleReadResponsesAndNullReadsPauseThenResumeWithoutCatchUp() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.press("Backspace")
            f.advance(600)
            f.editor.readable = false
            f.advance(1000)
            val paused = f.editor.submittedDeletes
            f.advance(500)
            assertEquals(paused, f.editor.submittedDeletes)
            f.editor.readable = true
            f.advance(200, read = false)
            f.advance(2500)
            assertTrue(f.editor.submittedDeletes > paused + 10)
            f.release("Backspace")
            f.advance(200)
            assertTrue(f.editor.maxPendingDeletes <= 2)
            assertTrue(f.editor.submissionTimes.zipWithNext().all { (a, b) -> b - a >= BackspacePace.WORD_INTERVAL_MS })
        }
    }

    @Test fun replacementAndExplicitInvalidationNeverTransferHeldWork() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.press("Backspace")
            f.advance(500)
            val replacement = DelayedEditorReplayFixture.Editor(android.view.View(f.service), "replacement")
            f.connection = replacement
            f.advance(1000)
            assertEquals(0, replacement.submittedDeletes)
            f.restart()
            f.press("Backspace")
            f.advance(100)
            f.controller.onExternalEdit(4, 4)
            val submitted = replacement.submittedDeletes
            f.advance(1000)
            assertEquals(submitted, replacement.submittedDeletes)
        }
    }

    @Test fun failedWriteCancelsOldHoldButANewPressRemainsUsable() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.press("Backspace")
            f.advance(500)
            f.editor.rejectWrites = true
            f.advance(500)
            val stopped = f.editor.submittedDeletes
            f.editor.rejectWrites = false
            f.advance(500)
            assertEquals(stopped, f.editor.submittedDeletes)
            f.release("Backspace")
            f.tap("Backspace")
            f.advance(200)
            assertEquals(stopped + 1, f.editor.submittedDeletes)
        }
    }

    @Test fun protectedHoldDoesNotReadAndExhaustsItsBoundedAllowance() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.restart(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            f.press("Backspace")
            f.advance(3000)
            f.release("Backspace")
            assertEquals(0, f.editor.readCount)
            assertTrue(f.editor.submittedDeletes in 1..2)
            assertEquals(0, f.editor.batchDepth)
        }
    }

    @Test fun reversedSelectionReplacementThenHoldContinuesAtItsNormalizedEdge() {
        DelayedEditorReplayFixture("prefix selected suffix").use { f ->
            f.editor.cursor = 15
            f.editor.selectionEnd = 7
            f.editor.publishReads()
            f.restart()
            f.press("Backspace")
            f.advance(100)
            assertEquals("prefix  suffix", f.editor.text)
            f.advance(1500)
            f.release("Backspace")
            f.advance(200)
            assertEquals(" suffix", f.editor.text)
            assertEquals(0, f.editor.cursor)
        }
    }

    @Test fun secondaryThumbTypingPreservesInitiatingDeleteHold() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.twoThumbs("Backspace", "q", false, 2000)
            f.advance(200)
            assertTrue(f.editor.submittedDeletes > 10)
            assertTrue(f.editor.maxPendingDeletes <= 2)
            assertTrue(!f.editor.wordOverlapped)
            val released = f.editor.submittedDeletes
            f.advance(500)
            assertEquals(released, f.editor.submittedDeletes)
        }
    }

    @Test fun initialNullReadHoldResumesWhenMetadataReturns() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.editor.readable = false
            f.press("Backspace")
            f.advance(750)
            val paused = f.editor.submittedDeletes
            f.editor.readable = true
            f.advance(3000)
            assertTrue(f.editor.submittedDeletes > paused + 15)
        }
    }

    @Test fun ordinalZeroAbortsWhenRefreshFindsExternalSelection() {
        DelayedEditorReplayFixture("prefix abcdef").use { f ->
            f.tap("q")
            f.advance(100, notify = false)
            f.editor.cursor = 2
            f.editor.selectionEnd = 5
            f.editor.publishReads()
            f.press("Backspace")
            assertEquals(0, f.editor.submittedDeletes)
        }
    }

    @Test fun pendingTypingCannotHideExternalSelectedRange() {
        DelayedEditorReplayFixture("prefix abcdef").use { f ->
            f.tap("q")
            f.advance(100, notify = false)
            f.editor.cursor = 2
            f.editor.selectionEnd = 5
            f.editor.publishReads()
            f.controller.onSelectionChanged(14, 14, 2, 5, -1, -1)
            f.tap("Backspace")
            f.advance(200)
            assertEquals("prx abcdefq", f.editor.text)
            assertEquals(0, f.editor.submittedDeletes)
        }
    }

    @Test fun delayedTypingCallbackBeyondThirtyTwoEntriesDoesNotEndHold() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            repeat(80) { f.tap("q"); f.advance(10, notify = false) }
            f.advance(200, notify = false)
            f.press("Backspace")
            f.advance(500, notify = false)
            f.controller.onSelectionChanged(7, 7, 8, 8, -1, -1)
            val count = f.editor.submittedDeletes
            f.advance(1500)
            assertTrue(f.editor.submittedDeletes > count + 5)
        }
    }

    @Test fun healthyWordProgressDoesNotPayFailureBackoff() {
        DelayedEditorReplayFixture(paragraph).use { f ->
            f.editor.delay = 0
            f.press("Backspace")
            f.advance(3500)
            val times = f.editor.submissionTimes.takeLast(15)
            assertEquals(15, times.size)
            assertTrue("healthy words should follow 34ms scheduling, not 80ms read backoff", times.zipWithNext().all { (a, b) -> b - a < 60 })
        }
    }

    @Test fun ambiguousRepeatedSuffixWithoutMetadataStaysBounded() {
        DelayedEditorReplayFixture("q".repeat(500)).use { f ->
            f.editor.metadata = false
            f.press("Backspace")
            f.advance(4000)
            assertTrue(f.editor.submittedDeletes <= 2)
            assertTrue(f.editor.readCount < 110)
        }
    }

    @Test fun manualTypingFinishesKnownCompositionEvenAfterVoiceIsNoLongerBusy() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            f.controller.onSelectionChanged(7, 7, 7, 7, 0, 6)
            f.tap("q")
            f.advance(200)
            assertEquals(1, f.editor.finishCompositionCalls)
            assertEquals("prefix q", f.editor.text)
        }
    }

    @Test fun commitsPublishWithoutWaitingForAnImeFrameAndRemainOrdered() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            for (key in "qwertyuiop".repeat(12)) {
                f.tap(key.toString())
                assertEquals("ordinary edits must not hold host layout across a frame", 0, f.editor.batchDepth)
                f.advance(10, notify = false)
            }
            f.advance(500)
            assertEquals("prefix " + "qwertyuiop".repeat(12), f.editor.text)
        }
    }
}
