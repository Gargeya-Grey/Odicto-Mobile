package app.odicto.mobile

import app.odicto.mobile.dictation.VoiceUi
import org.junit.Assert.*
import org.junit.Test

class PreviewExpiryTest {
    @Test fun insertedResultClearsTextAndPreservesMode() {
        for (mode in listOf("raw", "ai", "live")) {
            val state = VoiceUi(phase = "done", mode = mode, finalText = "Words", resultId = "one", inserted = true)
            assertEquals(VoiceUi(mode = mode), state.expireResult("one"))
        }
    }
    @Test fun unverifiedResultRemainsAvailableForExplicitCopy() {
        val saved = VoiceUi(phase = "done", mode = "raw", finalText = "Copy me", resultId = "one", inserted = false)
        assertSame(saved, saved.expireResult("one"))
    }
    @Test fun staleTimerCannotClearNewResultOrRecording() {
        val state = VoiceUi(phase = "done", resultId = "two", inserted = true, finalText = "New words")
        assertSame(state, state.expireResult("one"))
        for (phase in listOf("connecting", "recording", "processing", "error")) {
            val busy = state.copy(phase = phase)
            assertSame(busy, busy.expireResult("two"))
        }
    }
    @Test fun anUnidentifiedResultIsNeverCleared() {
        val unknown = VoiceUi(phase = "done", finalText = "Copy me", resultId = "", inserted = true)
        assertSame(unknown, unknown.expireResult(""))
    }
    @Test fun aNoticeExpiresButARealFailureStays() {
        // The "no text field here" notice carries an id and clears itself; a provider failure has no id
        // and stays until the user dismisses it.
        val notice = VoiceUi(phase = "notice", message = "No text field here", resultId = "notice")
        assertEquals(VoiceUi(mode = notice.mode), notice.expireResult("notice"))
        val failure = VoiceUi(phase = "error", message = "OpenRouter quota reached", resultId = "")
        assertSame(failure, failure.expireResult("notice"))
    }
}
