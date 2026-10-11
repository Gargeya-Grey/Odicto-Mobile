package app.odicto.mobile.ime

/** Common conversation emoji, grouped for browsing without a network or a picker dependency. */
internal object EmojiCatalog {
    data class Category(val label: String, val emoji: List<String>)

    /** Four rows of eight in Recent; larger categories scroll vertically. */
    const val PAGE_SIZE = 32

    val categories: List<Category> = listOf(
        Category("Smileys", listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
            "😊", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘",
            "😗", "😙", "😚", "😋", "😛", "😜", "🤪", "😝",
            "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐",
            "😑", "😶", "😏", "😒", "🙄", "😬", "🤥", "😴",
            "😪", "🤤", "😷", "🤒", "🤕", "🤢", "🤮", "🤧",
            "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "😎",
            "🤓", "🧐", "😕", "😟", "🙁", "☹️", "😮", "😯",
            "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥",
            "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩",
            "😫", "🥱", "😤", "😠", "😡", "🤬", "😈", "👿",
            "💀", "☠️", "💩", "🤡", "👻", "👽", "👾", "🤖",
        )),
        Category("Gestures", listOf(
            "👍", "👎", "👏", "🙏", "💪", "👋", "🤞", "✌️",
            "👌", "🤝", "🙌", "👀", "🤘", "🤙", "🫡", "👊",
            "✊", "🤛", "🤜", "🖐️", "✋", "🖖", "🤟", "🤲",
            "👐", "🤌", "🤏", "☝️", "👆", "👇", "👈", "👉",
            "✍️", "💅", "🤳", "👂", "👃", "👄", "👅", "🦶",
            "🦵", "🧠", "👶", "🧒", "👦", "👧", "🧑", "👨",
            "👩", "🧔", "👵", "👴", "🧓", "🙋", "🙆", "🙅",
            "🤷", "🤦", "🙇", "💁", "👮", "👷", "👨‍💻", "👩‍💻",
        )),
        Category("Hearts", listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖",
            "💘", "💝", "💟", "♥️", "💌", "💋", "💍", "💐",
            "🌹", "🥀", "🎀", "🧸", "💑", "💏", "💒", "🕊️",
        )),
        Category("Nature", listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈",
            "🙉", "🙊", "🐔", "🐧", "🐦", "🐤", "🦆", "🦉",
            "🦋", "🐝", "🐞", "🐢", "🐍", "🐙", "🦑", "🦀",
            "🐠", "🐟", "🐬", "🐳", "🦈", "🐊", "🐘", "🦒",
            "🦓", "🦏", "🦛", "🦍", "🦌", "🐎", "🐕", "🐈",
            "🌸", "🌺", "🌻", "🌼", "🌷", "🌱", "🌿", "🍀",
            "🌴", "🌵", "🍁", "🍂", "☀️", "🌙", "⭐", "🌈",
            "🌍", "🌎", "🌏", "🌊", "🔥", "✨", "⚡", "❄️",
            "☁️", "⛅", "🌤️", "🌧️", "⛈️", "🌩️", "🌨️", "☔",
        )),
        Category("Food", listOf(
            "🍎", "🍏", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
            "🍓", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝",
            "🍅", "🍆", "🥑", "🥦", "🥕", "🌽", "🥔", "🥒",
            "🍞", "🥐", "🥖", "🧀", "🥚", "🍳", "🥓", "🥞",
            "🍗", "🍖", "🌭", "🍔", "🍟", "🍕", "🥪", "🌮",
            "🌯", "🥙", "🍜", "🍝", "🍛", "🍣", "🍱", "🍤",
            "🍚", "🍙", "🥟", "🍦", "🍧", "🍨", "🍩", "🍪",
            "🎂", "🍰", "🧁", "🍫", "🍿", "🍯", "☕", "🍵",
            "🍼", "🥛", "🧃", "🥤", "🍺", "🍻", "🥂", "🍷",
            "🍸", "🍹", "🍾", "🥃", "🍶", "🥗", "🥣", "🥡",
        )),
        Category("Travel", listOf(
            "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
            "🚒", "🚐", "🚚", "🚛", "🚜", "🛵", "🏍️", "🚲",
            "🚆", "🚇", "🚊", "🚉", "🚄", "🚅", "🚂", "🚋",
            "✈️", "🛫", "🛬", "🚀", "🛸", "🚁", "⛵", "🚤",
            "🛳️", "🚢", "⚓", "⛽", "🚦", "🚥", "🚧", "🛣️",
            "🏠", "🏡", "🏢", "🏥", "🏦", "🏨", "🏫", "🏪",
            "🏖️", "🏝️", "🏔️", "⛰️", "🏕️", "⛺", "🗺️", "🧭",
            "🗼", "🗽", "🏰", "🕌", "🛕", "⛩️", "⛪", "🕋",
        )),
        Category("Activities", listOf(
            "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱",
            "🏓", "🏸", "🏒", "🏑", "🏏", "🥅", "⛳", "🏹",
            "🎣", "🥊", "🥋", "🎿", "🛷", "⛸️", "🛹", "🏄",
            "🏊", "🚴", "🏃", "🧘", "🏋️", "🤸", "🤾", "🤽",
            "🎮", "🕹️", "🎲", "🧩", "♟️", "🎯", "🎳", "🎨",
            "🎭", "🎤", "🎧", "🎵", "🎶", "🎸", "🎹", "🥁",
            "🎉", "🎊", "🎈", "🎆", "🎇", "🎄", "🎃", "🎓",
            "🏆", "🏅", "🥇", "🥈", "🥉", "🎬", "🎟️", "🎫",
        )),
        Category("Objects", listOf(
            "⌚", "📱", "📲", "💻", "⌨️", "🖥️", "🖨️", "🖱️",
            "📷", "📸", "📹", "🎥", "📺", "📻", "☎️", "📞",
            "🔋", "🔌", "💡", "🔦", "🕯️", "🧯", "🛒", "🎁",
            "💰", "💵", "💸", "💳", "🧾", "💎", "⚖️", "🔧",
            "🔨", "🛠️", "⚙️", "🔩", "🔑", "🗝️", "🔒", "🔓",
            "📚", "📖", "📝", "✏️", "🖊️", "🖋️", "📌", "📍",
            "📎", "🖇️", "📏", "📐", "✂️", "📅", "🗓️", "⏰",
            "⌛", "⏳", "📦", "✉️", "📧", "📮", "🗑️", "🧹",
        )),
        Category("Symbols", listOf(
            "💯", "✅", "☑️", "✔️", "❌", "❎", "➕", "➖",
            "✖️", "➗", "❗", "❓", "❕", "❔", "⁉️", "‼️",
            "⚠️", "🚫", "⛔", "🚭", "🔞", "♻️", "🔰", "🔱",
            "💬", "🗨️", "🗯️", "💭", "💤", "🔔", "🔕", "📣",
            "🔁", "🔂", "🔃", "🔄", "➡️", "⬅️", "⬆️", "⬇️",
            "↗️", "↘️", "↙️", "↖️", "↔️", "↕️", "↩️", "↪️",
            "▶️", "⏸️", "⏹️", "⏺️", "⏭️", "⏮️", "⏩", "⏪",
            "♾️", "🆗", "🆕", "🆒", "🆘", "🏁", "🚩", "🏳️‍🌈",
        )),
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
        "smile" to "Smileys", "face" to "Smileys", "happy" to "Smileys", "sad" to "Smileys",
        "hand" to "Gestures", "hands" to "Gestures", "thumb" to "Gestures", "people" to "Gestures",
        "heart" to "Hearts", "love" to "Hearts", "kiss" to "Hearts",
        "animal" to "Nature", "animals" to "Nature", "flower" to "Nature", "weather" to "Nature", "star" to "Nature",
        "drink" to "Food", "coffee" to "Food", "pizza" to "Food", "fruit" to "Food",
        "car" to "Travel", "train" to "Travel", "plane" to "Travel", "places" to "Travel",
        "sport" to "Activities", "sports" to "Activities", "game" to "Activities", "music" to "Activities", "party" to "Activities",
        "phone" to "Objects", "computer" to "Objects", "work" to "Objects", "gift" to "Objects",
        "arrow" to "Symbols", "sign" to "Symbols", "flag" to "Symbols",
    )

    /** Familiar choices fill the default page until the user has their own recent emoji. */
    val recent: List<String> = listOf(
        "😂", "❤️", "👍", "😊", "🔥", "🙏", "😅", "🎉",
        "😍", "👏", "😭", "💯", "😉", "🙂", "✨", "😎",
        "🥰", "🤣", "😘", "🤔", "🙌", "✅", "💕", "🥳",
        "🤗", "💪", "👋", "☕", "🎂", "🌹", "🎁", "🤞",
    )

    init {
        val labels = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        for (category in categories) {
            require(category.label.isNotBlank() && labels.add(category.label)) { "Emoji category needs a unique label" }
            require(category.emoji.isNotEmpty()) { "Emoji category ${category.label} is empty" }
            for (entry in category.emoji) {
                require(isWellFormed(entry)) { "Malformed emoji entry" }
                require(seen.add(entry)) { "Duplicate emoji in ${category.label}" }
            }
        }
        require(recent.toSet().size == recent.size) { "Duplicate default emoji" }
        for (entry in recent) require(entry in seen) { "Default emoji must be in the catalog" }
    }

    /** Reject split surrogate pairs and invalid text without restricting complete emoji sequences. */
    private fun isWellFormed(entry: String): Boolean {
        if (entry.isBlank()) return false
        if (entry.any { Character.isISOControl(it) }) return false
        var index = 0
        while (index < entry.length) {
            val codePoint = entry.codePointAt(index)
            if (codePoint in 0xD800..0xDFFF || codePoint == 0xFFFD) return false
            index += Character.charCount(codePoint)
        }
        return index == entry.length
    }
}
