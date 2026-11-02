package app.odicto.mobile.storage

/** Whisper language tokens. Empty means omit the hint and detect from audio. */
object SpokenLanguage {
    const val AUTOMATIC = ""
    val codes: Set<String> = setOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca",
        "nl", "ar", "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms",
        "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la",
        "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn",
        "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
        "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be",
        "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn",
        "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha",
        "ba", "jw", "su", "yue",
    )

    fun isSupported(value: String): Boolean = value == AUTOMATIC || value in codes

    fun forStored(value: Any?): String = (value as? String)?.takeIf(::isSupported) ?: AUTOMATIC
}
