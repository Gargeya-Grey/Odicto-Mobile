package app.odicto.mobile.ime

/**
 * Symbols offered by holding comma or period.
 *
 * The comma key already typed a comma on touch-down, so the strip does not offer another comma.
 * The full stop is the first cell and the selection when the strip opens. The strip grows to the
 * right of the key, so a thumb on the left side of the keyboard never has to reach the screen edge.
 */
internal object Punctuation {
    fun options(output: String): List<String>? = when (output) {
        "," -> listOf(".", "!", "?", ":", ";", "'")
        "." -> listOf("!", "?", ",", ":", ";", "'")
        else -> null
    }

    /** The cell selected when the strip first appears. */
    fun homeIndex(output: String): Int = if (options(output) == null) 0 else 0

    /**
     * Left edge of a strip that prefers to start at the key and grow right.
     * It shifts left only when the row would otherwise leave the screen.
     */
    fun stripLeft(anchorLeft: Float, width: Float, viewWidth: Float, margin: Float): Float {
        val maxLeft = (viewWidth - width - margin).coerceAtLeast(margin)
        return anchorLeft.coerceIn(margin, maxLeft)
    }

    fun indexFromLeft(count: Int, left: Float, x: Float, cellPx: Float): Int {
        if (count <= 0 || cellPx <= 0f) return 0
        return ((x - left) / cellPx).toInt().coerceIn(0, count - 1)
    }
}
