package app.odicto.mobile.network

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** One explicitly committed transcription turn. Partial text never becomes a successful timeout result. */
internal class OpenAiLiveSession(
    private val key: String,
    private val model: String,
    private val operation: String,
    private val editor: Long,
    private val factory: WebSocket.Factory,
    private val event: (JSONObject) -> Unit,
) {
    private val resampler = LivePcmResampler()
    private var socket: WebSocket? = null
    private var ready = false
    private var finishing = false
    private var terminal = false
    private var cancelled = false
    private var audioSamples = 0L
    private var observedItem: String? = null
    private var committedItem: String? = null
    private var interim = ""
    private var pendingFinal: Pair<String, String>? = null

    fun open() {
        synchronized(this) { if (cancelled || terminal) return }
        val request = Request.Builder().url("https://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $key").build()
        val created = factory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failure(
                if (response != null) DirectVoiceTransport.providerError("OpenAI Live", response.code)
                else "Cannot connect to OpenAI Live. Check your internet connection.")
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failure("OpenAI Live disconnected. Words already typed were kept.")
        })
        synchronized(this) {
            socket = created
            if (cancelled || terminal) created.cancel()
        }
    }

    @Synchronized private fun opened(webSocket: WebSocket) {
        if (cancelled || terminal) { webSocket.cancel(); return }
        socket = webSocket
        if (!webSocket.send(setup(model).toString())) failure("OpenAI Live connection closed.")
    }

    @Synchronized fun sendAudio(bytes: ByteArray, count: Int): Boolean {
        if (!ready || finishing || terminal || cancelled) return false
        val active = socket ?: return false
        if (active.queueSize() > 480_000) return false
        if (count !in 0..bytes.size || count % 2 != 0) { failure("Invalid microphone audio."); return false }
        val converted = resampler.append(bytes, count)
        audioSamples += count / 2
        return append(active, converted)
    }

    @Synchronized fun finish() {
        if (finishing || terminal || cancelled) return
        finishing = true
        val active = socket
        if (!ready || active == null) { failure("OpenAI Live was not ready. Try again."); return }
        if (audioSamples < 1600) { failure("Recording was too short. Try speaking a little longer."); return }
        if (!append(active, resampler.finish()) || !active.send("{\"type\":\"input_audio_buffer.commit\"}")) {
            failure("OpenAI Live connection closed. Words already typed were kept.")
        }
    }

    @Synchronized fun timeout() {
        if (finishing) failure("OpenAI Live finalization timed out. Words already typed were kept.")
    }

    @Synchronized fun cancel() {
        if (cancelled) return
        cancelled = true
        clearText()
        socket?.cancel()
    }

    private fun append(active: WebSocket, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            active.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", bytes.toByteString().base64()).toString())
        } finally { bytes.fill(0) }
    }

    @Synchronized private fun receive(text: String) {
        if (cancelled || terminal) return
        try {
            if (text.length > 1_000_000) { failure("OpenAI Live response was too large."); return }
            val message = JSONObject(text)
            when (message.optString("type")) {
                "session.updated" -> if (!ready) {
                    ready = true
                    emit(JSONObject().put("type", "ready").put("operationId", operation).put("editorSession", editor))
                }
                "input_audio_buffer.committed" -> {
                    if (!finishing) { failure("OpenAI Live returned an unexpected audio turn."); return }
                    val item = message.optString("item_id")
                    if (item.isBlank() || committedItem != null && committedItem != item || observedItem != null && observedItem != item) {
                        failure("OpenAI Live returned an unexpected audio turn."); return
                    }
                    committedItem = item
                    completeIfMatched()
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    if (!ready) return
                    if (message.optInt("content_index", 0) != 0) return
                    val item = message.optString("item_id")
                    if (!acceptItem(item)) return
                    interim += message.optString("delta")
                    if (interim.length > 100_000) { failure("Transcript limit reached."); return }
                    if (interim.isNotBlank()) emit(JSONObject().put("type", "interim").put("text", interim))
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    if (!ready) return
                    if (message.optInt("content_index", 0) != 0) return
                    val item = message.optString("item_id")
                    if (!acceptItem(item)) return
                    val final = message.optString("transcript")
                    if (final.length > 100_000) { failure("Transcript limit reached."); return }
                    pendingFinal = item to final
                    completeIfMatched()
                }
                "conversation.item.input_audio_transcription.failed", "error" -> failure("OpenAI Live rejected the request. Check your key, model access, and quota.")
            }
        } catch (_: Exception) { failure("Invalid OpenAI Live response. Try again.") }
    }

    private fun acceptItem(item: String): Boolean {
        if (item.isBlank()) { failure("Invalid OpenAI Live response. Try again."); return false }
        val known = committedItem ?: observedItem
        if (known != null && known != item) return false
        observedItem = item
        return true
    }

    private fun completeIfMatched() {
        val final = pendingFinal ?: return
        if (!finishing || committedItem != final.first) return
        val text = final.second.trim()
        if (text.isBlank()) { failure("No speech heard. Try again."); return }
        terminal = true
        emit(JSONObject().put("type", "final").put("text", text))
        emit(JSONObject().put("type", "result").put("operationId", operation).put("editorSession", editor).put("text", text).put("transcript", text))
        clearText()
        socket?.close(1000, null)
    }

    private fun emit(value: JSONObject) { if (!cancelled) event(value) }

    @Synchronized private fun failure(message: String) {
        if (terminal || cancelled) return
        terminal = true
        clearText()
        emit(JSONObject().put("type", "error").put("message", message))
        socket?.cancel()
    }

    private fun clearText() {
        interim = ""; pendingFinal = null; observedItem = null; committedItem = null
        resampler.clear()
    }

    companion object {
        fun setup(model: String): JSONObject = JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("type", "transcription").put("audio", JSONObject().put("input", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                .put("transcription", JSONObject().put("model", model)).put("turn_detection", JSONObject.NULL))))
    }
}
