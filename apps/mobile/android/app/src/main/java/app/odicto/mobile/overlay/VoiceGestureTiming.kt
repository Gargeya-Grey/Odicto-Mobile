package app.odicto.mobile.overlay

class VoiceGestureTiming {
    private var previousTap: Long? = null
    fun reset() { previousTap = null }
    fun release(downAt: Long, upAt: Long, moved: Boolean): Boolean {
        if (moved || upAt - downAt >= HOLD_MS) { reset(); return false }
        val previous = previousTap
        val doubleTap = previous != null && upAt - previous in 0..DOUBLE_TAP_MS
        previousTap = if (doubleTap) null else upAt
        return doubleTap
    }
    companion object { const val HOLD_MS = 180L; const val DOUBLE_TAP_MS = 500L }
}
