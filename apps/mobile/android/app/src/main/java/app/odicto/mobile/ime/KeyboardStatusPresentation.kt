package app.odicto.mobile.ime

import app.odicto.mobile.dictation.DeliveryOutcome
import app.odicto.mobile.dictation.VoiceUi

/** IME-only presentation. Never owns result text, delivery, insertion or retention. */
class KeyboardStatusPresentation {
    data class Feedback(val generation: Long, val summary: String, val description: String, val deadline: Long?)
    private var generation = 0L
    private var voiceKey: String? = null
    private var source = ""
    private var feedback: Feedback? = null

    fun voice(value: VoiceUi, operation: String, now: Long, timeout: Long) {
        val key = "$operation:${value.resultId}:${value.phase}"
        if (voiceKey == key) {
            // Persistence may add an important warning, but must not renew the deadline.
            if (source == "voice") feedback = feedback?.copy(description = value.message)
            return
        }
        voiceKey = key
        val visible = value.busy || value.phase in listOf("done", "error", "notice")
        if (!visible) {
            if (source == "voice") clear()
            return
        }
        val summary = if (value.phase == "done") {
            if (value.delivery == DeliveryOutcome.CONFIRMED) "Text inserted" else "Check field before pasting"
        } else value.message
        show("voice", summary, value.message, value.busy, now, timeout)
    }

    /** Every explicit action/Polish transition supersedes the already observed voice feedback. */
    fun notice(message: String, busy: Boolean, now: Long, timeout: Long) {
        show("notice", message, message, busy, now, timeout)
    }

    private fun show(owner: String, summary: String, description: String, busy: Boolean, now: Long, timeout: Long) {
        source = owner
        generation++
        feedback = if (summary.isBlank()) null else Feedback(generation,
            summary.replace(Regex("[\\r\\n]+"), " "), description, if (busy) null else now + timeout)
    }

    fun current(now: Long): Feedback? {
        if (feedback?.deadline?.let { now >= it } == true) clear()
        return feedback
    }

    fun expire(expected: Long, now: Long) {
        if (feedback?.generation == expected) current(now)
    }

    private fun clear() { generation++; feedback = null }
}
