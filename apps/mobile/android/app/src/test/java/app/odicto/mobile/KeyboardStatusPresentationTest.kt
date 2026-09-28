package app.odicto.mobile

import app.odicto.mobile.dictation.*
import app.odicto.mobile.ime.KeyboardStatusPresentation
import org.junit.Assert.*
import org.junit.Test

class KeyboardStatusPresentationTest {
    @Test fun deadlineDoesNotRestartForMeterOrPersistenceAndExpiryDoesNotMutateResult() {
        val presentation = KeyboardStatusPresentation()
        val result = VoiceUi(phase = "done", resultId = "one", finalText = "synthetic", message = "Check field", delivery = DeliveryOutcome.ACCEPTED_UNVERIFIED)
        presentation.voice(result, "one", 0, 3000)
        val first = presentation.current(0)!!
        presentation.voice(result.copy(level = 0.7f, historyPersistence = HistoryPersistence.SAVED, message = "Check field. Saved to history"), "one", 2000, 3000)
        assertEquals(first.deadline, presentation.current(2999)!!.deadline)
        assertEquals("Check field. Saved to history", presentation.current(2999)!!.description)
        assertNull(presentation.current(3000))
        presentation.voice(result, "one", 9000, 3000)
        assertNull(presentation.current(9000))
        assertEquals("synthetic", result.finalText)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, result.delivery)
    }

    @Test fun staleTimersCannotClearReplacementOrBusyAndPolishCannotResurrectVoice() {
        val p = KeyboardStatusPresentation()
        val voice = VoiceUi(phase = "error", message = "Voice error")
        p.voice(voice, "op", 0, 3000)
        val old = p.current(0)!!.generation
        p.notice("Polishing", true, 2000, 3000)
        p.expire(old, 10000)
        assertEquals("Polishing", p.current(10000)!!.summary)
        assertNull(p.current(10000)!!.deadline)
        p.notice("Field changed\nCopy available", false, 10000, 3000)
        assertEquals("Field changed Copy available", p.current(10000)!!.summary)
        assertEquals("Field changed\nCopy available", p.current(10000)!!.description)
        assertNull(p.current(13000))
        p.voice(voice, "op", 14000, 3000)
        assertNull(p.current(14000))
        p.voice(VoiceUi(phase = "recording", message = "Listening"), "next", 15000, 3000)
        assertEquals("Listening", p.current(99000)!!.summary)
    }

    @Test fun newActionsAndTerminalTransitionsGetTheirOwnAccessibleDeadline() {
        val p = KeyboardStatusPresentation()
        p.voice(VoiceUi(phase = "processing", message = "Processing"), "op", 0, 6000)
        p.voice(VoiceUi(phase = "done", resultId = "op", message = "Unverified"), "op", 10000, 6000)
        assertEquals(16000L, p.current(10000)!!.deadline)
        p.notice("Copied", false, 12000, 6000)
        assertEquals(18000L, p.current(12000)!!.deadline)
        p.notice("Copied", false, 14000, 6000)
        assertEquals(20000L, p.current(14000)!!.deadline)
    }
}
