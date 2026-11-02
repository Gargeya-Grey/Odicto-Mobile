package app.odicto.mobile.storage

object LiveProvider {
    const val GEMINI = "gemini"
    const val OPENAI = "openai"
    const val DEFAULT_OPENAI_MODEL = "gpt-live-transcribe"
    val openaiModels = setOf(DEFAULT_OPENAI_MODEL, "gpt-realtime-whisper")

    fun forStored(value: String?): String = value?.takeIf { it == GEMINI || it == OPENAI } ?: GEMINI
    fun openaiModelForStored(value: String?): String = value?.takeIf { it in openaiModels } ?: DEFAULT_OPENAI_MODEL
}
