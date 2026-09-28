package app.odicto.mobile.ime

import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import android.widget.LinearLayout
import app.odicto.mobile.R
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows
import java.time.Duration

internal class DelayedEditorReplayFixture(initial: String) : AutoCloseable {
    private val serviceOwner = Robolectric.buildService(OdictoImeService::class.java).create()
    val service = serviceOwner.get()
    val root = LayoutInflater.from(service).inflate(R.layout.ime_keyboard, null, false)
    val keyboard: OdictoKeyboardView = root.findViewById(R.id.ime_keyboard)
    val editor = Editor(View(service), initial)
    var connection: Editor = editor
    val controller = KeyboardController(service, root) { connection }
    private var downTime = 0L
    private var notified = initial.length
    private var nextNotification = 0L

    init { restart() }

    fun restart(type: Int = InputType.TYPE_CLASS_TEXT) {
        notified = connection.cursor
        controller.onStartInput(EditorInfo().apply {
            inputType = type
            initialSelStart = connection.cursor
            initialSelEnd = connection.selectionEnd
        })
        val width = (360 * service.resources.displayMetrics.density).toInt()
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
    }

    fun advance(ms: Long, apply: Boolean = true, read: Boolean = true, notify: Boolean = true) {
        repeat((ms / 10).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (apply) connection.applyDue()
            if (read) connection.publishReads()
            if (notify && connection.cursor != notified && SystemClock.uptimeMillis() >= nextNotification) {
                controller.onSelectionChanged(notified, notified, connection.cursor, connection.cursor, -1, -1)
                notified = connection.cursor
                nextNotification = SystemClock.uptimeMillis() + 120
            }
        }
    }

    fun press(key: String) { downTime = SystemClock.uptimeMillis(); event(MotionEvent.ACTION_DOWN, key) }
    fun release(key: String) = event(MotionEvent.ACTION_UP, key)
    fun tap(key: String) { press(key); release(key) }

    fun twoThumbs(first: String, second: String, primaryFirst: Boolean, remainingHoldMs: Long = 10) {
        downTime = SystemClock.uptimeMillis()
        val a = center(first)
        val b = center(second)
        fun send(action: Int, ids: List<Int>) {
            val properties = ids.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = ids.map { id -> MotionEvent.PointerCoords().apply {
                val point = if (id == 7) a else b
                x = point.first; y = point.second; pressure = 1f; size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, ids.size, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { keyboard.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, listOf(7))
        advance(10, notify = false)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 12))
        advance(10, notify = false)
        send(MotionEvent.ACTION_MOVE, listOf(7, 12))
        send(MotionEvent.ACTION_POINTER_UP or ((if (primaryFirst) 0 else 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 12))
        advance(remainingHoldMs, notify = false)
        send(MotionEvent.ACTION_UP, listOf(if (primaryFirst) 12 else 7))
    }

    private fun event(action: Int, key: String) {
        val (x, y) = center(key)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    fun center(key: String): Pair<Float, Float> {
        for (r in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(r) as LinearLayout
            for (c in 0 until row.childCount) {
                val cap = row.getChildAt(c)
                if (cap.contentDescription == key) return (row.left + cap.left + cap.width / 2f) to (row.top + cap.top + cap.height / 2f)
            }
        }
        error("Missing key $key")
    }

    override fun close() { controller.onFinishInput(); serviceOwner.destroy() }

    class Editor(view: View, initial: String) : BaseInputConnection(view, true) {
        var text = initial
        var cursor = initial.length
        var selectionEnd = initial.length
        var finishCompositionCalls = 0
        var delay = 90L
        var metadata = true
        var readable = true
        var readText = initial
        var readCursor = initial.length
        var readEnd = initial.length
        var batchDepth = 0
        var maxPendingDeletes = 0
        var submittedDeletes = 0
        var readCount = 0
        var metadataReadCount = 0
        var rejectWrites = false
        var wordOverlapped = false
        val submissionTimes = mutableListOf<Long>()
        val appliedDeleteSizes = mutableListOf<Int>()
        private data class Pending(val due: Long, val delete: Boolean, val apply: () -> Unit)
        private val pending = ArrayDeque<Pending>()
        override fun beginBatchEdit(): Boolean { batchDepth++; return true }
        override fun endBatchEdit(): Boolean { batchDepth--; check(batchDepth >= 0); return true }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
            readCount++
            return if (readable) readText.take(minOf(readCursor, readEnd)).takeLast(length) else null
        }
        override fun getSelectedText(flags: Int): CharSequence = readText.substring(minOf(readCursor, readEnd), maxOf(readCursor, readEnd))
        override fun finishComposingText(): Boolean { finishCompositionCalls++; return true }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            readCount++
            metadataReadCount++
            if (!metadata || !readable) return null
            val from = (minOf(readCursor, readEnd) - beforeLength).coerceAtLeast(0)
            val to = (maxOf(readCursor, readEnd) + afterLength).coerceAtMost(readText.length)
            return SurroundingText(readText.substring(from, to), readCursor - from, readEnd - from, from)
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text.toString()
            pending += Pending(SystemClock.uptimeMillis() + delay, false) {
                val start = minOf(cursor, selectionEnd)
                this.text = this.text.replaceRange(start, maxOf(cursor, selectionEnd), value)
                cursor = start + value.length
                selectionEnd = cursor
            }
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 2 && pending.any { it.delete }) wordOverlapped = true
            return enqueueDelete {
            val count = beforeLength.coerceAtMost(cursor)
            text = text.removeRange(cursor - count, cursor)
            cursor -= count
            selectionEnd = cursor
            appliedDeleteSizes += count
            }
        }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = enqueueDelete {
            val from = text.offsetByCodePoints(cursor, -beforeLength.coerceAtMost(text.codePointCount(0, cursor)))
            appliedDeleteSizes += cursor - from
            text = text.removeRange(from, cursor)
            cursor = from
            selectionEnd = cursor
        }
        private fun enqueueDelete(action: () -> Unit): Boolean {
            if (rejectWrites) return false
            submissionTimes += SystemClock.uptimeMillis()
            submittedDeletes++
            pending += Pending(SystemClock.uptimeMillis() + delay, true, action)
            maxPendingDeletes = maxOf(maxPendingDeletes, pending.count { it.delete })
            return true
        }
        fun applyDue() {
            while (pending.firstOrNull()?.due?.let { it <= SystemClock.uptimeMillis() } == true) pending.removeFirst().apply()
        }
        fun publishReads() { if (batchDepth == 0) { readText = text; readCursor = cursor; readEnd = selectionEnd } }
    }
}
