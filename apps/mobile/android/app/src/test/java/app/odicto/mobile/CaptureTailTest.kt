package app.odicto.mobile

import app.odicto.mobile.recording.CaptureTail
import app.odicto.mobile.recording.LevelRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTailTest {
    @Test fun theMicrophoneBuffersAtLeastOneSecondAndFourTimesThePlatformMinimum() {
        assertEquals(32_000, CaptureTail.bufferBytes(1_280))
        assertEquals(51_200, CaptureTail.bufferBytes(12_800))
        assertEquals("an error code from getMinBufferSize still gets one second", 32_000, CaptureTail.bufferBytes(-2))
    }

    @Test fun captureStopsAtTheFrameOfTheStopRequestPlusTheTail() {
        // Frame 16,000 was captured at 1.000 s; the stop request comes at 1.250 s; the tail is 150 ms (2,400 frames).
        assertEquals(16_000L + 4_000 + 2_400, CaptureTail.stopFrame(16_000, 1_000_000_000, 1_250_000_000))
        // A timestamp taken just after the request counts back to it.
        assertEquals(16_000L - 1_600 + 2_400, CaptureTail.stopFrame(16_000, 1_100_000_000, 1_000_000_000))
        assertEquals("no timestamp: the frames read plus 100 ms of capture delay and the tail", 12_000L, CaptureTail.fallbackStopFrame(8_000))
    }

    @Test fun afterTheStopRequestOnlyTheRemainingTailIsRead() {
        assertEquals("still recording", 3_200, CaptureTail.nextRead(3_200, 50_000, Long.MAX_VALUE))
        assertEquals(600, CaptureTail.nextRead(3_200, 10_000, 10_300))
        assertEquals(3_200, CaptureTail.nextRead(3_200, 10_000, 15_000))
        assertEquals("already covered: one sample, then the loop ends", 2, CaptureTail.nextRead(3_200, 10_000, 9_000))
    }

    @Test fun captureEndsWhenTheTailIsReadOrTheWaitRunsOut() {
        assertFalse(CaptureTail.done(10_000, 10_300, 100))
        assertTrue(CaptureTail.done(10_300, 10_300, 100))
        assertTrue("a microphone that stops delivering cannot hold the result back", CaptureTail.done(10_000, 10_300, CaptureTail.MAX_WAIT_MS))
    }

    @Test fun levelUpdatesNeverQueueBehindABusyMainThreadAndTheNewestWins() {
        val posted = mutableListOf<() -> Unit>()
        val applied = mutableListOf<Float>()
        val relay = LevelRelay({ posted.add(it) }) { applied.add(it) }
        relay.offer(0.1f); relay.offer(0.5f); relay.offer(0.9f)
        assertEquals("one update waits for the main thread, not three", 1, posted.size)
        assertTrue("offering never runs the update on the capture thread", applied.isEmpty())
        posted.removeAt(0)()
        assertEquals(listOf(0.9f), applied)
        relay.offer(0.2f)
        assertEquals(1, posted.size)
        posted.removeAt(0)()
        assertEquals(listOf(0.9f, 0.2f), applied)
    }
}
