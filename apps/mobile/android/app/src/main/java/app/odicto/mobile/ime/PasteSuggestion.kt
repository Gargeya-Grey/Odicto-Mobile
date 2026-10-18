package app.odicto.mobile.ime

/**
 * The one just-copied text the header offers to paste.
 *
 * Presentation rules only; capture eligibility, the disable switch and sensitive-clip skipping belong
 * to [ClipboardCaptureGate]. The offer is held in memory for [WINDOW_MS] after the copy itself (the
 * clip's own timestamp, so a copy made in an app with no text field still counts) and is spent by the
 * first paste or dismissal. A later copy has a new token and is offered again. Switching apps or
 * fields never spends it.
 */
internal class PasteSuggestion(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = WINDOW_MS,
) {
    data class Offer(val text: String, val token: String, val copiedAt: Long)

    private var offer: Offer? = null
    private var spentToken: String? = null

    /** True when this changed what is offered. */
    fun offer(text: String, token: String, copiedAt: Long): Boolean {
        if (text.isBlank() || copiedAt <= 0L || token == spentToken) return false
        if (clock() - copiedAt >= windowMs || copiedAt > clock() + CLOCK_SKEW_MS) return false
        val current = offer
        if (current != null && (current.token == token || current.copiedAt > copiedAt)) return false
        offer = Offer(text, token, copiedAt)
        return true
    }

    /** The offer, or null once it is spent or older than the window. */
    fun current(): Offer? {
        val held = offer ?: return null
        if (clock() - held.copiedAt >= windowMs) {
            offer = null
            return null
        }
        return held
    }

    /** Wall-clock time the held offer runs out, for scheduling a refresh. */
    fun expiresAt(): Long? = current()?.let { it.copiedAt + windowMs }

    /** Pasted, dismissed, or overtaken by another action: this copy is not offered again. */
    fun spend() {
        offer?.let { spentToken = it.token }
        offer = null
    }

    companion object {
        const val WINDOW_MS = 30_000L
        private const val CLOCK_SKEW_MS = 5_000L
        private const val PREVIEW_CHARS = 28

        /**
         * A short single-line preview, or null when the text looks like a password or secret: the bar
         * is visible to anyone nearby, and an unflagged secret cannot be told apart reliably, so a
         * single dense mixed-class token is never printed. Plain codes of digits still show.
         */
        fun preview(text: String): String? {
            val flat = text.trim().replace(Regex("\\s+"), " ")
            if (flat.isEmpty() || looksSecret(flat)) return null
            return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS).trimEnd() + "…"
        }

        private fun looksSecret(flat: String): Boolean {
            if (flat.contains(' ') || flat.length < 8) return false
            if (flat.all { it.isDigit() }) return false
            if (flat.startsWith("http://") || flat.startsWith("https://")) return false
            val classes = listOf(
                flat.any { it.isLowerCase() },
                flat.any { it.isUpperCase() },
                flat.any { it.isDigit() },
                flat.any { !it.isLetterOrDigit() },
            ).count { it }
            return classes >= 3
        }
    }
}
