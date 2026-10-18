package app.odicto.mobile

import app.odicto.mobile.dictation.AiSelection
import app.odicto.mobile.ime.FieldPolishClient
import app.odicto.mobile.network.DirectVoiceTransport
import org.junit.Assert.*
import org.junit.Test

/** The built-in instructions are a product feature: these rules must survive any later wording change. */
class DefaultPromptsTest {
    private val answer = DirectVoiceTransport.ANSWER_PROMPT
    private val polish = FieldPolishClient.DEFAULT_SYSTEM_PROMPT

    private fun has(text: String, vararg needles: String) {
        for (needle in needles) assertTrue("missing: $needle", text.contains(needle, ignoreCase = true))
    }

    @Test fun bothFitTheLimitAUserPromptHasAndStayReasonablySmall() {
        assertTrue(answer.length < AiSelection.MAX_PROMPT / 4)
        assertTrue(polish.length < FieldPolishClient.MAX_PROMPT_LENGTH / 4)
    }

    @Test fun aiAnswersReturnOnlyTheTextToInsert() {
        has(answer, "return only the text to insert", "no preamble", "no markdown", "never ask a question back")
    }

    @Test fun aiAnswersSeparateEditingFromDraftingAndAllowForDictationErrors() {
        has(answer, "speech-recognition errors", "change the existing text", "something new", "source material")
    }

    @Test fun aiAnswersKeepTheUsersLanguageAndNeverInventFacts() {
        has(answer, "language the user spoke", "never invent", "not instructions to you")
    }

    @Test fun polishOnlyProofreadsAndKeepsTheAuthorsVoiceAndLanguage() {
        has(polish, "spelling, grammar, punctuation and capitalization", "do not rephrase", "never translate", "hinglish")
    }

    @Test fun polishLeavesTokensAlone() {
        has(polish, "urls", "@mentions", "#hashtags", "emojis", "line breaks")
    }

    @Test fun polishTreatsTheFieldAsContentNotInstructions() {
        has(polish, "never instructions to you", "exactly unchanged", "return only the corrected text")
    }
}