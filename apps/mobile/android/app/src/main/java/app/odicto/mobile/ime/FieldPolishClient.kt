package app.odicto.mobile.ime

import app.odicto.mobile.storage.DEFAULT_POLISH_MODEL
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed class FieldPolishResult {
    data class Success(val text: String) : FieldPolishResult()
    data class Failure(val reason: Reason, val message: String) : FieldPolishResult()

    enum class Reason { INVALID_INPUT, AUTHENTICATION, MODEL_UNAVAILABLE, RATE_LIMITED, PROVIDER, NETWORK, INVALID_RESPONSE, TRUNCATED }
}

class FieldPolishClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun correct(
        text: String,
        model: String = DEFAULT_MODEL,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        apiKey: String,
    ): FieldPolishResult {
        if (text.isBlank()) return FieldPolishResult.Failure(FieldPolishResult.Reason.INVALID_INPUT, "Enter text to correct.")
        if (text.length > MAX_TEXT_LENGTH) return FieldPolishResult.Failure(FieldPolishResult.Reason.INVALID_INPUT, "Field exceeds 20,000 characters. Shorten it and try again.")
        if (systemPrompt.length > MAX_PROMPT_LENGTH) return FieldPolishResult.Failure(FieldPolishResult.Reason.INVALID_INPUT, "System prompt exceeds 8,000 UTF-16 units.")
        if (model.isBlank()) return FieldPolishResult.Failure(FieldPolishResult.Reason.INVALID_INPUT, "Choose an OpenRouter model.")
        if (apiKey.isBlank()) return FieldPolishResult.Failure(FieldPolishResult.Reason.INVALID_INPUT, "Add an OpenRouter API key.")

        val body = JSONObject().put("model", model).put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }))
                .put(JSONObject().put("role", "user").put("content", text)))
            .put("provider", JSONObject().put("sort", "latency"))
        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        FieldPolishResult.Failure(FieldPolishResult.Reason.NETWORK, "Cannot reach OpenRouter. Check your connection and try again."))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { parseResponse(it) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun parseResponse(response: Response): FieldPolishResult {
        if (!response.isSuccessful) {
            val (reason, message) = when (response.code) {
                401, 403 -> FieldPolishResult.Reason.AUTHENTICATION to "OpenRouter rejected the API key. Check your key."
                404 -> FieldPolishResult.Reason.MODEL_UNAVAILABLE to "OpenRouter model not found. Check the model name or availability."
                429 -> FieldPolishResult.Reason.RATE_LIMITED to "OpenRouter rate limit or quota reached. Try later or check your account."
                else -> FieldPolishResult.Reason.PROVIDER to "OpenRouter request failed (HTTP ${response.code}). Try again."
            }
            return FieldPolishResult.Failure(reason, message)
        }
        return try {
            val stream = response.body?.byteStream() ?: return invalidResponse()
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (bytes.size() <= MAX_RESPONSE_BYTES) {
                val count = stream.read(chunk, 0, minOf(chunk.size, MAX_RESPONSE_BYTES + 1 - bytes.size()))
                if (count == -1) break
                bytes.write(chunk, 0, count)
            }
            if (bytes.size() > MAX_RESPONSE_BYTES) return invalidResponse()
            val choice = JSONObject(bytes.toString(Charsets.UTF_8.name()))
                .optJSONArray("choices")?.optJSONObject(0) ?: return invalidResponse()
            if (choice.optString("finish_reason").equals("length", ignoreCase = true))
                return FieldPolishResult.Failure(FieldPolishResult.Reason.TRUNCATED, "Correction was cut off. Shorten the field and try again; nothing was replaced.")
            if (choice.optString("finish_reason") != "stop") return invalidResponse()
            val content = choice.optJSONObject("message")?.opt("content")
            if (content !is String || content.isBlank() || content.length > MAX_OUTPUT_LENGTH) return invalidResponse()
            FieldPolishResult.Success(content)
        } catch (_: IOException) {
            FieldPolishResult.Failure(FieldPolishResult.Reason.NETWORK, "Cannot read OpenRouter response. Try again.")
        } catch (_: JSONException) {
            invalidResponse()
        }
    }

    private fun invalidResponse() = FieldPolishResult.Failure(
        FieldPolishResult.Reason.INVALID_RESPONSE, "OpenRouter returned an empty, incomplete, or oversized correction. Nothing was replaced.")

    companion object {
        const val DEFAULT_MODEL = DEFAULT_POLISH_MODEL
        const val DEFAULT_SYSTEM_PROMPT = "Correct spelling, grammar, and capitalization only. Keep the original meaning and formatting. Preserve proper-noun spellings exactly as entered. Return only the corrected text."
        const val MAX_TEXT_LENGTH = 20_000
        const val MAX_PROMPT_LENGTH = 8_000
        const val MAX_OUTPUT_LENGTH = 20_000
        private const val MAX_OUTPUT_TOKENS = 8192
        private const val MAX_RESPONSE_BYTES = 128 * 1024
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
