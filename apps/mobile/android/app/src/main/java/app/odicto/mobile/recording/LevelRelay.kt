package app.odicto.mobile.recording

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands the microphone level to the main thread without making the capture thread wait for it. At most one update is
 * queued at a time and it applies the newest level, so a busy main thread skips meter frames instead of stalling capture.
 */
internal class LevelRelay(private val post: (() -> Unit) -> Unit, private val apply: (Float) -> Unit) {
    private val queued = AtomicBoolean(false)
    @Volatile private var latest = 0f

    fun offer(level: Float) {
        latest = level
        if (queued.compareAndSet(false, true)) post { queued.set(false); apply(latest) }
    }
}
