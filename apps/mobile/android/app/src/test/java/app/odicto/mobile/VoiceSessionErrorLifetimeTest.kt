package app.odicto.mobile

import android.os.Looper
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** A failure message is read and then resets itself: nobody has to dismiss a stale error. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceSessionErrorLifetimeTest {
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before fun fresh() {
        VoiceSession.messageLifetimeMs = VoiceSession.MESSAGE_LIFETIME_MS
        VoiceSession.update { VoiceUi() }
    }

    @After fun restore() {
        VoiceSession.messageLifetimeMs = VoiceSession.MESSAGE_LIFETIME_MS
        VoiceSession.update { VoiceUi() }
    }

    @Test fun anErrorStaysForFiveSecondsThenResetsToIdle() {
        VoiceSession.fail("Add your Groq key in Voice settings.")
        assertEquals("error", VoiceSession.state.value.phase)
        looper.idleFor(Duration.ofMillis(4_900))
        assertEquals("still readable just before the limit", "error", VoiceSession.state.value.phase)
        looper.idleFor(Duration.ofMillis(200))
        assertEquals("idle", VoiceSession.state.value.phase)
        assertEquals("", VoiceSession.state.value.message)
    }

    @Test fun aNewerErrorRestartsTheClockAndIsNeverClearedByTheOldTimer() {
        VoiceSession.fail("first")
        looper.idleFor(Duration.ofMillis(3_000))
        VoiceSession.fail("second")
        looper.idleFor(Duration.ofMillis(3_000))
        assertEquals("second", VoiceSession.state.value.message)
        looper.idleFor(Duration.ofMillis(2_100))
        assertEquals("idle", VoiceSession.state.value.phase)
    }

    @Test fun aNoticeFollowsTheSameLifetime() {
        VoiceSession.notice("No text field for voice here.")
        looper.idleFor(Duration.ofMillis(4_900))
        assertEquals("notice", VoiceSession.state.value.phase)
        looper.idleFor(Duration.ofMillis(200))
        assertEquals("idle", VoiceSession.state.value.phase)
    }

    @Test fun usersWhoNeedMoreTimeToReadGetIt() {
        VoiceSession.messageLifetimeMs = 9_000
        VoiceSession.fail("slow reader")
        looper.idleFor(Duration.ofMillis(8_900))
        assertEquals("error", VoiceSession.state.value.phase)
        looper.idleFor(Duration.ofMillis(200))
        assertEquals("idle", VoiceSession.state.value.phase)
    }

    @Test fun aLaterStateIsNeverTouchedByAnEarlierErrorTimer() {
        VoiceSession.fail("old")
        VoiceSession.update { VoiceUi(phase = "processing", message = "Processing voice") }
        looper.idleFor(Duration.ofMillis(6_000))
        assertEquals("processing", VoiceSession.state.value.phase)
    }

    @Test fun expiringAnErrorKeepsTheUsersRetainedTextForCopy() {
        val retained = VoiceUi(phase = "error", mode = "raw", finalText = "hello world", message = "Delivery failed")
        val cleared = retained.expireError("Delivery failed")
        assertEquals("idle", cleared.phase)
        assertEquals("", cleared.message)
        assertEquals("hello world", cleared.finalText)
        assertEquals("raw", cleared.mode)
    }

    @Test fun expiringAnErrorWithNothingRetainedReturnsToTheBlankStateInTheSameMode() {
        val cleared = VoiceUi(phase = "error", mode = "live", message = "boom").expireError("boom")
        assertEquals(VoiceUi(mode = "live"), cleared)
    }

    @Test fun aDifferentMessageOrPhaseIsLeftAlone() {
        val error = VoiceUi(phase = "error", message = "boom")
        assertSame(error, error.expireError("something else"))
        val done = VoiceUi(phase = "done", message = "boom", finalText = "x")
        assertSame(done, done.expireError("boom"))
    }
}
