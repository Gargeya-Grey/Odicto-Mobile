package app.odicto.mobile.recording

/**
 * How the microphone buffers and when it may stop. After a stop request the capture loop reads on until the audio
 * covers the request plus a short tail, so a word spoken while the finger lifts is not cut off, and it never waits
 * longer than [MAX_WAIT_MS]. Frames are counted from the start of recording at 16 kHz mono.
 */
internal object CaptureTail {
    const val RATE = 16_000
    const val TAIL_MS = 150L
    const val MAX_WAIT_MS = 600L
    /** Assumed capture delay when Android gives no timestamp (Android 6, or the call fails). */
    private const val FALLBACK_LATENCY_MS = 100L

    /** One second of audio, and at least four times the platform minimum: a busy main thread cannot overrun it. */
    fun bufferBytes(minBufferBytes: Int) = maxOf(minBufferBytes * 4, RATE * 2)

    /** The frame the stop request plus the tail falls on, from an AudioTimestamp (frame [stampFrame] was captured at [stampNanos]). */
    fun stopFrame(stampFrame: Long, stampNanos: Long, requestNanos: Long): Long =
        stampFrame + (requestNanos - stampNanos) * RATE / 1_000_000_000L + TAIL_MS * RATE / 1000

    /** Without a timestamp: the frames already read, plus the assumed capture delay and the tail. */
    fun fallbackStopFrame(framesRead: Long): Long = framesRead + (FALLBACK_LATENCY_MS + TAIL_MS) * RATE / 1000

    /** Bytes to read next: a whole frame while recording, then only what is left before [stopFrame]. */
    fun nextRead(frameBytes: Int, framesRead: Long, stopFrame: Long): Int =
        if (stopFrame == Long.MAX_VALUE) frameBytes else ((stopFrame - framesRead) * 2).coerceIn(2L, frameBytes.toLong()).toInt()

    fun done(framesRead: Long, stopFrame: Long, waitedMs: Long) = framesRead >= stopFrame || waitedMs >= MAX_WAIT_MS
}
