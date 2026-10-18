package app.odicto.mobile

import app.odicto.mobile.dictation.VoiceUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard skips its status and spelling work on meter ticks, so only a level change may count as one. */
class VoiceUiMeterTickTest {
    private val recording = VoiceUi(phase = "recording", mode = "raw", message = "Listening", level = 0.2f)

    @Test fun aLevelChangeAloneIsAMeterTick() {
        assertTrue(recording.copy(level = 0.7f).sameExceptLevel(recording))
        assertTrue(recording.sameExceptLevel(recording))
    }

    @Test fun anyOtherChangeIsNot() {
        for (next in listOf(
            recording.copy(phase = "processing", level = 0.7f),
            recording.copy(message = "Connection is slow. Some audio may be missing."),
            recording.copy(interim = "hello"),
            recording.copy(finalText = "hello"),
            recording.copy(locked = true),
            recording.copy(resultId = "r1"),
        )) assertFalse(next.toString(), next.sameExceptLevel(recording))
    }
}
