package app.odicto.mobile.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeminiCatalogModel(val id: String, val name: String)

class GeminiCatalogException(message: String) : Exception(message)

/** An explicitly requested catalog; listing models does not establish quota, cost or inference access. */
class GeminiModelCatalog(client: OkHttpClient? = null) {
    private val client by lazy {
        (client ?: HttpClients.shared).newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun fetch(apiKey: String): List<GeminiCatalogModel> {
        if (apiKey.isBlank()) throw GeminiCatalogException("Save a Gemini key first")
        val models = linkedMapOf<String, GeminiCatalogModel>()
        val tokens = mutableSetOf<String>()
        var token: String? = null
        try {
            repeat(MAX_PAGES) {
                val url = ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("pageSize", "1000")
                    .apply { token?.let { addQueryParameter("pageToken", it) } }
                    .build()
                val request = Request.Builder().url(url)
                    .header("x-goog-api-key", apiKey)
                    .header("Accept", "application/json")
                    .get().build()
                val response = client.newCall(request).execute().use { result ->
                    if (!result.isSuccessful) throw GeminiCatalogException(when (result.code) {
                        401 -> "Gemini rejected the API key. Check your key."
                        402 -> "Gemini requires payment for this request. Check your billing settings."
                        403 -> "Gemini denied access. Check your project, billing, and model availability."
                        429 -> "Gemini rate limit or quota reached. Try again later."
                        else -> "Could not load Gemini models. Try again."
                    })
                    val input = result.body?.byteStream() ?: throw GeminiCatalogException("Could not read Gemini models. Try again.")
                    val bytes = ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (bytes.size() <= MAX_PAGE_BYTES) {
                        val count = input.read(chunk, 0, minOf(chunk.size, MAX_PAGE_BYTES + 1 - bytes.size()))
                        if (count == -1) break
                        bytes.write(chunk, 0, count)
                    }
                    if (bytes.size() > MAX_PAGE_BYTES) throw GeminiCatalogException("Gemini returned an oversized model list. Try again.")
                    JSONObject(bytes.toString(Charsets.UTF_8.name()))
                }
                val page = response.optJSONArray("models") ?: throw GeminiCatalogException("Could not read Gemini models. Try again.")
                for (i in 0 until page.length()) {
                    val item = page.optJSONObject(i) ?: continue
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                    if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
                    val resource = item.opt("name") as? String ?: continue
                    if (!resource.startsWith("models/")) continue
                    val id = resource.removePrefix("models/")
                    if (!id.startsWith("gemini-") || !MODEL_ID.matches(id) || SPECIALIZED_MODEL.containsMatchIn(id)) continue
                    val rawName = (item.opt("displayName") as? String).orEmpty()
                    val name = rawName.filterNot { it.isISOControl() }.trim().take(128).ifBlank { id }
                    models.putIfAbsent(id, GeminiCatalogModel(id, name))
                }
                val next = if (response.has("nextPageToken")) response.opt("nextPageToken") as? String
                    ?: throw GeminiCatalogException("Could not read Gemini models. Try again.") else null
                if (next.isNullOrEmpty()) return models.values.toList()
                if (next.length > MAX_TOKEN_LENGTH || next.contains(apiKey) || !tokens.add(next))
                    throw GeminiCatalogException("Could not read Gemini models. Try again.")
                token = next
            }
            throw GeminiCatalogException("Gemini returned too many model pages. Try again.")
        } catch (failure: GeminiCatalogException) {
            throw failure
        } catch (_: IOException) {
            throw GeminiCatalogException("Cannot reach Gemini. Check your connection and try again.")
        } catch (_: Exception) {
            throw GeminiCatalogException("Could not read Gemini models. Try again.")
        }
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_PAGES = 5
        private const val MAX_PAGE_BYTES = 1024 * 1024
        private const val MAX_TOKEN_LENGTH = 2048
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        private val SPECIALIZED_MODEL = Regex("(?:^|[-_.])(?:image|imagen|audio|tts|live|embedding|embed|veo|video|music|transcribe|computer|robotics)(?:[-_.]|$)", RegexOption.IGNORE_CASE)
    }
}
