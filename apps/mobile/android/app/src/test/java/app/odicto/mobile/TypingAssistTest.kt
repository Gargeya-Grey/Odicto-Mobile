package app.odicto.mobile

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.ime.BackspacePace
import app.odicto.mobile.ime.EditorTail
import app.odicto.mobile.ime.ImeActions
import app.odicto.mobile.ime.Punctuation
import app.odicto.mobile.ime.Suggestions
import app.odicto.mobile.ime.TypingAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingAssistTest {
    @Test fun aFreshFieldAndASentenceEndWantACapital() {
        assertTrue(TypingAssist.shouldAutoCap(""))
        assertTrue(TypingAssist.shouldAutoCap("Hello. "))
        assertTrue(TypingAssist.shouldAutoCap("Wait!\n"))
        assertFalse(TypingAssist.shouldAutoCap("Hello"))
        assertFalse(TypingAssist.shouldAutoCap("Hello "))
    }

    @Test fun aSecondSpaceAfterAWordBecomesAPeriod() {
        assertTrue(TypingAssist.periodForSecondSpace("Hi "))
        assertFalse(TypingAssist.periodForSecondSpace("Hi"))
        assertFalse(TypingAssist.periodForSecondSpace("Hi  "))
        assertFalse(TypingAssist.periodForSecondSpace("1 "))
    }

    @Test fun theFirstTenDeletesRemoveOneCharacterThenAWord() {
        assertEquals(1, BackspacePace.charCountToDelete("ab", 0))
        assertEquals(1, BackspacePace.charCountToDelete("ab", 9))
        assertEquals(2, BackspacePace.charCountToDelete("hi yo", 10))
        assertEquals(3, BackspacePace.charCountToDelete("hi yo ", 10))
        assertEquals(0, BackspacePace.charCountToDelete("", 10))
        assertFalse(BackspacePace.removesWord(9))
        assertTrue(BackspacePace.removesWord(10))
    }

    @Test fun aWordDeleteRemovesAWholeEmojiAndStopsAtTheSpace() {
        val thumbs = "x " + String(Character.toChars(0x1F44D))
        assertEquals(2, BackspacePace.charCountToDelete(thumbs, 10))
        assertEquals(2, BackspacePace.charCountToDelete(String(Character.toChars(0x1F44D)), 0))
    }

    @Test fun aShortHoldWaitsBeforeRepeatingAndWordsArriveSooner() {
        assertEquals(BackspacePace.HOLD_MS, BackspacePace.delayUntil(1))
        assertEquals(BackspacePace.LETTER_INTERVAL_MS, BackspacePace.delayUntil(2))
        assertEquals(BackspacePace.WORD_INTERVAL_MS, BackspacePace.delayUntil(10))
    }

    @Test fun aSearchFieldSubmitsAndAMultilineFieldKeepsTheNewline() {
        assertEquals(ImeActions.Kind.SEARCH, ImeActions.resolve(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH))
        assertEquals(ImeActions.Kind.GO, ImeActions.resolve(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO))
        assertEquals(ImeActions.Kind.SEND, ImeActions.resolve(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        assertEquals(
            ImeActions.Kind.NEWLINE,
            ImeActions.resolve(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
        assertEquals(ImeActions.Kind.DONE, ImeActions.resolve(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals(
            ImeActions.Kind.NEWLINE,
            ImeActions.resolve(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
        assertEquals(ImeActions.Kind.NEWLINE, ImeActions.resolve(null))
    }

    @Test fun rememberedTextAnswersTheNextKeyWithoutReadingAgain() {
        val tail = EditorTail()
        var reads = 0
        assertEquals("Hi", tail.textBefore(8) { reads += 1; "Hi" })
        assertEquals("Hi", tail.textBefore(8) { reads += 1; "NO" })
        assertEquals(1, reads)
        assertEquals(3, tail.afterInsert(2, "!"))
        assertEquals("Hi!", tail.knownSuffix())
        tail.clear()
        assertEquals(null, tail.knownSuffix())
        tail.seed("tail", 100)
        tail.afterDelete(100, 4)
        assertEquals(null, tail.knownSuffix())
        tail.seed("all", 3)
        tail.afterDelete(3, 3)
        assertEquals("", tail.knownSuffix())
        assertTrue(tail.complete)
    }

    @Test fun holdingCommaOffersOtherMarksWithTheFullStopFirstAndToTheRight() {
        val options = Punctuation.options(",")
        assertEquals(listOf(".", "!", "?", ":", ";", "'"), options)
        assertFalse(options!!.contains(","))
        assertEquals(0, Punctuation.homeIndex(","))
        assertEquals(0f, Punctuation.stripLeft(12f, 180f, 400f, 2f).let { it - 12f }, 0.01f)
        assertEquals(2, Punctuation.indexFromLeft(6, 12f, 12f + 34f * 2, 34f))
    }

    @Test fun suggestionsStayOnDeviceAndFollowThePrefix() {
        assertEquals(listOf("the", "than", "that"), Suggestions.matches("th"))
        assertTrue(Suggestions.matches("").isEmpty())
        assertTrue(Suggestions.matches("the").all { it.startsWith("the") && it != "the" })
    }
}
