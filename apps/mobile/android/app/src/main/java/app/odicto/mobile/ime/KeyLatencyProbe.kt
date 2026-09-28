package app.odicto.mobile.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.odicto.mobile.BuildConfig
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

internal object KeyLatencyProbe {
    enum class Metric {
        EVENT_QUEUE, HANDLER, TOUCH_TO_HIT, TOUCH_TO_HAPTIC_HELPER, HAPTIC_PREPARATION,
        TOUCH_TO_VIBRATION_REQUEST, VIBRATION_API, TOUCH_TO_EDITOR_SUBMISSION,
        TOUCH_TO_EDITOR_RETURN, EDITOR_API, TOUCH_TO_BATCH_CLOSE, CURSOR_API,
        REPEAT_LATENESS,
    }

    data class Sample(val eventId: Long, val metric: Metric, val startNanos: Long, val endNanos: Long) {
        val durationNanos: Long get() = endNanos - startNanos
    }

    private const val SIZE = 1024
    private val ids = LongArray(SIZE)
    private val metrics = arrayOfNulls<Metric>(SIZE)
    private val starts = LongArray(SIZE)
    private val ends = LongArray(SIZE)
    private var next = 0
    private var count = 0
    private val flushPending = AtomicBoolean(false)
    private val exportWorker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "OdictoProbeExport").apply { isDaemon = true } } }

    @Synchronized private fun record(id: Long, metric: Metric, start: Long, end: Long) {
        if (!BuildConfig.DEBUG || start < 0 || end < start) return
        ids[next] = id
        metrics[next] = metric
        starts[next] = start
        ends[next] = end
        next = (next + 1) % SIZE
        count = (count + 1).coerceAtMost(SIZE)
    }

    fun event(eventUptimeMillis: Long, entryElapsedNanos: Long, entryUptimeMillis: Long) {
        val queuedNanos = (entryUptimeMillis - eventUptimeMillis).coerceAtLeast(0L) * 1_000_000L
        record(entryElapsedNanos, Metric.EVENT_QUEUE, entryElapsedNanos - queuedNanos, entryElapsedNanos)
    }

    fun hit(touchAt: Long, hitAt: Long) {
        if (touchAt > 0) record(touchAt, Metric.TOUCH_TO_HIT, touchAt, hitAt)
    }

    fun handler(entryElapsedNanos: Long, endElapsedNanos: Long) {
        record(entryElapsedNanos, Metric.HANDLER, entryElapsedNanos, endElapsedNanos)
    }

    fun haptic(touchAt: Long, helperAt: Long, requestAt: Long, returnedAt: Long) {
        if (touchAt > 0) {
            record(touchAt, Metric.TOUCH_TO_HAPTIC_HELPER, touchAt, helperAt)
            record(touchAt, Metric.TOUCH_TO_VIBRATION_REQUEST, touchAt, requestAt)
        }
        record(touchAt, Metric.HAPTIC_PREPARATION, helperAt, requestAt)
        record(touchAt, Metric.VIBRATION_API, requestAt, returnedAt)
    }

    fun cursor(start: Long, end: Long) {
        record(0, Metric.CURSOR_API, start, end)
    }

    @Suppress("UNUSED_PARAMETER")
    fun commit(touchAt: Long, hapticAt: Long, commitStart: Long, commitEnd: Long) {
        if (touchAt <= 0 || commitEnd < commitStart || commitStart < touchAt) return
        record(touchAt, Metric.TOUCH_TO_EDITOR_SUBMISSION, touchAt, commitStart)
        record(touchAt, Metric.TOUCH_TO_EDITOR_RETURN, touchAt, commitEnd)
        record(touchAt, Metric.EDITOR_API, commitStart, commitEnd)
    }

    fun frame(touchAt: Long, frameAt: Long) {
        if (touchAt > 0) record(touchAt, Metric.TOUCH_TO_BATCH_CLOSE, touchAt, frameAt)
    }

    fun repeatDeadline(deadlineUptimeMillis: Long, nowUptimeMillis: Long) {
        if (!BuildConfig.DEBUG || nowUptimeMillis < deadlineUptimeMillis) return
        val now = SystemClock.elapsedRealtimeNanos()
        val lateness = (nowUptimeMillis - deadlineUptimeMillis) * 1_000_000L
        record(0, Metric.REPEAT_LATENESS, now - lateness, now)
    }

    @Synchronized fun snapshot(clear: Boolean = false): List<Sample> {
        val result = List(count) { offset ->
            val index = (next - count + offset + SIZE) % SIZE
            Sample(ids[index], metrics[index]!!, starts[index], ends[index])
        }
        if (clear) { count = 0; next = 0 }
        return result
    }

    fun summarize(samples: List<Sample>): String = Metric.entries.mapNotNull { metric ->
        val values = samples.filter { it.metric == metric }.map { it.durationNanos }.sorted()
        if (values.isEmpty()) return@mapNotNull null
        fun ms(value: Long) = String.format(Locale.US, "%.3f", value / 1_000_000.0)
        fun at(percent: Double) = ms(values[(ceil(values.size * percent).toInt() - 1).coerceAtLeast(0)])
        "${metric.name} count=${values.size} ms:p50=${at(.50)},p95=${at(.95)},p99=${at(.99)},max=${ms(values.last())}"
    }.joinToString("\n")

    fun flushAsync(onSummary: (String) -> Unit = { Log.i("OdictoKeyLatency", it) }) {
        if (!BuildConfig.DEBUG || !flushPending.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            val samples = snapshot(clear = true)
            exportWorker.execute {
                try { onSummary(summarize(samples)) } finally { flushPending.set(false) }
            }
        }
    }
}
