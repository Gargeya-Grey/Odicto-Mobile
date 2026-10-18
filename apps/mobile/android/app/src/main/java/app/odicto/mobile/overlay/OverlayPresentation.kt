package app.odicto.mobile.overlay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import app.odicto.mobile.dictation.VoiceSession
import app.odicto.mobile.dictation.VoiceUi
import kotlinx.coroutines.*

object OverlayPresentation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var timeout = 3000L
    private var key: Triple<String, String, String>? = null
    private var deadline: Long? = null
    private var dismissed = false
    var options = false
        private set

    fun start(timeoutMillis: Long) {
        timeout = timeoutMillis
        VoiceSession.messageLifetimeMs = maxOf(VoiceSession.MESSAGE_LIFETIME_MS, timeoutMillis)
        if (observing) return
        observing = true
        scope.launch { VoiceSession.state.collect { observe(it) } }
    }

    fun observe(value: VoiceUi) {
        val next = Triple(VoiceSession.operationId, value.resultId, value.phase)
        if (key == next) return
        key = next
        options = false
        dismissed = false
        // A failure is given the time it needs to be read; the session then resets it, so the card and the
        // stored message end together and a later reopen can never bring a stale error back.
        deadline = when (value.phase) {
            "done" -> SystemClock.elapsedRealtime() + timeout
            "error", "notice" -> SystemClock.elapsedRealtime() + maxOf(timeout, VoiceSession.messageLifetimeMs)
            else -> null
        }
    }

    fun visible(): Boolean {
        if (deadline?.let { SystemClock.elapsedRealtime() >= it } == true) collapse()
        return !dismissed && deadline != null
    }

    fun remaining(): Long? = if (visible()) deadline?.minus(SystemClock.elapsedRealtime()) else null

    fun collapse() { dismissed = true; options = false; deadline = null }
    fun reopen() { options = true; dismissed = false; deadline = null }

    fun recommendedTimeout(context: Context): Long {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return if (Build.VERSION.SDK_INT >= 29) manager.getRecommendedTimeoutMillis(3000,
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong() else 3000L
    }
}
