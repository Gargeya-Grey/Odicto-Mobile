package app.odicto.mobile

import app.odicto.mobile.overlay.VoiceGestureTiming
import org.junit.Assert.*
import org.junit.Test

class VoiceGestureTimingTest {
    @Test fun twoShortTapsWithinHalfASecondExpandOnlyOnce() {
        val gesture = VoiceGestureTiming()
        assertFalse(gesture.release(0, 50, false))
        assertTrue(gesture.release(450, 550, false))
        assertFalse(gesture.release(600, 650, false))
    }
    @Test fun holdingAndDraggingNeverCountAsDoubleTap() {
        val gesture = VoiceGestureTiming()
        assertFalse(gesture.release(1000, 1040, false))
        assertFalse(gesture.release(1100, 1280, false))
        assertFalse(gesture.release(1300, 1320, false))
        assertFalse(gesture.release(1350, 1390, true))
        assertFalse(gesture.release(1400, 1440, false))
    }
    @Test fun expiredTapAndCancellationDoNotExpand() {
        val gesture = VoiceGestureTiming()
        assertFalse(gesture.release(0, 50, false)); assertFalse(gesture.release(500, 551, false))
        gesture.reset(); assertFalse(gesture.release(600, 650, false))
    }
}
