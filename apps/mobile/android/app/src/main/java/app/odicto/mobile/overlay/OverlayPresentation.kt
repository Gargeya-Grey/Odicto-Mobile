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
    private var optionsDeadline: Long? = null
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
        optionsDeadline = null
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
        val now = SystemClock.elapsedRealtime()
        if (options && optionsDeadline?.let { now >= it } == true) collapse()
        if (deadline?.let { now >= it } == true) collapse()
        return !dismissed && deadline != null
    }

    /** Time until the next thing folds away: the result card's moment, or the options row going idle. */
    fun remaining(): Long? {
        visible()
        val now = SystemClock.elapsedRealtime()
        val toResult = deadline?.minus(now)
        val toOptions = if (options) optionsDeadline?.minus(now) else null
        return listOfNotNull(toResult, toOptions).minOrNull()
    }

    fun collapse() { dismissed = true; options = false; deadline = null; optionsDeadline = null }

    /**
     * The options row and the last result it brings along stay open while they are being used, then fold away on their
     * own like every other message. Opening them again shows them again, so Copy is never lost.
     */
    fun reopen() { options = true; dismissed = false; deadline = null; optionsDeadline = SystemClock.elapsedRealtime() + optionsIdle() }

    /** A touch on the open options keeps them open a while longer. */
    fun touch() { if (options) optionsDeadline = SystemClock.elapsedRealtime() + optionsIdle() }

    private fun optionsIdle() = maxOf(OPTIONS_IDLE_MS, timeout)

    const val OPTIONS_IDLE_MS = 8000L

    fun recommendedTimeout(context: Context): Long {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return if (Build.VERSION.SDK_INT >= 29) manager.getRecommendedTimeoutMillis(3000,
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong() else 3000L
    }
}
