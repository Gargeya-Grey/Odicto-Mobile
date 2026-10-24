package app.odicto.mobile.ime

import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class UndoEditor(val epoch: Long, val connection: InputConnection, val info: EditorInfo?)

/** The host owns edit history. One explicit tap sends one shortcut, never a second undo protocol. */
internal class EditorUndoCoordinator(
    private val scope: CoroutineScope,
    private val editor: () -> UndoEditor?,
    private val allowed: () -> Boolean,
    private val state: (String, Boolean) -> Unit,
    private val dispatched: () -> Unit,
    private val applied: (Int, Int) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()

    fun cancel() {
        if (!busyState.value) return
        generation++
        job?.cancel()
        job = null
        busyState.value = false
        state("", false)
    }

    fun undo() {
        if (busyState.value) return
        val target = editor() ?: return state("Focus a text field to undo.", false)
        if (!permits(target.info)) return state("Undo is unavailable in this field.", false)
        if (!allowed()) return state("Finish voice typing before undoing.", false)
        val id = ++generation
        busyState.value = true
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!current(id, target)) return@launch
                val readable = EditorPolicy.permits(target.info) && !EditorPolicy.ownEditor(target.info)
                val before = if (readable) FieldPolishSnapshotReader.capture(target.connection) else null
                if (!current(id, target)) return@launch
                val time = SystemClock.uptimeMillis()
                val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                var down = false
                var up = false
                try {
                    down = target.connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
                } finally {
                    // The modifier is carried on Z itself. Never send a modifier release into a new field.
                    if (current(id, target)) up = runCatching {
                        target.connection.sendKeyEvent(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
                    }.getOrDefault(false)
                }
                if (down) dispatched()
                if (!current(id, target)) return@launch
                if (!down || !up) {
                    state("Undo delivery could not be confirmed. Check the field.", false)
                    return@launch
                }
                if (before == null) {
                    applied(-1, -1)
                    state("Undo requested. Check the field.", false)
                    return@launch
                }
                var after = FieldPolishSnapshotReader.capture(target.connection)
                var reads = 0
                while ((after == null || after.text == before.text) && current(id, target) && reads++ < READBACK_WAITS) {
                    delay(READBACK_WAIT_MS)
                    if (!current(id, target)) return@launch
                    after = FieldPolishSnapshotReader.capture(target.connection)
                }
                if (!current(id, target)) return@launch
                if (after != null && after.text != before.text) {
                    applied(after.selectionStart, after.selectionEnd)
                    state("Text undone", false)
                } else {
                    applied(-1, -1)
                    state("No text change confirmed. This app may have nothing to undo or may not support Undo.", false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == id) state("Undo delivery could not be confirmed. Check the field.", false)
            } finally {
                if (generation == id) {
                    job = null
                    busyState.value = false
                }
            }
        }
        state("Undoing…", true)
        job?.start()
    }

    private fun current(id: Long, target: UndoEditor): Boolean {
        if (generation != id || !allowed()) return false
        val active = editor() ?: return false
        return active.epoch == target.epoch && active.connection === target.connection && permits(active.info)
    }

    companion object {
        private const val READBACK_WAITS = 4
        private const val READBACK_WAIT_MS = 40L

        fun permits(info: EditorInfo?): Boolean {
            if (info == null || !EditorPolicy.manualPermits(info)) return false
            val kind = info.inputType and InputType.TYPE_MASK_CLASS
            if (kind == InputType.TYPE_CLASS_NUMBER &&
                (info.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return false
            return kind in setOf(InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
        }
    }
}
