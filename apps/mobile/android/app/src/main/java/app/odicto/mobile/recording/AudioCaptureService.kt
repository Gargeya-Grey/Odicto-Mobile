package app.odicto.mobile.recording

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.*
import android.os.IBinder
import android.os.SystemClock
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
    @Volatile private var transportReady = false
    private var started = false
    private var timeout: Job? = null
    private var audioBytes = 0L
    private var startedAt = 0L
    private val backlog = AudioBacklog(MAX_BACKLOG_FRAMES)
    private val verbose by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started && intent?.getStringExtra(EXTRA_OPERATION) != operation) return START_NOT_STICKY
        val requested = intent?.getStringExtra(EXTRA_OPERATION).orEmpty()
        if (!started && intent?.action != ACTION_START && VoiceSession.owns(requested)) {
            operation = requested; editor = VoiceSession.session; started = true
        }
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_CANCEL -> { ended = true; if (VoiceSession.owns(operation)) { DictationCoordinator.cancel(); updateVoice { VoiceUi(mode = VoicePreferences.state.value.mode) } }; shutdown() }
            ACTION_START -> if (!started) { started = true; startedAt = SystemClock.uptimeMillis(); editor = intent.getLongExtra(EXTRA_SESSION, -1); operation = intent.getStringExtra(EXTRA_OPERATION).orEmpty(); connect() }
        }
        return START_NOT_STICKY
    }
    private fun connect() {
        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW)) }
        val cancel = PendingIntent.getService(this, 1, Intent(this, AudioCaptureService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_OPERATION, operation), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Promote before any validation: a startForegroundService() launch that never reaches
        // startForeground kills the process with ForegroundServiceDidNotStartInTimeException.
        try { startForeground(17, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Odicto voice session").setContentText("Microphone active only during recording").setOngoing(true).addAction(0, "Cancel", cancel).build()) }
        catch (_: Exception) { error("Android blocked recording. Use the keyboard mic."); return }
        if (!VoiceSession.owns(operation)) { ended = true; shutdown(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { error("Allow microphone access in Odicto."); return }
        // Capture before the provider handshake: the microphone goes live with the request so the first
        // words are buffered rather than lost, and the control stops showing "Connecting…".
        if (!startCapture()) return
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
                if (ended || (operation.isNotEmpty() && !VoiceSession.owns(operation))) return@launch
                val created = DirectVoiceTransport(config, operation, editor, { event -> scope.launch { receive(event.toString()) } }, log = { if (verbose) android.util.Log.i(TAG, "transport: $it") })
                transport = created
                created.open()
            } catch (e: VoiceConfigurationException) { error(e.message ?: "Check your voice settings.") }
            catch (_: Exception) { error("Cannot connect. Check your connection and voice settings.") }
        }
    }
    private fun receive(text: String) {
        if (ended || (operation.isNotEmpty() && !VoiceSession.owns(operation))) return
        try {
            val event = JSONObject(text)
            when (event.getString("type")) {
                "ready" -> {
                    if (event.getString("operationId") != operation || event.getLong("editorSession") != editor) return
                    timeout?.cancel()
                    if (DictationCoordinator.activeEditorSession != editor) { error("Editor changed. Start again in the intended field."); return }
                    transportReady = true
                    if (verbose) android.util.Log.i(TAG, "transport ready after ${SystemClock.uptimeMillis() - startedAt}ms, buffered=${backlog.size} frames")
                }
                "interim" -> { updateVoice { it.copy(interim = event.getString("text")) }; streamLive() }
                "final" -> { updateVoice { it.copy(finalText = event.getString("text"), interim = "") }; streamLive() }
                "processing" -> updateVoice { it.copy(message = if (it.mode == "ai") "Writing your answer…" else "Finishing…") }
                "result" -> {
                    if (event.getString("operationId") != operation || event.getLong("editorSession") != editor) return
                    ended = true; timeout?.cancel()
                    val output = event.getString("text"); val transcript = event.getString("transcript")
                    val outcome = DictationCoordinator.deliver(editor, output, operation)
                    if (verbose) android.util.Log.i(TAG, "result after ${SystemClock.uptimeMillis() - startedAt}ms, recorded=${audioBytes / 32}ms, outcome=$outcome")
                    VoiceSession.completed(operation, output, outcome)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { HistoryDatabase.open(this@AudioCaptureService).history().save(HistoryEntry(operation, output, transcript, outcome.name.lowercase(), System.currentTimeMillis())) }
                            VoiceSession.historyPersisted(operation, true)
                        }
                        catch (_: Exception) { VoiceSession.historyPersisted(operation, false) }
                        finally { shutdown() }
                    }
                }
                "error" -> error(event.optString("message", "Could not finish. Try again."))
            }
        } catch (_: Exception) { error("Invalid voice response. Try again.") }
    }
    private fun updateVoice(transform: (VoiceUi) -> VoiceUi) {
        if (VoiceSession.owns(operation)) VoiceSession.update(transform)
    }
    private fun startCapture(): Boolean {
        return try {
            val size = maxOf(3200, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microphone unavailable"); return false }
            recorder = audio; audio.startRecording()
            updateVoice { it.copy(phase = "recording", message = "Listening") }
            if (verbose) android.util.Log.i(TAG, "capture started after ${SystemClock.uptimeMillis() - startedAt}ms, mode=${VoiceSession.state.value.mode}")
            capture = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(3200)
                var dropping = false
                try {
                    while (isActive && !ended) {
                        val count = audio.read(buffer, 0, buffer.size)
                        if (count <= 0) {
                            if (!finishing && !ended) withContext(Dispatchers.Main) { error("Microphone interrupted. Try again.") }
                            break
                        }
                        audioBytes += count
                        // Frames queue in memory until the provider handshake finishes, then replay in order.
                        val sent = if (transportReady) flushBacklog() && sendFrame(buffer, count) else backlog.add(buffer.copyOf(count))
                        // A transient stall must not kill a long dictation: skip the frame and keep the
                        // session alive. A transport that is truly gone reports its own failure event.
                        if (!sent && transportReady && !dropping) { dropping = true; withContext(Dispatchers.Main) { updateVoice { it.copy(message = "Connection is slow. Some audio may be missing.") } } }
                        if (sent) dropping = false
                        // Nothing is buffering into a provider that never connected; the timeout is the bound.
                        if (!sent && !transportReady && backlog.isFull) { withContext(Dispatchers.Main) { error("Connection timed out. Try again.") }; break }
                        var peak = 0
                        for (i in 0 until count - 1 step 2) peak = maxOf(peak, abs(((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 255)).toShort().toInt()))
                        withContext(Dispatchers.Main) { updateVoice { it.copy(level = (peak / 10000f).coerceIn(0f, 1f)) } }
                        if (finishing) break
                        if (audioBytes >= 295L * 32000) { withContext(Dispatchers.Main) { if (VoiceSession.owns(operation)) VoiceSession.finish(this@AudioCaptureService) }; break }
                    }
                    // A release can land before the handshake finished: hold on briefly so the words that
                    // were already captured still reach the provider instead of being discarded.
                    if (!ended && backlog.isNotEmpty) flushWhenReady()
                } catch (_: Exception) { if (!finishing && !ended) withContext(Dispatchers.Main) { error("Microphone interrupted. Try again.") } }
            }
            true
        } catch (_: Exception) { error("Microphone unavailable. Check permissions."); false }
    }

    private fun sendFrame(bytes: ByteArray, count: Int): Boolean = transport?.sendAudio(bytes, count) == true

    /** Replays frames captured before the transport connected. False means the transport dropped them. */
    private fun flushBacklog(): Boolean = backlog.flush { frame -> sendFrame(frame, frame.size) }

    private suspend fun flushWhenReady() {
        var waited = 0L
        while (!ended && !transportReady && waited < FINISH_FLUSH_MS) { delay(50); waited += 50 }
        if (transportReady && !ended) flushBacklog()
    }
    private fun finish() {
        if (finishing || ended) return
        finishing = true
        val audio = recorder
        // A release before the microphone opened has nothing to transcribe. Ending the session here keeps
        // the service from staying started with no transport left to shut it down.
        if (audio == null) { stopBeforeCapture(); return }
        try { audio.stop() } catch (_: Exception) {}
        scope.launch { capture?.join(); audio.release(); if (recorder === audio) recorder = null; sendFinish() }
    }
    private fun stopBeforeCapture() {
        ended = true; timeout?.cancel(); transport?.cancel(); transport = null
        if (VoiceSession.owns(operation)) DictationCoordinator.cancel()
        updateVoice { VoiceUi(mode = VoicePreferences.state.value.mode) }
        shutdown()
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
        if (text.isNotEmpty() && !DictationCoordinator.stream(editor, text, operation)) error("Live typing stopped: editor or cursor changed. Copy available text.")
    }
    private fun error(message: String) {
        if (ended || (operation.isNotEmpty() && !VoiceSession.owns(operation))) return
        if (verbose) android.util.Log.i(TAG, "session failed after ${SystemClock.uptimeMillis() - startedAt}ms: $message")
        ended = true; VoiceSession.fail(message)
        val text = VoiceSession.state.value.let { listOf(it.finalText, it.interim).filter { part -> part.isNotBlank() }.joinToString(" ") }
        scope.launch {
            try { if (text.isNotBlank()) withContext(Dispatchers.IO) { HistoryDatabase.open(this@AudioCaptureService).history().save(HistoryEntry(operation.ifBlank { UUID.randomUUID().toString() }, text, text, "incomplete", System.currentTimeMillis())) } }
            catch (_: Exception) { if (VoiceSession.owns(operation)) updateVoice { it.copy(message = "$message Copy available text before retrying.") } }
            finally { shutdown() }
        }
    }
    private fun shutdown() {
        timeout?.cancel(); try { recorder?.stop() } catch (_: Exception) {}
        capture?.cancel(); backlog.clear(); transport?.cancel(); transport = null
        if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }
    override fun onDestroy() {
        if (VoiceSession.owns(operation) || VoiceSession.operationId.isEmpty()) VoiceSession.transportActive = false
        if (!ended && VoiceSession.owns(operation)) { ended = true; VoiceSession.fail("Recording interrupted. Try again.") }
        try { recorder?.stop() } catch (_: Exception) {}; recorder?.release(); recorder = null
        transport?.cancel(); transport = null; scope.cancel(); super.onDestroy()
    }
    companion object {
        const val ACTION_START = "app.odicto.START_RECORDING"; const val ACTION_STOP = "app.odicto.STOP_RECORDING"
        const val ACTION_CANCEL = "app.odicto.CANCEL_RECORDING"
        const val EXTRA_OPERATION = "operationId"; const val EXTRA_SESSION = "editorSession"; private const val CHANNEL = "odicto-recording"; private const val TAG = "OdictoCapture"
        /** 160 frames is about sixteen seconds of audio, just past the connect timeout. */
        private const val MAX_BACKLOG_FRAMES = 160
        private const val FINISH_FLUSH_MS = 10_000L
    }
}
