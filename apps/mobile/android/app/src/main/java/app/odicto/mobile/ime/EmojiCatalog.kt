package app.odicto.mobile.ime

/**
 * A small, curated emoji set rather than the full Unicode table.
 *
 * A full picker is a large, frequently updated data set that a keyboard does not need to justify:
 * these are the emoji people reach for in conversation, grouped so the panel is browsable. The
 * catalogue validates itself at construction, so a malformed or duplicated entry fails loudly in a
 * unit test rather than becoming a blank cell or a broken glyph on the phone.
 */
internal object EmojiCatalog {
    data class Category(val label: String, val emoji: List<String>)

    /** Two rows of eight. Each page is only the emoji people actually reach for. */
    const val PAGE_SIZE = 16

    val categories: List<Category> = listOf(
        Category("Smileys", listOf("😀", "😃", "😄", "😁", "😅", "😂", "🤣", "😊", "😍", "😘", "😉", "😎", "😢", "😭", "😡", "🤔")),
        Category("Gestures", listOf("👍", "👎", "👏", "🙏", "💪", "👋", "🤞", "✌️", "👌", "🤝", "🙌", "👀", "🤘", "🤙", "🫡", "👊")),
        Category("Hearts", listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "💕", "💖", "💗", "💘", "💝", "💞", "💓", "💟")),
        Category("Objects", listOf("🔥", "✨", "⭐", "💯", "✅", "❌", "🎉", "🎁", "☕", "🍕", "🌹", "🌙", "⚡", "💡", "🎵", "🏆")),
        Category("Symbols", listOf("❗", "❓", "➕", "➖", "⚠", "🚫", "♻", "💬", "🔁", "➡", "⬅", "⬆", "⬇", "▶", "⏸", "♾")),
    )

    /** Category name, or a short alias such as "heart". Empty shows every category. */
    fun matching(query: String?): List<Category> {
        val needle = query?.trim()?.lowercase().orEmpty()
        if (needle.isEmpty() || needle == "all") return categories
        val byName = categories.filter { it.label.lowercase().contains(needle) }
        if (byName.isNotEmpty()) return byName
        val alias = ALIASES[needle] ?: return emptyList()
        return categories.filter { it.label == alias }
    }

    private val ALIASES = mapOf(
        "smile" to "Smileys", "face" to "Smileys",
        "hand" to "Gestures", "hands" to "Gestures",
        "heart" to "Hearts", "love" to "Hearts",
        "food" to "Objects", "star" to "Objects",
        "arrow" to "Symbols",
    )

    /** Frequently used first so the default panel is useful without scrolling. */
    val recent: List<String> = listOf("😂", "❤️", "👍", "😊", "🔥", "🙏", "😅", "🎉", "😍", "👏", "😭", "💯", "😉", "🙂", "✨", "😎")

    init {
        val seen = mutableSetOf<String>()
        for (category in categories) {
            require(category.label.isNotBlank()) { "Emoji category needs a label" }
            require(category.emoji.isNotEmpty()) { "Emoji category ${category.label} is empty" }
            for (entry in category.emoji) {
                require(isWellFormed(entry)) { "Malformed emoji entry" }
                require(seen.add(entry)) { "Duplicate emoji in ${category.label}" }
            }
        }
        for (entry in recent) require(isWellFormed(entry)) { "Malformed emoji in recent list" }
    }

    /**
     * An entry is well formed when every code point is a real character. A lone surrogate survives
     * as its own code point, so checking that none of them is a surrogate catches a split emoji.
     */
    private fun isWellFormed(entry: String): Boolean {
        if (entry.isBlank()) return false
        if (entry.any { Character.isISOControl(it) }) return false
        var index = 0
        while (index < entry.length) {
            val codePoint = entry.codePointAt(index)
            if (codePoint in 0xD800..0xDFFF) return false
            index += Character.charCount(codePoint)
        }
        return index == entry.length
    }
}
