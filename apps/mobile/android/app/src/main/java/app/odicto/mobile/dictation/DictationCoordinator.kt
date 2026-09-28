package app.odicto.mobile.dictation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class DeliveryResult(val editorSession: Long, val operationId: String?, val outcome: DeliveryOutcome) : DictationState

object DictationCoordinator {
    private val sequence = AtomicLong()
    private val mutableState = MutableStateFlow<DictationState>(DictationState.Idle)
    val state = mutableState.asStateFlow()
    var activeEditorSession: Long? = null
        private set
    private val targets = linkedMapOf<String, DictationTarget>()
    private var operation: Long? = null
    var aiSelection: AiSelection? = null
        private set
    private var selectionChanged = false
    private var live = false
    private var liveTarget: DictationTarget? = null

    /** True while the Odicto IME owns the focused editor, so the accessibility fallback stands down. */
    var imeAttached = false
        private set

    val busy get() = operation != null
    val protectedField = MutableStateFlow(false)
    val keyboardVisible = MutableStateFlow(false)

    fun registerTarget(target: DictationTarget) { targets[target.id] = target }

    fun unregisterTarget(id: String) {
        targets.remove(id)?.endLive()
        if (id == DictationTarget.IME) { imeAttached = false; editorClosed() }
    }

    fun setImeAttached(value: Boolean) { imeAttached = value }

    // The IME wins while it owns the editor so selection context and Live composing stay native.
    // The published store build never registers an accessibility target, so this resolves to the IME
    // in every case; only the legacy build has a second channel while another keyboard is active.
    private fun target(): DictationTarget? =
        if (imeAttached) targets[DictationTarget.IME]
        else targets[DictationTarget.ACCESSIBILITY] ?: targets[DictationTarget.IME]

    /** Re-resolve the focused field. Events are the normal trigger, but a restarted process can miss them. */
    fun refreshTarget() { target()?.refresh() }

    /** AI mode defaults to the whole field, but a range the user already picked is left alone. */
    fun selectAllIfNothingSelected(): Boolean {
        val selected = target() ?: return false
        if (selected.readSelection()?.text?.isNotEmpty() == true) return false
        return selected.selectAll()
    }

    fun selectionUpdated(start: Int, end: Int) {
        aiSelection?.let { if (minOf(start, end) != minOf(it.start, it.end) || maxOf(start, end) != maxOf(it.start, it.end)) selectionChanged = true }
    }

    private var requestId: String? = null
    private var requestTarget: DictationTarget? = null
    private var requestIdentity: Any? = null
    private var requestSelectionVersion = 0L

    fun deliver(session: Long, text: String, operationId: String? = requestId): DeliveryOutcome {
        if (operation != session || operationId != requestId) return DeliveryOutcome.REJECTED_STALE
        val target = target()
        val selection = aiSelection
        val outcome = try {
            if (!canInsert(session) || target !== requestTarget || target?.identity != requestIdentity || (!live && target?.selectionVersion != requestSelectionVersion) ||
                (selection != null && (selectionChanged || !selection.matches(target?.readSelection())))) DeliveryOutcome.REJECTED_STALE
            else if (live) {
                if (liveTarget?.writeLive(text, true) == true) DeliveryOutcome.ACCEPTED_UNVERIFIED else DeliveryOutcome.REJECTED_STALE
            } else target?.deliver(text) ?: DeliveryOutcome.REJECTED_STALE
        } catch (_: Exception) { DeliveryOutcome.REJECTED_STALE }
        mutableState.value = DeliveryResult(session, operationId, outcome)
        endLive(); operation = null; aiSelection = null; requestId = null; requestTarget = null; requestIdentity = null
        return outcome
    }

    fun editorReady(): Long = sequence.incrementAndGet().also {
        activeEditorSession = it
        protectedField.value = false
        if (!busy) mutableState.value = DictationState.Ready(it)
    }

    fun editorClosed(protected: Boolean = false) {
        activeEditorSession = null
        protectedField.value = protected
        if (!busy) mutableState.value = DictationState.Idle
    }

    fun start(session: Long, selectionAware: Boolean = false, operationId: String = java.util.UUID.randomUUID().toString()): Boolean {
        if (activeEditorSession != session || busy || protectedField.value) return false
        val selection = if (selectionAware) {
            val selected = target()?.readSelection()
            if (selected?.text.isNullOrEmpty()) target()?.selectAll()
            target()?.readSelection()
        } else null
        if (selectionAware && selection?.valid != true) return false
        aiSelection = selection; selectionChanged = false
        operation = session; requestId = operationId; requestTarget = target(); requestIdentity = requestTarget?.identity; requestSelectionVersion = requestTarget?.selectionVersion ?: 0
        mutableState.value = DictationState.Recording(session)
        return true
    }

    fun process(session: Long, operationId: String? = requestId) { if (operation == session && operationId == requestId) mutableState.value = DictationState.Processing(session) }
    fun canInsert(session: Long) = activeEditorSession == session && operation == session && mutableState.value is DictationState.Processing && !protectedField.value
    fun complete(session: Long) { if (operation == session) mutableState.value = DictationState.Failed("Insertion unverified") }
    fun fail(reason: String) { endLive(); operation = null; aiSelection = null; requestId = null; requestTarget = null; requestIdentity = null; mutableState.value = DictationState.Failed(reason) }
    fun cancel() { endLive(); operation = null; aiSelection = null; requestId = null; requestTarget = null; requestIdentity = null; mutableState.value = activeEditorSession?.let { DictationState.Ready(it) } ?: DictationState.Idle }

    fun beginLive(): Boolean {
        val selected = target() ?: return false
        live = selected.beginLive()
        liveTarget = selected.takeIf { live }
        return live
    }

    fun stream(session: Long, text: String, operationId: String? = requestId): Boolean = operationId == requestId && live && target() === requestTarget && requestTarget?.identity == requestIdentity && operation == session && activeEditorSession == session && !protectedField.value && liveTarget?.writeLive(text, false) == true

    private fun endLive() { if (live) liveTarget?.endLive(); live = false; liveTarget = null }
}
