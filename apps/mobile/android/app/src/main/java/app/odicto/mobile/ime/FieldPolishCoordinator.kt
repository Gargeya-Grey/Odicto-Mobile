package app.odicto.mobile.ime

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PolishEditor(val session: Long, val connection: InputConnection, val info: EditorInfo?)

internal data class CompletedPolishResult(val operationId: Long, val text: String, val expiresAtElapsedRealtime: Long)

internal class FieldPolishCoordinator(
    private val scope: CoroutineScope,
    private val editor: () -> PolishEditor?,
    private val allowed: () -> Boolean,
    private val readKey: () -> String?,
    private val config: () -> Pair<String, String>,
    private val correct: suspend (String, String, String, String) -> FieldPolishResult,
    private val state: (String, Boolean) -> Unit,
    private val applied: (Int, Int) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val recoveryTtlMs: Long = RECOVERY_TTL_MS,
) {
    init { require(recoveryTtlMs in 1..RECOVERY_TTL_MS) }

    private var job: Job? = null
    private var generation = 0L
    private var snapshot: FieldPolishSnapshot? = null
    private var connection: InputConnection? = null
    private var editorSession: Long? = null
    private var capturing = false
    private var expiryJob: Job? = null
    private val completed = MutableStateFlow<CompletedPolishResult?>(null)
    val recovery = completed.asStateFlow()

    val busy: Boolean get() = job?.isActive == true

    fun completedResult(): CompletedPolishResult? {
        val result = completed.value ?: return null
        if (now() >= result.expiresAtElapsedRealtime) {
            dismissCompletedResult(result.operationId)
            return null
        }
        return result
    }

    fun copyCompletedResult(operationId: Long, copy: (String) -> Boolean): Boolean {
        val result = completedResult()?.takeIf { it.operationId == operationId } ?: return false
        if (!runCatching { copy(result.text) }.getOrDefault(false)) return false
        dismissCompletedResult(operationId)
        return true
    }

    fun dismissCompletedResult(operationId: Long): Boolean {
        if (completed.value?.operationId != operationId) return false
        completed.value = null
        expiryJob?.cancel()
        expiryJob = null
        return true
    }

    private fun retain(id: Long, text: String) {
        expiryJob?.cancel()
        completed.value = CompletedPolishResult(id, text, now() + recoveryTtlMs)
        expiryJob = scope.launch { delay(recoveryTtlMs) }.also { timer ->
            timer.invokeOnCompletion {
                if (completed.value?.operationId == id) completed.value = null
            }
        }
    }

    fun start() {
        if (busy) {
            cancel()
            state("Polish cancelled", false)
            return
        }
        val target = editor() ?: return state("Focus a text field to polish.", false)
        if (!EditorPolicy.permits(target.info)) return state(
            EditorPolicy.voiceRefusal(target.info)?.replace("Voice", "Polish") ?: "Polish is unavailable in this field.", false)
        if (!allowed()) return state("Finish voice typing before polishing text.", false)
        if (runCatching { target.connection.finishComposingText() }.getOrDefault(false) != true)
            return state("This field cannot finish its current edit.", false)
        val source = capture(target.connection)
        if (source == null) {
            val empty = runCatching {
                target.connection.getTextBeforeCursor(1, 0)?.length == 0 &&
                    target.connection.getTextAfterCursor(1, 0)?.length == 0 &&
                    target.connection.getSelectedText(0).isNullOrEmpty()
            }.getOrDefault(false)
            return state(if (empty) "Nothing to polish. Type some text first." else "This field does not expose its entire text safely.", false)
        }
        if (source.payload.isBlank()) return state("Nothing to polish. Select or type some text first.", false)
        val (model, prompt) = config()
        val id = ++generation
        snapshot = source
        connection = target.connection
        editorSession = target.session
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val key = withContext(Dispatchers.IO) { readKey() }.orEmpty()
                if (generation != id) return@launch
                if (key.isBlank()) {
                    state("Add an OpenRouter key in Odicto settings.", false)
                    return@launch
                }
                val result = correct(source.payload, model, prompt, key)
                if (generation != id) return@launch
                when (result) {
                    is FieldPolishResult.Failure -> state(result.message, false)
                    is FieldPolishResult.Success -> finish(id, source, result.text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == id) state("Could not polish text. Try again.", false)
            } finally {
                if (generation == id) {
                    snapshot = null
                    connection = null
                    editorSession = null
                    job = null
                }
            }
        }
        state("Polishing text… Tap again to cancel.", true)
        job?.start()
    }

    fun onSelectionChanged(start: Int, end: Int) {
        if (capturing) return
        val source = snapshot ?: return
        if (start != source.selectionStart || end != source.selectionEnd) cancel()
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        snapshot = null
        connection = null
        editorSession = null
        state("", false)
    }

    private fun capture(current: InputConnection): FieldPolishSnapshot? {
        capturing = true
        return try { FieldPolishSnapshotReader.capture(current) } finally { capturing = false }
    }

    private fun finish(id: Long, source: FieldPolishSnapshot, revised: String) {
        if (generation != id) return
        if (revised.isBlank() || revised.length > FieldPolishClient.MAX_OUTPUT_LENGTH) {
            state("Polish returned an empty or oversized correction. Nothing was replaced.", false)
            return
        }
        val current = editor()
        if (current == null || !allowed() || current.session != editorSession || current.connection !== connection ||
            !EditorPolicy.permits(current.info) ||
            runCatching { current.connection.finishComposingText() }.getOrDefault(false) != true ||
            capture(current.connection) != source || generation != id) {
            if (generation == id) {
                retain(id, revised)
                state("Field changed. ${recoveryHint()}", false)
            }
            return
        }
        if (revised == source.payload) {
            state("Text already looks good.", false)
            return
        }
        val selection = source.replacementSelection(revised)
        val target = current.connection
        capturing = true
        val batched = runCatching { target.beginBatchEdit() }.getOrDefault(false)
        var committed = false
        var restored = false
        try {
            if (target.setSelection(source.rangeStart, source.rangeEnd)) {
                committed = target.commitText(revised, 1)
                if (committed) restored = runCatching { target.setSelection(selection.first, selection.second) }.getOrDefault(false)
            }
        } catch (_: Exception) {
            committed = false
        } finally {
            if (batched) runCatching { target.endBatchEdit() }
            if (!committed) runCatching { target.setSelection(source.selectionStart, source.selectionEnd) }
            capturing = false
        }
        if (generation != id) return
        val expected = source.text.replaceRange(source.rangeStart, source.rangeEnd, revised)
        val active = editor()
        val verified = committed && active != null && active.session == editorSession && active.connection === target &&
            EditorPolicy.permits(active.info) && allowed() && capture(target)?.text == expected
        if (verified) {
            applied(selection.first, selection.second)
            state(if (restored) "Text polished" else "Text polished, but cursor could not be restored.", false)
        } else {
            retain(id, revised)
            val message = if (committed) "Correction delivery not verified. Nothing was retried."
                else "This field rejected the correction. Nothing was retried."
            state("$message ${recoveryHint()}", false)
        }
    }

    private fun recoveryHint() = "Copy completed result within ${(recoveryTtlMs + 999) / 1000} seconds, or dismiss."

    companion object {
        const val RECOVERY_TTL_MS = 120_000L
    }
}
