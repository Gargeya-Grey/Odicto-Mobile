package app.odicto.mobile.dictation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.odicto.mobile.recording.AudioCaptureService
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

enum class HistoryPersistence { PENDING, SAVED, FAILED }

data class VoiceUi(val phase: String = "idle", val mode: String = "ai", val locked: Boolean = false, val finalText: String = "", val interim: String = "", val message: String = "", val level: Float = 0f, val resultId: String = "", val inserted: Boolean = false, val delivery: DeliveryOutcome? = null, val historyPersistence: HistoryPersistence? = null) {
    val active get() = phase == "connecting" || phase == "recording"
    val busy get() = active || phase == "processing"
    /** Ends a result or notice preview. A stale timer must never clear a newer result, a notice, or a live session. */
    fun expireResult(expectedId: String): VoiceUi = if (((phase == "done" && inserted) || (phase == "notice" && finalText.isEmpty() && interim.isEmpty())) && resultId == expectedId && expectedId.isNotEmpty()) VoiceUi(mode = mode) else this
}
object VoiceSession {
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previewExpiry: Job? = null
    private val mutable = MutableStateFlow(VoiceUi())
    val state = mutable.asStateFlow()
    var session: Long = -1; private set
    var transportActive = false
    fun update(transform: (VoiceUi) -> VoiceUi) { mutable.value = transform(mutable.value) }
    var operationId: String = ""; private set
    fun owns(id: String) = id.isNotEmpty() && operationId == id
    fun completed(id: String, output: String, outcome: DeliveryOutcome) {
        if (!owns(id)) return
        previewExpiry?.cancel()
        val inserted = outcome == DeliveryOutcome.CONFIRMED
        update { it.copy(phase = "done", finalText = output, interim = "", level = 0f, resultId = id, inserted = inserted, delivery = outcome, historyPersistence = HistoryPersistence.PENDING,
            message = when (outcome) {
                DeliveryOutcome.CONFIRMED -> "Inserted"
                DeliveryOutcome.ACCEPTED_UNVERIFIED -> "Insertion unverified · check field before pasting · Copy available"
                DeliveryOutcome.REJECTED_STALE -> "Delivery failed or editor changed · check field before pasting · Copy available"
            }) }
    }
    fun historyPersisted(id: String, saved: Boolean) {
        update { if (it.resultId != id || !owns(id) || it.phase != "done") it else
            it.copy(historyPersistence = if (saved) HistoryPersistence.SAVED else HistoryPersistence.FAILED, message = it.message.substringBefore(" · Saved").substringBefore(" · History unavailable") +
                if (saved) " · Saved to history" else " · History unavailable · copy your result") }
        if (saved && mutable.value.resultId == id && mutable.value.inserted) {
            previewExpiry?.cancel()
            previewExpiry = previewScope.launch { delay(2_000); update { it.expireResult(id) } }
        }
    }
    fun start(context: Context, locked: Boolean = false) {
        if (mutable.value.busy || transportActive) return
        val editor = DictationCoordinator.activeEditorSession ?: return
        val id = java.util.UUID.randomUUID().toString()
        if (!DictationCoordinator.start(editor, VoicePreferences.state.value.mode == "ai", id)) {
            update { it.copy(phase = "error", message = "Cannot read this selection. Reselect up to 20,000 characters in a supported editor.") }
            return
        }
        session = editor; operationId = id
        if (VoicePreferences.state.value.mode == "live" && !DictationCoordinator.beginLive()) {
            fail("This editor cannot accept Live typing. Try Raw mode."); return
        }
        transportActive = true
        mutable.value = VoiceUi("connecting", VoicePreferences.state.value.mode, locked, message = "Connecting…")
        try { ContextCompat.startForegroundService(context, Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_START).putExtra(AudioCaptureService.EXTRA_SESSION, editor).putExtra(AudioCaptureService.EXTRA_OPERATION, id)) }
        catch (_: Exception) { transportActive = false; fail("Open Odicto and allow microphone access, or use the keyboard mic.") }
    }
    fun lock() { if (mutable.value.active) update { it.copy(locked = true) } }
    fun mode(context: Context, mode: String) {
        if (mutable.value.busy || mode !in listOf("raw", "ai", "live")) return
        VoicePreferences.update(context) { it.copy(mode = mode) }; update { it.copy(mode = mode) }
        // AI rewrites the field, so select everything up front; tapping a cursor afterwards deselects it.
        if (mode == "ai") DictationCoordinator.selectAllIfNothingSelected()
    }
    fun finish(context: Context) {
        if (!mutable.value.active) return
        update { it.copy(phase = "processing", locked = false, level = 0f, message = "Finishing…") }
        DictationCoordinator.process(session, operationId)
        // A rejected background service start means the capture service is already gone, not that the
        // process may die: resolve the session locally instead of throwing on the main thread.
        if (!command(context, AudioCaptureService.ACTION_STOP)) { transportActive = false; fail("Recording service stopped. Start again in the intended field.") }
    }
    fun cancel(context: Context) {
        if (!mutable.value.busy) return
        if (!command(context, AudioCaptureService.ACTION_CANCEL)) transportActive = false
        DictationCoordinator.cancel(); operationId = ""; mutable.value = VoiceUi(mode = VoicePreferences.state.value.mode)
    }
    fun fail(message: String) {
        previewExpiry?.cancel()
        DictationCoordinator.fail(message)
        // No result id: a real failure stays readable until the user dismisses it.
        update { it.copy(phase = "error", locked = false, level = 0f, message = message, resultId = "") }
    }

    /** A short explanation that clears itself, for taps that cannot start a recording. */
    fun notice(message: String) {
        previewExpiry?.cancel()
        val id = "notice"
        // "notice" is deliberately not "error": a failure stays until the user dismisses it, this clears.
        update { it.copy(phase = "notice", locked = false, level = 0f, message = message, resultId = id, inserted = false) }
        previewExpiry = previewScope.launch {
            delay(20_000)
            update { it.expireResult(id) }
        }
    }
    /** Android refuses background service starts; the bubble runs in the background while the capture service can already be gone. */
    private fun command(context: Context, action: String): Boolean = try {
        context.startService(Intent(context, AudioCaptureService::class.java).setAction(action).putExtra(AudioCaptureService.EXTRA_OPERATION, operationId)); true
    } catch (_: Exception) { false }
}
