package app.odicto.mobile.storage

import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/** A short, explicit spelling vocabulary sent only with Groq transcription requests. */
object VocabularyHints {
    // A UTF-8 byte bound is conservative for Whisper's byte-level tokenizer, without shipping its tokenizer.
    const val MAX_PROMPT_BYTES = 224

    fun normalize(terms: List<String>): List<String> = terms
        .flatMap { it.split(',', '\uFF0C', '\n', '\r') }
        .map { Normalizer.normalize(it.trim(), Normalizer.Form.NFC) }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

    fun prompt(terms: List<String>): String = normalize(terms).joinToString(", ")

    fun fits(terms: List<String>): Boolean = prompt(terms).toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_BYTES

    fun fromJson(value: Any?): List<String>? {
        if (value !is JSONArray || value.length() > MAX_PROMPT_BYTES) return null
        val terms = (0 until value.length()).map { value.opt(it) as? String ?: return null }
        return normalize(terms).takeIf(::fits)
    }

    fun forStored(value: Any?): List<String> = try {
        if (value is String) fromJson(JSONArray(value)).orEmpty() else emptyList()
    } catch (_: Exception) { emptyList() }
}
