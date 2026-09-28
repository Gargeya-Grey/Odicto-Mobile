package app.odicto.mobile.ime

internal class DeleteRepeatCoordinator {
    enum class Phase { Idle, Running, AwaitingEvidence, AtBoundary }
    var phase = Phase.Idle
        private set
    var epoch = -1L
        private set
    var nextOrdinal = 1
        private set
    private var nextReadAt = 0L
    var preparedCount = 0
    fun start(epoch: Long) {
        this.epoch = epoch
        phase = Phase.Running
        nextOrdinal = 1
        nextReadAt = 0
        preparedCount = 0
    }
    fun stop() { phase = Phase.Idle; preparedCount = 0 }
    fun waitForEvidence() { phase = Phase.AwaitingEvidence; preparedCount = 0 }
    fun boundary() { phase = Phase.AtBoundary; preparedCount = 0 }
    fun ready(count: Int) { phase = Phase.Running; preparedCount = count }
    fun dispatched() { nextOrdinal++; preparedCount = 0 }
    fun freshProgress() { nextReadAt = 0L }
    fun canRead(now: Long): Boolean {
        if (now < nextReadAt) return false
        nextReadAt = now + 80
        return true
    }
    fun hasCredit(state: EditorSessionState, word: Boolean, now: Long): Boolean =
        !state.ambiguous && !state.pendingTyping && !state.wordPending &&
            state.deleteCount < (if (word) 1 else 2) &&
            state.oldestAt?.let { now - it <= 240 } != false
}
