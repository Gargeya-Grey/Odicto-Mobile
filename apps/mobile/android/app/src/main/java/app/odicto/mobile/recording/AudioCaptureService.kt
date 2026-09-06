package app.odicto.mobile.recording

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import app.odicto.mobile.R
import app.odicto.mobile.dictation.*
import app.odicto.mobile.network.DirectVoiceConfig
import app.odicto.mobile.network.DirectVoiceTransport
import app.odicto.mobile.network.VoiceConfigurationException
import app.odicto.mobile.storage.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

class AudioCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: AudioRecord? = null
    private var capture: Job? = null
    private var transport: DirectVoiceTransport? = null
    private var editor = -1L
    private var operation = ""
    @Volatile private var finishing = false
    @Volatile private var ended = false
    private var started = false
    private var timeout: Job? = null
    private var audioBytes = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_CANCEL -> { ended = true; DictationCoordinator.cancel(); VoiceSession.update { VoiceUi(mode = VoicePreferences.state.value.mode) }; shutdown() }
            ACTION_START -> if (!started) { started = true; editor = intent.getLongExtra(EXTRA_SESSION, -1); connect() }
        }
        return START_NOT_STICKY
    }
    private fun connect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { error("Allow microphone access in Odicto."); return }
        val manager = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 1, Intent(this, AudioCaptureService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { startForeground(17, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Odicto voice session").setContentText("Microphone active only during recording").setOngoing(true).addAction(0, "Cancel", cancel).build()) }
        catch (_: Exception) { error("Android blocked recording. Use the keyboard mic."); return }
        timeout = scope.launch { delay(15_000); error("Connection timed out. Try again.") }
        scope.launch {
            try {
                val selectedText = DictationCoordinator.aiSelection?.text.orEmpty()
                val config = withContext(Dispatchers.IO) {
                    val credentials = SecureCredentialStore(this@AudioCaptureService)
                    val settings = VoicePreferences.state.value
                    val mode = VoiceSession.state.value.mode
                    DirectVoiceConfig(mode = mode, provider = settings.provider, model = settings.model, liveModel = settings.liveModel,
                        selectedText = if (mode == "ai") selectedText else "",
                        systemPrompt = if (mode == "ai") settings.systemPrompt else "",
                        groqKey = if (mode != "live") credentials.get("voice-groq").orEmpty() else "",
                        answerKey = if (mode == "ai") credentials.get("voice-${settings.provider}").orEmpty() else "",
                        liveKey = if (mode == "live") credentials.get("voice-gemini").orEmpty() else "")
                }
                if (ended) return@launch
                operation = UUID.randomUUID().toString()
                transport = DirectVoiceTransport(config, operation, editor, { event -> scope.launch { receive(event.toString()) } })
                transport!!.open()
            } catch (e: VoiceConfigurationException) { error(e.message ?: "Check your voice settings.") }
            catch (_: Exception) { error("Cannot connect. Check your connection and voice settings.") }
        }
    }
    private fun receive(text: String) {
        if (ended) return
        try {
            val event = JSONObject(text)
            when (event.getString("type")) {
                "ready" -> {
                    if (event.getString("operationId") != operation || event.getLong("editorSession") != editor) return
                    timeout?.cancel()
                    if (finishing) { sendFinish(); return }
                    if (DictationCoordinator.activeEditorSession != editor) { error("Editor changed. Start again in the intended field."); return }
                    beginCapture()
                }
                "interim" -> { VoiceSession.update { it.copy(interim = event.getString("text")) }; streamLive() }
                "final" -> { VoiceSession.update { it.copy(finalText = event.getString("text"), interim = "") }; streamLive() }
                "processing" -> VoiceSession.update { it.copy(message = if (it.mode == "ai") "Writing your answer…" else "Finishing…") }
                "result" -> {
                    if (event.getString("operationId") != operation || event.getLong("editorSession") != editor) return
                    ended = true; timeout?.cancel()
                    val output = event.getString("text"); val transcript = event.getString("transcript")
                    val inserted = DictationCoordinator.deliver(editor, output)
                    VoiceSession.completed(operation, output, inserted)
                    scope.launch {
                        try { withContext(Dispatchers.IO) { HistoryDatabase.open(this@AudioCaptureService).history().save(HistoryEntry(operation, output, transcript, if (inserted) "inserted" else "focus_lost", System.currentTimeMillis())) } }
                        catch (_: Exception) { VoiceSession.update { it.copy(message = if (inserted) "Inserted · history unavailable" else "History unavailable · copy your result") } }
                        finally { shutdown() }
                    }
                }
                "error" -> error(event.optString("message", "Could not finish. Try again."))
            }
        } catch (_: Exception) { error("Invalid voice response. Try again.") }
    }
    private fun beginCapture() {
        try {
            val size = maxOf(3200, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microphone unavailable"); return }
            recorder = audio; audio.startRecording()
            VoiceSession.update { it.copy(phase = "recording", message = "Listening") }
            capture = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(3200)
                try {
                    while (isActive && !finishing && !ended) {
                        val count = audio.read(buffer, 0, buffer.size)
                        if (count <= 0) { if (!finishing && !ended) withContext(Dispatchers.Main) { error("Microphone interrupted. Try again.") }; break }
                        if (transport?.sendAudio(buffer, count) != true) { withContext(Dispatchers.Main) { error("Connection is too slow. Try again.") }; break }
                        audioBytes += count
                        var peak = 0
                        for (i in 0 until count - 1 step 2) peak = maxOf(peak, abs(((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 255)).toShort().toInt()))
                        withContext(Dispatchers.Main) { VoiceSession.update { it.copy(level = (peak / 10000f).coerceIn(0f, 1f)) } }
                        if (audioBytes >= 295L * 32000) { withContext(Dispatchers.Main) { VoiceSession.finish(this@AudioCaptureService) }; break }
                    }
                } catch (_: Exception) { if (!finishing && !ended) withContext(Dispatchers.Main) { error("Microphone interrupted. Try again.") } }
            }
        } catch (_: Exception) { error("Microphone unavailable. Check permissions.") }
    }
    private fun finish() {
        if (finishing || ended) return
        finishing = true
        if (recorder == null) return
        try { recorder?.stop() } catch (_: Exception) {}
        scope.launch { capture?.join(); recorder?.release(); recorder = null; sendFinish() }
    }
    private fun sendFinish() {
        scope.launch(Dispatchers.IO) { transport?.finish() }
        timeout?.cancel(); timeout = scope.launch {
            if (VoiceSession.state.value.mode == "live") {
                delay(3_000); transport?.finishLiveFallback()
                delay(1_000)
            } else delay(50_000)
            if (!ended) error("Could not finish in time. Try again.")
        }
    }
    private fun streamLive() {
        val state = VoiceSession.state.value
        if (state.mode != "live") return
        val text = listOf(state.finalText, state.interim).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isNotEmpty() && !DictationCoordinator.stream(editor, text)) error("Live typing stopped: editor or cursor changed. Available text saved.")
    }
    private fun error(message: String) {
        if (ended) return
        ended = true; VoiceSession.fail(message)
        val text = VoiceSession.state.value.let { listOf(it.finalText, it.interim).filter { part -> part.isNotBlank() }.joinToString(" ") }
        scope.launch {
            try { if (text.isNotBlank()) withContext(Dispatchers.IO) { HistoryDatabase.open(this@AudioCaptureService).history().save(HistoryEntry(operation.ifBlank { UUID.randomUUID().toString() }, text, text, "incomplete", System.currentTimeMillis())) } }
            catch (_: Exception) { VoiceSession.update { it.copy(message = "$message Copy available text before retrying.") } }
            finally { shutdown() }
        }
    }
    private fun shutdown() {
        timeout?.cancel(); try { recorder?.stop() } catch (_: Exception) {}
        capture?.cancel(); transport?.cancel(); transport = null
        if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }
    override fun onDestroy() {
        VoiceSession.transportActive = false
        if (!ended) { ended = true; VoiceSession.fail("Recording interrupted. Try again.") }
        try { recorder?.stop() } catch (_: Exception) {}; recorder?.release(); recorder = null
        transport?.cancel(); transport = null; scope.cancel(); super.onDestroy()
    }
    companion object {
        const val ACTION_START = "app.odicto.START_RECORDING"; const val ACTION_STOP = "app.odicto.STOP_RECORDING"
        const val ACTION_CANCEL = "app.odicto.CANCEL_RECORDING"; const val ACTION_MODE = "app.odicto.MODE"
        const val EXTRA_SESSION = "editorSession"; private const val CHANNEL = "odicto-recording"
    }
}
