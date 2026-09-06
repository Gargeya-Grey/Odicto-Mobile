package app.odicto.mobile.dictation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object DictationCoordinator {
    private val sequence = AtomicLong()
    private val mutableState = MutableStateFlow<DictationState>(DictationState.Idle)
    val state = mutableState.asStateFlow()
    var activeEditorSession: Long? = null
        private set
    private var inserter: ((Long, String) -> Boolean)? = null
    private var operation: Long? = null
    private var selectionReader: (() -> AiSelection?)? = null
    var aiSelection: AiSelection? = null
        private set
    private var selectionChanged = false
    private var liveBegin: (() -> Boolean)? = null
    private var liveWrite: ((String, Boolean) -> Boolean)? = null
    private var liveEnd: (() -> Unit)? = null
    private var live = false
    fun registerLive(begin: () -> Boolean, write: (String, Boolean) -> Boolean, end: () -> Unit) {
        liveBegin = begin; liveWrite = write; liveEnd = end
    }
    fun beginLive(): Boolean { live = liveBegin?.invoke() == true; return live }
    fun stream(session: Long, text: String): Boolean = live && operation == session && activeEditorSession == session && !protectedField.value && liveWrite?.invoke(text, false) == true
    private fun endLive() { if (live) liveEnd?.invoke(); live = false }
    fun registerSelectionReader(reader: () -> AiSelection?) { selectionReader = reader }
    fun selectionUpdated(start: Int, end: Int) {
        aiSelection?.let { if (minOf(start, end) != minOf(it.start, it.end) || maxOf(start, end) != maxOf(it.start, it.end)) selectionChanged = true }
    }
    val busy get() = operation != null
    val protectedField = MutableStateFlow(false)
    val keyboardVisible = MutableStateFlow(false)

    fun registerInserter(value: (Long, String) -> Boolean) { inserter = value }
    fun unregisterInserter() { endLive(); liveBegin = null; liveWrite = null; liveEnd = null; inserter = null; selectionReader = null; editorClosed() }
    fun deliver(session: Long, text: String): Boolean {
        val target = aiSelection
        val inserted = try {
            canInsert(session) &&
                (target == null || (!selectionChanged && target.matches(selectionReader?.invoke()))) &&
                (if (live) liveWrite?.invoke(text, true) else inserter?.invoke(session, text)) == true
        } catch (_: Exception) { false }
        mutableState.value = if (inserted) DictationState.Completed(session) else DictationState.SavedToHistory("Editor or selection changed")
        endLive(); operation = null; aiSelection = null
        return inserted
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

    fun start(session: Long, selectionAware: Boolean = false): Boolean {
        if (activeEditorSession != session || busy || protectedField.value) return false
        val selection = if (selectionAware) selectionReader?.invoke() else null
        if (selectionAware && selection?.valid != true) return false
        aiSelection = selection; selectionChanged = false
        operation = session
        mutableState.value = DictationState.Recording(session)
        return true
    }

    fun process(session: Long) { if (operation == session) mutableState.value = DictationState.Processing(session) }
    fun canInsert(session: Long) = activeEditorSession == session && operation == session && mutableState.value is DictationState.Processing && !protectedField.value
    fun complete(session: Long) { mutableState.value = if (canInsert(session)) DictationState.Completed(session) else DictationState.SavedToHistory("Editor changed") }
    fun fail(reason: String) { endLive(); operation = null; aiSelection = null; mutableState.value = DictationState.Failed(reason) }
    fun cancel() { endLive(); operation = null; aiSelection = null; mutableState.value = activeEditorSession?.let { DictationState.Ready(it) } ?: DictationState.Idle }
}
