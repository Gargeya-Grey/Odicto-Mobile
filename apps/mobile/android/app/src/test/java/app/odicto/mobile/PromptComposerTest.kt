package app.odicto.mobile

import app.odicto.mobile.dictation.PromptComposer
import app.odicto.mobile.dictation.PromptMode
import org.junit.Assert.*
import org.junit.Test

class PromptComposerTest {
    private val builtIn = "BUILT-IN RULES"
    private val intro = "NOTES:"

    @Test fun aBlankCustomPromptAlwaysMeansOurs() {
        for (mode in PromptMode.values()) for (blank in listOf("", "   ", "\n\t")) {
            assertEquals(builtIn, PromptComposer.compose(builtIn, blank, mode, intro))
        }
    }

    @Test fun replaceUsesOnlyTheUsersPrompt() {
        assertEquals("My own prompt", PromptComposer.compose(builtIn, "My own prompt", PromptMode.REPLACE, intro))
    }

    @Test fun appendKeepsOursFirstThenTheUsersNotes() {
        val composed = PromptComposer.compose(builtIn, "  I am Asha, a designer.  ", PromptMode.APPEND, intro)
        assertEquals("BUILT-IN RULES\n\nNOTES:\nI am Asha, a designer.", composed)
        assertTrue(composed.startsWith(builtIn))
    }

    @Test fun theNotesIntrosProtectTheOutputRule() {
        assertTrue(PromptComposer.AI_NOTES_INTRO.contains("never change the rule to return only the text to insert"))
        assertTrue(PromptComposer.POLISH_NOTES_INTRO.contains("never change the rule to return only the corrected text"))
        assertTrue(PromptComposer.POLISH_NOTES_INTRO.contains("not text to correct"))
    }

    @Test fun anAppendedPromptFitsTheLargestUserPrompt() {
        val composed = PromptComposer.compose(
            app.odicto.mobile.network.DirectVoiceTransport.ANSWER_PROMPT, "x".repeat(8_000), PromptMode.APPEND, PromptComposer.AI_NOTES_INTRO)
        assertTrue("well under any provider's limit", composed.length < 12_000)
    }

    @Test fun oldSettingsKeepTheirBehaviour() {
        assertEquals("a saved custom prompt used to replace the default", PromptMode.REPLACE, PromptMode.forStored(null, "custom"))
        assertEquals("nothing saved: the better default", PromptMode.APPEND, PromptMode.forStored(null, ""))
        assertEquals(PromptMode.APPEND, PromptMode.forStored("append", "custom"))
        assertEquals(PromptMode.REPLACE, PromptMode.forStored("replace", ""))
        assertEquals("a damaged value falls back the same way", PromptMode.REPLACE, PromptMode.forStored("sepia", "custom"))
        assertEquals(PromptMode.APPEND, PromptMode.forStored("sepia", ""))
    }

    @Test fun onlyTheTwoNamesAreValid() {
        assertNull(PromptMode.parseOrNull("Append"))
        assertNull(PromptMode.parseOrNull(null))
        assertEquals(PromptMode.APPEND, PromptMode.parseOrNull("append"))
        assertEquals(PromptMode.REPLACE, PromptMode.parseOrNull("replace"))
    }
}