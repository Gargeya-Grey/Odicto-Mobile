package app.odicto.mobile

import app.odicto.mobile.ime.CursorNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cursor movement is expressed as a selection range; it must never split a surrogate pair. */
class CursorNavigatorTest {
    @Test fun emojiModifiersFlagsAndCombiningSequencesStayWholeInBothDirections() {
        for (cluster in listOf("👍🏽", "🇺🇸", "👩🏽‍💻", "e\u0301", "\r\n")) {
            val text = "a${cluster}b"
            assertEquals(cluster.length + 1 to cluster.length + 1, CursorNavigator.character(text, 1, 1, 1))
            assertEquals(1 to 1, CursorNavigator.character(text, cluster.length + 1, cluster.length + 1, -1))
        }
        val flags = "🇺🇸🇨🇦"
        assertEquals(4 to 4, CursorNavigator.character(flags, 0, 0, 1))
        assertEquals(4 to 4, CursorNavigator.character(flags, 8, 8, -1))
    }

    @Test fun aCharacterStepMovesOnePosition() {
        assertEquals(4 to 4, CursorNavigator.character("hello world", 3, 3, 1))
        assertEquals(2 to 2, CursorNavigator.character("hello world", 3, 3, -1))
    }

    @Test fun aCharacterStepNeverSplitsAnEmoji() {
        // "a👍b" is five UTF-16 units; the emoji is one character of two units.
        val text = "a😀b"
        assertEquals(3 to 3, CursorNavigator.character(text, 1, 1, 1))
        assertEquals(1 to 1, CursorNavigator.character(text, 3, 3, -1))
    }

    @Test fun backwardMovementDoesNotSplitJoinedEmoji() {
        val text = "a👩‍💻b"
        assertEquals(1 to 1, CursorNavigator.character(text, text.length - 1, text.length - 1, -1))
    }

    @Test fun boundedWindowKeepsAbsoluteCursorOffsets() {
        assertEquals(5_002 to 5_002, CursorNavigator.characterInWindow("abcde", 5_000, 5_001, 1))
        assertEquals(5_000 to 5_000, CursorNavigator.characterInWindow("abcde", 5_000, 5_001, -1))
        assertNull(CursorNavigator.characterInWindow("abcde", 5_000, 5_020, 1))
    }

    @Test fun movementClampsAtBothEnds() {
        assertEquals(0 to 0, CursorNavigator.character("hello", 0, 0, -5))
        assertEquals(5 to 5, CursorNavigator.character("hello", 5, 5, 5))
    }

    @Test fun aLiveSelectionCollapsesToItsFarEdge() {
        // Collapsing to the trailing edge is what lets a drag continue from where the words sit.
        assertEquals(5 to 5, CursorNavigator.character("hello", 1, 4, 1))
    }

    @Test fun extendingMovesOnlyTheMovingEdge() {
        assertEquals(0 to 2, CursorNavigator.extendCharacters("hello world", 0, 0, 2))
        assertEquals(2 to 4, CursorNavigator.extendCharacters("hello world", 4, 4, -2))
    }

    @Test fun extendingNeverCrossesTheFixedEdge() {
        // Extending left from a selection that already starts at 0 cannot move before it.
        assertEquals(0 to 4, CursorNavigator.extendCharacters("hello", 0, 4, -3))
    }

    @Test fun wordStepsLandOnWordBoundaries() {
        val text = "hello brave world"
        // One forward step leaves "hello" and its trailing space together, landing on "brave".
        assertEquals(6 to 6, CursorNavigator.word(text, 0, 0, 1))
        assertEquals(12 to 12, CursorNavigator.word(text, 0, 0, 2))
        assertEquals(17 to 17, CursorNavigator.word(text, 0, 0, 3))
        // Walking back lands on the start of the preceding word.
        assertEquals(12 to 12, CursorNavigator.word(text, 17, 17, -1))
        assertEquals(6 to 6, CursorNavigator.word(text, 17, 17, -2))
        assertEquals(0 to 0, CursorNavigator.word(text, 17, 17, -3))
    }

    @Test fun wordStepsClampAtTheEdges() {
        assertEquals(0 to 0, CursorNavigator.word("hello", 0, 0, -3))
        assertEquals(5 to 5, CursorNavigator.word("hello", 5, 5, 3))
    }

    @Test fun wordsDoNotSplitAnEmojiWhenWalkingBackwards() {
        val text = "hi 😀 there"
        val result = CursorNavigator.word(text, text.length, text.length, -1)!!
        // The emoji is one character of two units, so the walk must not land between them.
        val insidePair = result.first == 4 || result.first == 5
        assertEquals(false, insidePair)
        assertEquals(true, result.first in 0..text.length)
    }

    @Test fun noMovementIsRequestedForZeroStepsOrEmptyText() {
        assertNull(CursorNavigator.character("hello", 2, 2, 0))
        assertNull(CursorNavigator.character("", 0, 0, 1))
        assertNull(CursorNavigator.word("hello", 0, 0, 0))
        assertNull(CursorNavigator.extendCharacters("hello", 1, 1, 0))
    }
}
