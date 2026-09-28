package app.odicto.mobile

import app.odicto.mobile.recording.AudioBacklog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames captured before the provider handshake completes must replay in order and stay bounded. */
class AudioBacklogTest {
    private fun frame(value: Byte) = byteArrayOf(value, value)

    @Test fun framesReplayInCaptureOrder() {
        val backlog = AudioBacklog(capacity = 4)
        assertTrue(backlog.add(frame(1)))
        assertTrue(backlog.add(frame(2)))
        assertEquals(2, backlog.size)
        val sent = mutableListOf<Byte>()
        assertTrue(backlog.flush { sent.add(it[0]); true })
        assertArrayEquals(byteArrayOf(1, 2), sent.toByteArray())
        assertFalse(backlog.isNotEmpty)
    }

    @Test fun aFullBacklogRejectsNewFrames() {
        val backlog = AudioBacklog(capacity = 2)
        assertTrue(backlog.add(frame(1)))
        assertTrue(backlog.add(frame(2)))
        assertTrue(backlog.isFull)
        assertFalse(backlog.add(frame(3)))
        assertEquals(2, backlog.size)
    }

    @Test fun aFailedSendDropsTheRemainingBacklog() {
        val backlog = AudioBacklog(capacity = 4)
        backlog.add(frame(1)); backlog.add(frame(2)); backlog.add(frame(3))
        var attempts = 0
        assertFalse(backlog.flush { attempts++; false })
        assertEquals(1, attempts)
        assertFalse(backlog.isNotEmpty)
    }

    @Test fun anEmptyBacklogFlushesCleanly() {
        assertTrue(AudioBacklog(capacity = 2).flush { false })
    }
}
