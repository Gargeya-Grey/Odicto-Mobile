package app.odicto.mobile.recording

/**
 * Audio frames captured before the provider transport is ready. The microphone starts with the
 * request so the first words are not lost, and these frames are sent in order once it connects.
 * The backlog is bounded: a connection that never completes must not grow memory without limit.
 */
internal class AudioBacklog(private val capacity: Int) {
    private val frames = ArrayDeque<ByteArray>()
    val size: Int get() = frames.size
    val isNotEmpty: Boolean get() = frames.isNotEmpty()
    val isFull: Boolean get() = frames.size >= capacity
    /** Returns false when the backlog is full and the frame could not be kept. */
    fun add(frame: ByteArray): Boolean {
        if (isFull) return false
        frames.addLast(frame)
        return true
    }
    /**
     * Sends queued frames in order. A failed send means the transport is gone, so the rest of the
     * backlog is dropped and false is reported.
     */
    fun flush(send: (ByteArray) -> Boolean): Boolean {
        while (frames.isNotEmpty()) {
            val frame = frames.first()
            if (!send(frame)) { frames.clear(); return false }
            frames.removeFirst()
        }
        return true
    }
    fun clear() = frames.clear()
}
