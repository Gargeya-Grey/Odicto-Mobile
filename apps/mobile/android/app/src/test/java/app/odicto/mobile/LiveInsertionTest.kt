package app.odicto.mobile

import app.odicto.mobile.dictation.*
import org.junit.Assert.*
import org.junit.Test

class LiveInsertionTest {
    @Test fun revisesOneComposingRangeWithoutFinalDuplication() {
        val writer = LiveInsertion(AiSelection(4, 4, ""))
        val writes = mutableListOf<String>()
        val compose: (String) -> Boolean = { writes.add(it); true }
        assertTrue(writer.update(AiSelection(4, 4, ""), "", "Hel", compose))
        assertTrue(writer.update(AiSelection(7, 7, ""), "Hel", "Hello", compose))
        assertTrue(writer.update(AiSelection(9, 9, ""), "Hello", "Hello", compose))
        assertEquals(listOf("Hel", "Hello"), writes)
    }
    @Test fun selectionIsReplacedOnceThenRevisedInPlace() {
        val writer = LiveInsertion(AiSelection(2, 5, "old"))
        assertTrue(writer.update(AiSelection(2, 5, "old"), "", "new", { true }))
        assertTrue(writer.update(AiSelection(5, 5, ""), "new", "new words", { true }))
    }
    @Test fun movedCursorEditedTextAndFailedCompositionStopFurtherWrites() {
        for (changed in listOf("cursor", "text", "failure")) {
            val writer = LiveInsertion(AiSelection(0, 0, ""))
            assertTrue(writer.update(AiSelection(0, 0, ""), "", "hello", { true }))
            val current = AiSelection(if (changed == "cursor") 4 else 5, if (changed == "cursor") 4 else 5, "")
            assertFalse(writer.update(current, if (changed == "text") "world" else "hello", "hello there", { changed != "failure" }))
            assertFalse(writer.update(AiSelection(5, 5, ""), "hello", "late", { fail("Stopped writer called editor"); true }))
        }
    }
}
