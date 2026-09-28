package app.odicto.mobile.ime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyLatencyProbeTest {
    @Before fun clear() { KeyLatencyProbe.snapshot(clear = true) }

    @Test fun shortTrialSeparatesHelperRequestAndEditorReturn() {
        KeyLatencyProbe.haptic(100L, 110L, 130L, 140L)
        KeyLatencyProbe.commit(100L, 130L, 150L, 180L)
        val samples = KeyLatencyProbe.snapshot(clear = true)
        assertEquals(30L, samples.first { it.metric == KeyLatencyProbe.Metric.TOUCH_TO_VIBRATION_REQUEST }.durationNanos)
        assertEquals(20L, samples.first { it.metric == KeyLatencyProbe.Metric.HAPTIC_PREPARATION }.durationNanos)
        assertEquals(10L, samples.first { it.metric == KeyLatencyProbe.Metric.VIBRATION_API }.durationNanos)
        assertEquals(80L, samples.first { it.metric == KeyLatencyProbe.Metric.TOUCH_TO_EDITOR_RETURN }.durationNanos)
        assertEquals(30L, samples.first { it.metric == KeyLatencyProbe.Metric.EDITOR_API }.durationNanos)
        val summary = KeyLatencyProbe.summarize(samples)
        assertTrue(summary.contains("p99="))
        assertTrue(summary.contains("max="))
        assertTrue(summary.contains("count=1"))
        assertTrue(KeyLatencyProbe.snapshot().isEmpty())
    }

    @Test fun ringKeepsOnlyBoundedRecentSamplesAndNeverFormatsOnRecord() {
        repeat(2000) { KeyLatencyProbe.cursor(it.toLong(), it.toLong() + 5L) }
        val samples = KeyLatencyProbe.snapshot()
        assertEquals(1024, samples.size)
        assertEquals(976L, samples.first().startNanos)
        assertEquals(1999L, samples.last().startNanos)
    }

    @Test fun invalidIntervalsAreDiscardedAndFrameRemainsOnlyBatchCloseEvidence() {
        KeyLatencyProbe.cursor(20, 10)
        KeyLatencyProbe.commit(100, 0, 90, 120)
        KeyLatencyProbe.frame(100, 140)
        val samples = KeyLatencyProbe.snapshot()
        assertEquals(1, samples.size)
        assertEquals(KeyLatencyProbe.Metric.TOUCH_TO_BATCH_CLOSE, samples.single().metric)
    }
}
