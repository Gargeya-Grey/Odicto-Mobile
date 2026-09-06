package app.odicto.mobile

import app.odicto.mobile.dictation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DictationCoordinatorTest {
    @Test fun liveUpdatesUseOriginalEditorAndFinishWithoutOrdinaryPaste() {
        val writes = mutableListOf<Pair<String, Boolean>>()
        DictationCoordinator.registerLive({ true }, { text, finish -> writes.add(text to finish); true }, {})
        DictationCoordinator.registerInserter { _, _ -> fail("Live must not paste twice"); true }
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session)); assertTrue(DictationCoordinator.beginLive())
        assertTrue(DictationCoordinator.stream(session, "Hello"))
        DictationCoordinator.process(session)
        assertTrue(DictationCoordinator.deliver(session, "Hello"))
        assertEquals(listOf("Hello" to false, "Hello" to true), writes)
        assertFalse(DictationCoordinator.stream(session, "late"))
    }
    @Test fun liveFocusLossRejectsFurtherTyping() {
        DictationCoordinator.registerLive({ true }, { _, _ -> fail("Wrong editor"); true }, {})
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session)); assertTrue(DictationCoordinator.beginLive())
        DictationCoordinator.editorClosed(); DictationCoordinator.editorReady()
        assertFalse(DictationCoordinator.stream(session, "private"))
        DictationCoordinator.process(session)
        assertFalse(DictationCoordinator.deliver(session, "private"))
    }
    @Test fun aiReplacesOnlyTheOriginalSelectionAndClearsContext() {
        val selection = AiSelection(4, 9, "hello")
        DictationCoordinator.registerSelectionReader { selection }
        var inserted = false
        DictationCoordinator.registerInserter { _, _ -> inserted = true; true }
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        assertEquals(selection, DictationCoordinator.aiSelection)
        DictationCoordinator.process(session)
        assertTrue(DictationCoordinator.deliver(session, "hi"))
        assertTrue(inserted); assertNull(DictationCoordinator.aiSelection)
    }
    @Test fun aiRejectsMovedChangedAndMovedBackSelections() {
        for (change in listOf("move", "text", "moveBack")) {
            var current = AiSelection(4, 9, "hello")
            DictationCoordinator.registerSelectionReader { current }
            DictationCoordinator.registerInserter { _, _ -> fail("Unsafe insertion"); true }
            val session = DictationCoordinator.editorReady()
            assertTrue(DictationCoordinator.start(session, true))
            when (change) {
                "move" -> current = AiSelection(14, 19, "hello")
                "text" -> current = AiSelection(4, 9, "world")
                else -> { DictationCoordinator.selectionUpdated(9, 9); DictationCoordinator.selectionUpdated(4, 9) }
            }
            DictationCoordinator.process(session)
            assertFalse(DictationCoordinator.deliver(session, "hi"))
            assertNull(DictationCoordinator.aiSelection)
        }
    }
    @Test fun aiCursorDraftAndUnavailableSelectionAreDistinct() {
        DictationCoordinator.registerSelectionReader { null }
        val session = DictationCoordinator.editorReady()
        assertFalse(DictationCoordinator.start(session, true))
        DictationCoordinator.registerSelectionReader { AiSelection(5, 5, "") }
        DictationCoordinator.registerInserter { _, _ -> true }
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.process(session)
        assertTrue(DictationCoordinator.deliver(session, "draft"))
        DictationCoordinator.registerSelectionReader { fail("Raw must not read context"); null }
        assertTrue(DictationCoordinator.start(session))
    }
    @Test fun invalidOrOversizedSelectionsAreRejected() {
        assertFalse(AiSelection(-1, -1, "").valid)
        assertFalse(AiSelection(0, 8, "partial").valid)
        assertFalse(AiSelection(0, 20_001, "a".repeat(20_001)).valid)
        assertTrue(AiSelection(5, 0, "hello").matches(AiSelection(0, 5, "hello")))
    }
    @Test fun aiCancellationAndFocusLossNeverReplaceAndReleaseContext() {
        DictationCoordinator.registerSelectionReader { AiSelection(0, 5, "hello") }
        DictationCoordinator.registerInserter { _, _ -> fail("Stale AI insertion"); true }
        var session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.cancel(); assertNull(DictationCoordinator.aiSelection)
        assertFalse(DictationCoordinator.deliver(session, "late"))
        session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.editorClosed(true); DictationCoordinator.process(session)
        assertFalse(DictationCoordinator.deliver(session, "late"))
        assertNull(DictationCoordinator.aiSelection)
    }
    @Test fun unavailableEditorAtCommitFallsBackWithoutLosingResult() {
        DictationCoordinator.registerSelectionReader { AiSelection(0, 5, "hello") }
        DictationCoordinator.registerInserter { _, _ -> throw IllegalStateException("Editor disconnected") }
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.process(session)
        assertFalse(DictationCoordinator.deliver(session, "hi"))
        assertTrue(DictationCoordinator.state.value is DictationState.SavedToHistory)
        assertNull(DictationCoordinator.aiSelection)
    }
    @After fun reset() { DictationCoordinator.cancel(); DictationCoordinator.unregisterInserter() }
    @Test fun repeatedRecordingsWorkWithoutRefocusing() {
        val session = DictationCoordinator.editorReady()
        DictationCoordinator.registerInserter { _, _ -> true }
        repeat(3) {
            assertTrue(DictationCoordinator.start(session)); assertFalse(DictationCoordinator.start(session))
            DictationCoordinator.process(session); assertTrue(DictationCoordinator.deliver(session, "Hello"))
        }
    }
    @Test fun neverInsertsIntoAChangedEditor() {
        var inserted = false
        DictationCoordinator.registerInserter { _, _ -> inserted = true; true }
        val first = DictationCoordinator.editorReady(); assertTrue(DictationCoordinator.start(first))
        DictationCoordinator.editorClosed(); DictationCoordinator.editorReady(); DictationCoordinator.process(first)
        assertFalse(DictationCoordinator.deliver(first, "Private words")); assertFalse(inserted)
        assertTrue(DictationCoordinator.state.value is DictationState.SavedToHistory)
    }
    @Test fun protectedFieldsAndCancellationRejectLateResults() {
        val first = DictationCoordinator.editorReady(); DictationCoordinator.start(first)
        DictationCoordinator.editorClosed(true); DictationCoordinator.process(first)
        assertFalse(DictationCoordinator.canInsert(first))
        DictationCoordinator.cancel(); assertFalse(DictationCoordinator.canInsert(first))
        val next = DictationCoordinator.editorReady(); assertTrue(DictationCoordinator.start(next))
    }
}
