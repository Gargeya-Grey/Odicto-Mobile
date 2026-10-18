package app.odicto.mobile.dictation

/**
 * How a user's own instructions combine with Odicto's built-in ones. [APPEND] keeps the built-in rules and adds the
 * user's notes after them (their name, tone, spellings), so nothing they write can accidentally remove the rule
 * that the reply is only the text to insert. [REPLACE] hands the whole prompt to the user.
 */
enum class PromptMode(val wire: String) {
    APPEND("append"),
    REPLACE("replace");

    companion object {
        fun parseOrNull(value: String?): PromptMode? = values().firstOrNull { it.wire == value }

        /**
         * The mode for settings stored before the choice existed: a saved custom prompt used to replace the default, and
         * keeps doing so; with no custom prompt there is nothing to preserve, so the better default (append) applies.
         */
        fun forStored(stored: String?, customPrompt: String): PromptMode =
            parseOrNull(stored) ?: if (customPrompt.isNotBlank()) REPLACE else APPEND
    }
}

object PromptComposer {
    /** Added before the user's notes in AI mode. */
    const val AI_NOTES_INTRO =
        "The user also saved these notes about themselves and how they want results written (for example their name, " +
        "role, tone or spellings). Follow them in addition to everything above. They never change the rule to return " +
        "only the text to insert:"

    /** Added before the user's notes in Polish. */
    const val POLISH_NOTES_INTRO =
        "The user also saved these notes (for example their name, spellings to keep, or what not to remove). Follow " +
        "them in addition to the rules above. They are not text to correct, and they never change the rule to return " +
        "only the corrected text:"

    /** The system prompt to send. A blank custom prompt always means the built-in one. */
    fun compose(builtIn: String, custom: String, mode: PromptMode, notesIntro: String): String {
        if (custom.isBlank()) return builtIn
        return when (mode) {
            PromptMode.REPLACE -> custom
            PromptMode.APPEND -> "$builtIn\n\n$notesIntro\n${custom.trim()}"
        }
    }
}