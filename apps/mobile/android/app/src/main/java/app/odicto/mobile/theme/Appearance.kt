package app.odicto.mobile.theme

/** The user's choice. [SYSTEM] follows the phone's day/night setting and is the default. */
enum class Appearance(val wire: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        /** Null for anything that is not one of the three names, so a bad value is refused rather than guessed. */
        fun parseOrNull(value: String?): Appearance? = values().firstOrNull { it.wire == value }

        fun parse(value: String?): Appearance = parseOrNull(value) ?: SYSTEM
    }
}