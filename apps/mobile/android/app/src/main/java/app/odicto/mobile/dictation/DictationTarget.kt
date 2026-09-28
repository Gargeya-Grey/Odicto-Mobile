package app.odicto.mobile.dictation

/** Insertion channel for a dictation session: the Odicto IME, or the accessibility fallback used while another keyboard is active. */
enum class DeliveryOutcome { CONFIRMED, ACCEPTED_UNVERIFIED, REJECTED_STALE }

interface DictationTarget {
    val identity: Any get() = this
    val selectionVersion: Long get() = 0
    fun deliver(text: String): DeliveryOutcome = if (insert(text)) DeliveryOutcome.ACCEPTED_UNVERIFIED else DeliveryOutcome.REJECTED_STALE
    val id: String
    fun readSelection(): AiSelection?
    fun insert(text: String): Boolean
    fun selectAll(): Boolean = false
    fun refresh() = Unit
    fun beginLive(): Boolean = false
    fun writeLive(text: String, finish: Boolean): Boolean = false
    fun endLive() {}
    companion object {
        const val IME = "ime"
        const val ACCESSIBILITY = "accessibility"
    }
}
