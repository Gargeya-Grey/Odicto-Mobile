package app.odicto.mobile.ime

import org.junit.Assert.*
import org.junit.Test

class EditorEvidenceRegressionTest {
    @Test fun latestTypingCallbackRetiresCompactedRunWithoutForgettingOldPositions() {
        val state = EditorSessionState()
        state.reset(Any(), 7, 7)
        state.seed("prefix ", true)
        repeat(80) { i ->
            state.submitted(EditorSessionState.Snapshot(8 + i, 8 + i, "prefix " + "q".repeat(i + 1), true), 0, false, i.toLong())
        }
        assertTrue(state.plausible(8, 8))
        assertTrue(state.acknowledgeTyping(7, 7, 87, 87))
        assertFalse(state.pendingTyping)
        assertFalse(state.pending)
        assertFalse(state.ambiguous)
    }

    @Test fun typingCallbackCannotRetireAnInterveningDelete() {
        val state = EditorSessionState()
        state.reset(Any(), 7, 7)
        state.seed("prefix ", true)
        state.submitted(EditorSessionState.Snapshot(6, 6, "prefix", true), 1, false, 0)
        state.submitted(EditorSessionState.Snapshot(7, 7, "prefixq", true), 0, false, 1)
        assertFalse(state.acknowledgeTyping(7, 7, 7, 7))
        assertEquals(1, state.deleteCount)
    }

    @Test fun unknownNativeDeleteNeedsFreshEvidenceBeforeAnotherRepeat() {
        val state = EditorSessionState()
        state.reset(Any(), 3, 3)
        state.submitted(EditorSessionState.Snapshot(-1, -1, null), 1, false, 0)
        assertTrue(state.locateUnknownDelete(1, 1))
        val repeat = DeleteRepeatCoordinator()
        repeat.start(state.epoch)
        assertEquals(1, state.deleteCount)
        assertFalse(repeat.hasCredit(state, false, 100))
        assertEquals(EditorSessionState.Evidence.CURRENT, state.observe(EditorSessionState.Snapshot(1, 1, "a", true)))
        assertTrue(repeat.hasCredit(state, false, 101))
    }

    @Test fun replacementRetainsOnlyKnownInsertedSuffix() {
        val tail = EditorTail()
        tail.seed("unrelated", 9)
        assertEquals(9, tail.afterReplace(7, "xy"))
        assertEquals("xy", tail.knownSuffix())
        assertFalse(tail.complete)
    }

    @Test fun repeatedSuffixWithoutAbsoluteEvidenceRemainsAwaitingEvidence() {
        val state = EditorSessionState()
        state.reset(Any(), -1, -1)
        state.seed("q".repeat(96), false)
        state.submitted(EditorSessionState.Snapshot(-1, -1, "q".repeat(95)), 1, false, 0)
        state.submitted(EditorSessionState.Snapshot(-1, -1, "q".repeat(94)), 1, false, 1)
        assertEquals(EditorSessionState.Evidence.WAITING, state.observe(EditorSessionState.Snapshot(-1, -1, "q".repeat(96))))
        val repeat = DeleteRepeatCoordinator()
        repeat.start(state.epoch)
        repeat.waitForEvidence()
        assertEquals(DeleteRepeatCoordinator.Phase.AwaitingEvidence, repeat.phase)
        assertFalse(repeat.hasCredit(state, false, 100))
        assertEquals(2, state.deleteCount)
    }
}
