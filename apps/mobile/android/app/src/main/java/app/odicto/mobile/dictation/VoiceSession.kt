package app.odicto.mobile.dictation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.odicto.mobile.recording.AudioCaptureService
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

data class VoiceUi(val phase: String = "idle", val mode: String = "ai", val locked: Boolean = false, val finalText: String = "", val interim: String = "", val message: String = "", val level: Float = 0f, val resultId: String = "", val inserted: Boolean = false) {
    val active get() = phase == "connecting" || phase == "recording"
    val busy get() = active || phase == "processing"
    fun expireInserted(expectedId: String): VoiceUi = if (phase == "done" && inserted && resultId == expectedId && expectedId.isNotEmpty()) VoiceUi(mode = mode) else this
}
object VoiceSession {
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previewExpiry: Job? = null
    private val mutable = MutableStateFlow(VoiceUi())
    val state = mutable.asStateFlow()
    var session: Long = -1; private set
    var transportActive = false
    fun update(transform: (VoiceUi) -> VoiceUi) { mutable.value = transform(mutable.value) }
    fun completed(id: String, output: String, inserted: Boolean) {
        previewExpiry?.cancel()
        update { it.copy(phase = "done", finalText = output, interim = "", level = 0f, resultId = id, inserted = inserted,
            message = if (inserted) "Inserted" else "Saved · editor or selection changed") }
        if (inserted) previewExpiry = previewScope.launch {
            delay(5_000)
            update { it.expireInserted(id) }
        }
    }
    fun start(context: Context, locked: Boolean = false) {
        if (mutable.value.busy || transportActive) return
        val editor = DictationCoordinator.activeEditorSession ?: return
        if (!DictationCoordinator.start(editor, VoicePreferences.state.value.mode == "ai")) {
            update { it.copy(phase = "error", message = "Cannot read this selection. Reselect up to 20,000 characters in a supported editor.") }
            return
        }
        session = editor
        if (VoicePreferences.state.value.mode == "live" && !DictationCoordinator.beginLive()) {
            fail("This editor cannot accept Live typing. Try Raw mode."); return
        }
        transportActive = true
        mutable.value = VoiceUi("connecting", VoicePreferences.state.value.mode, locked, message = "Connecting…")
        try { ContextCompat.startForegroundService(context, Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_START).putExtra(AudioCaptureService.EXTRA_SESSION, editor)) }
        catch (_: Exception) { transportActive = false; fail("Open Odicto and allow microphone access, or use the keyboard mic.") }
    }
    fun lock() { if (mutable.value.active) update { it.copy(locked = true) } }
    fun mode(context: Context, mode: String) {
        if (mutable.value.busy || mode !in listOf("raw", "ai", "live")) return
        VoicePreferences.update(context) { it.copy(mode = mode) }; update { it.copy(mode = mode) }
        if (mutable.value.active) context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_MODE).putExtra("mode", mode))
    }
    fun finish(context: Context) {
        if (!mutable.value.active) return
        update { it.copy(phase = "processing", locked = false, level = 0f, message = "Finishing…") }
        DictationCoordinator.process(session)
        context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
    }
    fun cancel(context: Context) {
        context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_CANCEL))
        DictationCoordinator.cancel(); mutable.value = VoiceUi(mode = VoicePreferences.state.value.mode)
    }
    fun fail(message: String) { DictationCoordinator.fail(message); update { it.copy(phase = "error", locked = false, level = 0f, message = message) } }
}
