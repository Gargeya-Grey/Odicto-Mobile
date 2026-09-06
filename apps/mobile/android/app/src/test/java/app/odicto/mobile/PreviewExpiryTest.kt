package app.odicto.mobile

import app.odicto.mobile.dictation.VoiceUi
import org.junit.Assert.*
import org.junit.Test

class PreviewExpiryTest {
    @Test fun insertedResultClearsTextAndPreservesMode() {
        for (mode in listOf("raw", "ai", "live")) {
            val state = VoiceUi(phase = "done", mode = mode, finalText = "Words", resultId = "one", inserted = true)
            assertEquals(VoiceUi(mode = mode), state.expireInserted("one"))
        }
    }
    @Test fun staleTimerCannotClearNewResultOrRecording() {
        val state = VoiceUi(phase = "done", resultId = "two", inserted = true, finalText = "New words")
        assertSame(state, state.expireInserted("one"))
        for (phase in listOf("connecting", "recording", "processing", "error")) {
            val busy = state.copy(phase = phase)
            assertSame(busy, busy.expireInserted("two"))
        }
    }
    @Test fun unsavedOrUnidentifiedResultsStayAvailable() {
        val saved = VoiceUi(phase = "done", finalText = "Copy me", resultId = "one", inserted = false)
        assertSame(saved, saved.expireInserted("one"))
        val unknown = saved.copy(inserted = true, resultId = "")
        assertSame(unknown, unknown.expireInserted(""))
    }
}
