package app.odicto.mobile

import app.odicto.mobile.dictation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DictationCoordinatorTest {
    private fun target(
        selection: () -> AiSelection? = { null },
        paste: (String) -> Boolean = { true },
        beginLive: () -> Boolean = { false },
        write: (String, Boolean) -> Boolean = { _, _ -> true },
        end: () -> Unit = {},
        connection: () -> Any = { Unit },
        generation: () -> Long = { 0 },
    ) = object : DictationTarget {
        override val id = DictationTarget.IME
        override val identity get() = connection()
        override val selectionVersion get() = generation()
        override fun readSelection() = selection()
        override fun insert(text: String) = paste(text)
        override fun beginLive() = beginLive()
        override fun writeLive(text: String, finish: Boolean) = write(text, finish)
        override fun endLive() = end()
    }

    @Test fun liveUpdatesUseOriginalEditorAndFinishWithoutOrdinaryPaste() {
        val writes = mutableListOf<Pair<String, Boolean>>()
        DictationCoordinator.registerTarget(target(beginLive = { true }, write = { text, finish -> writes.add(text to finish); true }, paste = { fail("Live must not paste twice"); true }))
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session)); assertTrue(DictationCoordinator.beginLive())
        assertTrue(DictationCoordinator.stream(session, "Hello"))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "Hello"))
        assertEquals(listOf("Hello" to false, "Hello" to true), writes)
        assertFalse(DictationCoordinator.stream(session, "late"))
    }
    @Test fun liveFocusLossRejectsFurtherTyping() {
        DictationCoordinator.registerTarget(target(beginLive = { true }, write = { _, _ -> fail("Wrong editor"); true }))
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session)); assertTrue(DictationCoordinator.beginLive())
        DictationCoordinator.editorClosed(); DictationCoordinator.editorReady()
        assertFalse(DictationCoordinator.stream(session, "private"))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "private"))
    }
    @Test fun aiReplacesOnlyTheOriginalSelectionAndClearsContext() {
        val selection = AiSelection(4, 9, "hello")
        var inserted = false
        DictationCoordinator.registerTarget(target(selection = { selection }, paste = { inserted = true; true }))
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        assertEquals(selection, DictationCoordinator.aiSelection)
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "hi"))
        assertTrue(inserted); assertNull(DictationCoordinator.aiSelection)
    }
    @Test fun aiRejectsMovedChangedAndMovedBackSelections() {
        for (change in listOf("move", "text", "moveBack")) {
            var current = AiSelection(4, 9, "hello")
            DictationCoordinator.registerTarget(target(selection = { current }, paste = { fail("Unsafe insertion"); true }))
            val session = DictationCoordinator.editorReady()
            assertTrue(DictationCoordinator.start(session, true))
            when (change) {
                "move" -> current = AiSelection(14, 19, "hello")
                "text" -> current = AiSelection(4, 9, "world")
                else -> { DictationCoordinator.selectionUpdated(9, 9); DictationCoordinator.selectionUpdated(4, 9) }
            }
            DictationCoordinator.process(session)
            assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "hi"))
            assertNull(DictationCoordinator.aiSelection)
        }
    }
    @Test fun aiCursorDraftAndUnavailableSelectionAreDistinct() {
        DictationCoordinator.registerTarget(target(selection = { null }))
        val session = DictationCoordinator.editorReady()
        assertFalse(DictationCoordinator.start(session, true))
        DictationCoordinator.registerTarget(target(selection = { AiSelection(5, 5, "") }))
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "draft"))
        DictationCoordinator.registerTarget(target(selection = { fail("Raw must not read context"); null }))
        assertTrue(DictationCoordinator.start(session))
    }
    @Test fun invalidOrOversizedSelectionsAreRejected() {
        assertFalse(AiSelection(-1, -1, "").valid)
        assertFalse(AiSelection(0, 8, "partial").valid)
        assertFalse(AiSelection(0, 20_001, "a".repeat(20_001)).valid)
        assertTrue(AiSelection(5, 0, "hello").matches(AiSelection(0, 5, "hello")))
    }
    @Test fun aiCancellationAndFocusLossNeverReplaceAndReleaseContext() {
        DictationCoordinator.registerTarget(target(selection = { AiSelection(0, 5, "hello") }, paste = { fail("Stale AI insertion"); true }))
        var session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.cancel(); assertNull(DictationCoordinator.aiSelection)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "late"))
        session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.editorClosed(true); DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "late"))
        assertNull(DictationCoordinator.aiSelection)
    }
    @Test fun unavailableEditorAtCommitFallsBackWithoutLosingResult() {
        DictationCoordinator.registerTarget(target(selection = { AiSelection(0, 5, "hello") }, paste = { throw IllegalStateException("Editor disconnected") }))
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "hi"))
        assertFalse(DictationCoordinator.state.value is DictationState.SavedToHistory)
        assertNull(DictationCoordinator.aiSelection)
    }
    @Test fun acceptedDeliveryIsNotConfirmedOrSaved() {
        DictationCoordinator.registerTarget(target())
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "result"))
        assertFalse(DictationCoordinator.state.value is DictationState.Completed)
        assertFalse(DictationCoordinator.state.value is DictationState.SavedToHistory)
    }
    @Test fun oldOperationCannotCompleteNewRecordingInSameEditor() {
        DictationCoordinator.registerTarget(target())
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, operationId = "old"))
        DictationCoordinator.cancel()
        assertTrue(DictationCoordinator.start(session, operationId = "new"))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "late", "old"))
        assertTrue(DictationCoordinator.busy)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "new", "new"))
    }
    @Test fun replacedTargetCannotReceiveAnExistingOperation() {
        DictationCoordinator.registerTarget(target())
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session))
        DictationCoordinator.registerTarget(target(paste = { fail("Wrong target"); true }))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "result"))
    }
    @Test fun rawSelectionChangesRejectDeliveryWithoutReadingContext() {
        var generation = 0L
        DictationCoordinator.registerTarget(target(generation = { generation }, selection = { fail("Raw context read"); null }, paste = { fail("Stale selection"); true }))
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session))
        generation += 2
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "result"))
    }
    @Test fun liveConnectionReplacementRejectsStreamingAndFinalization() {
        var connection = Any()
        DictationCoordinator.registerTarget(target(connection = { connection }, beginLive = { true }, write = { _, _ -> fail("Wrong connection"); true }))
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session))
        assertTrue(DictationCoordinator.beginLive())
        connection = Any()
        assertFalse(DictationCoordinator.stream(session, "partial"))
        DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(session, "result"))
    }
    @After fun reset() { DictationCoordinator.cancel(); DictationCoordinator.unregisterTarget(DictationTarget.IME); DictationCoordinator.unregisterTarget(DictationTarget.ACCESSIBILITY) }
    @Test fun repeatedRecordingsWorkWithoutRefocusing() {
        val session = DictationCoordinator.editorReady()
        DictationCoordinator.registerTarget(target())
        repeat(3) {
            assertTrue(DictationCoordinator.start(session)); assertFalse(DictationCoordinator.start(session))
            DictationCoordinator.process(session); assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "Hello"))
        }
    }
    @Test fun neverInsertsIntoAChangedEditor() {
        var inserted = false
        DictationCoordinator.registerTarget(target(paste = { inserted = true; true }))
        val first = DictationCoordinator.editorReady(); assertTrue(DictationCoordinator.start(first))
        DictationCoordinator.editorClosed(); DictationCoordinator.editorReady(); DictationCoordinator.process(first)
        assertEquals(DeliveryOutcome.REJECTED_STALE, DictationCoordinator.deliver(first, "Private words")); assertFalse(inserted)
        assertFalse(DictationCoordinator.state.value is DictationState.SavedToHistory)
    }
    @Test fun protectedFieldsAndCancellationRejectLateResults() {
        val first = DictationCoordinator.editorReady(); DictationCoordinator.start(first)
        DictationCoordinator.editorClosed(true); DictationCoordinator.process(first)
        assertFalse(DictationCoordinator.canInsert(first))
        DictationCoordinator.cancel(); assertFalse(DictationCoordinator.canInsert(first))
        val next = DictationCoordinator.editorReady(); assertTrue(DictationCoordinator.start(next))
    }
    @Test fun aiModeSelectsTheWholeFieldOnlyWhenNothingIsSelected() {
        var selected = 0
        var selection: AiSelection? = null
        DictationCoordinator.registerTarget(object : DictationTarget {
            override val id = DictationTarget.IME
            override fun readSelection() = selection
            override fun insert(text: String) = true
            override fun selectAll(): Boolean { selected++; return true }
        })
        assertTrue(DictationCoordinator.selectAllIfNothingSelected())
        assertEquals(1, selected)
        selection = AiSelection(0, 4, "text")
        assertFalse(DictationCoordinator.selectAllIfNothingSelected())
        assertEquals(1, selected)
    }
    @Test fun aiStartSelectsTheWholeFieldWhenNoSelectionIsActive() {
        var selection: AiSelection? = null
        DictationCoordinator.registerTarget(object : DictationTarget {
            override val id = DictationTarget.IME
            override fun readSelection() = selection
            override fun insert(text: String) = true
            override fun selectAll(): Boolean {
                selection = AiSelection(0, 4, "text")
                return true
            }
        })
        val session = DictationCoordinator.editorReady()
        assertTrue(DictationCoordinator.start(session, true))
        assertEquals(AiSelection(0, 4, "text"), DictationCoordinator.aiSelection)
    }
    @Test fun imeTargetWinsOnlyWhileTheImeOwnsTheEditor() {
        var ime = 0
        var accessibility = 0
        DictationCoordinator.registerTarget(object : DictationTarget {
            override val id = DictationTarget.ACCESSIBILITY
            override fun readSelection() = null
            override fun insert(text: String): Boolean { accessibility++; return true }
        })
        DictationCoordinator.registerTarget(object : DictationTarget {
            override val id = DictationTarget.IME
            override fun readSelection() = null
            override fun insert(text: String): Boolean { ime++; return true }
        })
        DictationCoordinator.setImeAttached(true)
        var session = DictationCoordinator.editorReady()
        DictationCoordinator.start(session); DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "a"))
        DictationCoordinator.setImeAttached(false)
        session = DictationCoordinator.editorReady()
        DictationCoordinator.start(session); DictationCoordinator.process(session)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, DictationCoordinator.deliver(session, "b"))
        assertEquals(1, ime); assertEquals(1, accessibility)
    }
}
