package app.odicto.mobile.ime

import org.junit.Assert.*
import org.junit.Test

class PasteSuggestionTest {
    private var now = 1_000_000L
    private val suggestion = PasteSuggestion({ now })

    @Test fun aFreshCopyIsOfferedAndExpiresThirtySecondsAfterTheCopyItself() {
        assertTrue(suggestion.offer("hello", "t1", now - 10_000))
        assertEquals("hello", suggestion.current()?.text)
        assertEquals(now - 10_000 + 30_000, suggestion.expiresAt())
        now += 19_999
        assertNotNull(suggestion.current())
        now += 1
        assertNull("30s after the copy, not after it was noticed", suggestion.current())
        assertNull(suggestion.expiresAt())
    }

    @Test fun aCopyOlderThanTheWindowIsNeverOffered() {
        assertFalse(suggestion.offer("old", "t1", now - 30_000))
        assertFalse(suggestion.offer("older", "t2", now - 120_000))
        assertNull(suggestion.current())
    }

    @Test fun anUnknownOrFutureTimestampIsNotOffered() {
        assertFalse(suggestion.offer("x", "t1", 0L))
        assertFalse(suggestion.offer("x", "t2", now + 60_000))
        assertFalse(suggestion.offer("   ", "t3", now))
    }

    @Test fun spendingEndsTheOfferForThatCopyOnlyAndTheSameSampleCannotRevive() {
        assertTrue(suggestion.offer("one", "t1", now - 1_000))
        suggestion.spend()
        assertNull(suggestion.current())
        assertFalse("the same clip sampled again in another field", suggestion.offer("one", "t1", now - 1_000))
        assertTrue("a genuine new copy is offered again", suggestion.offer("two", "t2", now))
        assertEquals("two", suggestion.current()?.text)
    }

    @Test fun theSameCopySampledAgainChangesNothingAndAnOlderOneDoesNotReplaceANewerOne() {
        assertTrue(suggestion.offer("new", "t2", now - 1_000))
        assertFalse(suggestion.offer("new", "t2", now - 1_000))
        assertFalse(suggestion.offer("older", "t1", now - 5_000))
        assertEquals("new", suggestion.current()?.text)
        assertTrue(suggestion.offer("newest", "t3", now))
        assertEquals("newest", suggestion.current()?.text)
    }

    @Test fun spendingWithNothingOfferedIsHarmless() {
        suggestion.spend()
        assertTrue(suggestion.offer("fine", "t1", now))
    }

    @Test fun previewIsOneShortLineWithAnEllipsisWhenCut() {
        assertEquals("see you at 5", PasteSuggestion.preview("  see you\n\n at   5 "))
        val long = "a ".repeat(40)
        val preview = PasteSuggestion.preview(long)!!
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 29)
    }

    @Test fun passwordLikeTokensAreNeverPrintedButCodesLinksAndSentencesAre() {
        assertNull(PasteSuggestion.preview("Tr0ub4dor&3xyz"))
        assertNull(PasteSuggestion.preview("p@ssw0rdLong"))
        assertNull(PasteSuggestion.preview("aB3\$kL9#qW2"))
        assertEquals("482913", PasteSuggestion.preview("482913"))
        assertEquals("https://example.com/a?b=1", PasteSuggestion.preview("https://example.com/a?b=1"))
        assertEquals("Meet me at Gate 4 tomorrow", PasteSuggestion.preview("Meet me at Gate 4 tomorrow"))
        assertEquals("hello", PasteSuggestion.preview("hello"))
        assertEquals("elephantine", PasteSuggestion.preview("elephantine"))
    }
}
