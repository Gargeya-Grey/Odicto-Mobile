package app.odicto.mobile.ime

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyTapDiagnosticsTest {
    @org.junit.Before fun preciseTargets() = kotlinx.coroutines.runBlocking {
        app.odicto.mobile.storage.VoicePreferences.save(androidx.test.core.app.ApplicationProvider.getApplicationContext(), app.odicto.mobile.storage.VoiceSettings(keys = app.odicto.mobile.storage.KeySettings(tapTolerance = 0)))
    }
    @org.junit.After fun restoreTargets() = kotlinx.coroutines.runBlocking {
        app.odicto.mobile.storage.VoicePreferences.save(androidx.test.core.app.ApplicationProvider.getApplicationContext(), app.odicto.mobile.storage.VoiceSettings())
    }

    private fun count(value: KeyLatencyProbe.Outcome) = KeyLatencyProbe.outcomeSnapshot().getValue(value)
    private fun clear() { KeyLatencyProbe.outcomeSnapshot(clear = true) }

    @Test fun acceptedSubmissionIsNotReportedAsVisibleApplication() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            clear()
            f.tap("q")
            assertEquals(1L, count(KeyLatencyProbe.Outcome.CONTACT_RECEIVED))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.KEY_CONTACT))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.CHARACTER_DISPATCH))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_ACCEPTED))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.INTERACTION_INVALIDATED))
            assertEquals("prefix ", f.editor.text)
            f.advance(200, notify = false)
            assertEquals("prefix q", f.editor.text)
        }
    }

    @Test fun forgivingMarginTapReachesTheEditorWithoutChangingVisibleSpacing() {
        kotlinx.coroutines.runBlocking {
            app.odicto.mobile.storage.VoicePreferences.save(androidx.test.core.app.ApplicationProvider.getApplicationContext(), app.odicto.mobile.storage.VoiceSettings())
        }
        DelayedEditorReplayFixture("prefix ").use { f ->
            val row = f.keyboard.getChildAt(1) as ViewGroup
            val keys = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isClickable }
            val left = keys.first { it.contentDescription == "q" }
            val right = keys.first { it.contentDescription == "w" }
            assertTrue(right.left > left.right)
            clear()
            val x = (left.right + right.left) / 2f
            val y = row.y + left.y + left.height / 2f
            touch(f.keyboard, MotionEvent.ACTION_DOWN, x, y)
            touch(f.keyboard, MotionEvent.ACTION_UP, x, y)
            assertEquals(1L, count(KeyLatencyProbe.Outcome.MARGIN_RECOVERED))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_ACCEPTED))
            f.advance(200, notify = false)
            assertEquals("prefix w", f.editor.text)
        }
    }

    @Test fun rejectedCommitDoesNotRetryAndFreshTapCanSucceed() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            clear()
            f.editor.rejectWrites = true
            f.tap("q")
            f.editor.rejectWrites = false
            f.tap("w")
            f.advance(200, notify = false)
            assertEquals(2L, count(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_REJECTED))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_ACCEPTED))
            assertEquals("prefix w", f.editor.text)
        }
    }

    @Test fun interKeyMarginsAndOutsideContactsAreSeparateFromDispatch() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            val row = f.keyboard.getChildAt(1) as ViewGroup
            val keys = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isClickable }
            val q = keys.first { it.contentDescription == "q" }
            val w = keys.first { it.contentDescription == "w" }
            clear()
            touch(f.keyboard, MotionEvent.ACTION_DOWN, (q.right + w.left) / 2f, row.y + q.y + q.height / 2f)
            touch(f.keyboard, MotionEvent.ACTION_UP, (q.right + w.left) / 2f, row.y + q.y + q.height / 2f)
            touch(f.keyboard, MotionEvent.ACTION_DOWN, -1f, 0f)
            touch(f.keyboard, MotionEvent.ACTION_UP, -1f, 0f)
            assertEquals(2L, count(KeyLatencyProbe.Outcome.CONTACT_RECEIVED))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.KEY_MARGIN_MISS))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.STRUCTURAL_MISS))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.INTERACTION_INVALIDATED))
        }
    }

    @Test fun platformCancelCannotPretendAnAlreadyDispatchedLetterWasLost() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            clear()
            f.press("q")
            touch(f.keyboard, MotionEvent.ACTION_CANCEL, 0f, 0f)
            assertEquals(1L, count(KeyLatencyProbe.Outcome.PLATFORM_CANCEL))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_ACCEPTED))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.INTERACTION_INVALIDATED))
            clear()
            f.press("Space")
            touch(f.keyboard, MotionEvent.ACTION_CANCEL, 0f, 0f)
            assertEquals(1L, count(KeyLatencyProbe.Outcome.PLATFORM_CANCEL))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.SPACE_DISPATCH))
        }
    }

    @Test fun deliveredLightAndFirmPressuresHaveIdenticalDispatch() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            val (x, y) = f.center("q")
            clear()
            for (pressure in listOf(0f, 0.05f, 1f)) {
                val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val point = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 1f }
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0, 0, action, 1, arrayOf(property), arrayOf(point), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { f.keyboard.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            assertEquals(3L, count(KeyLatencyProbe.Outcome.COMMIT_ACCEPTED))
            f.advance(200, notify = false)
            assertEquals("prefix qqq", f.editor.text)
        }
    }

    @Test fun missingConnectionAndCommitExceptionAreCountedWithoutRetries() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            clear()
            f.connectionAvailable = false
            f.tap("q")
            assertEquals(1L, count(KeyLatencyProbe.Outcome.NO_CONNECTION))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT))
            f.connectionAvailable = true
            f.editor.throwOnCommit = true
            f.tap("w")
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT))
            assertEquals(1L, count(KeyLatencyProbe.Outcome.COMMIT_EXCEPTION))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.COMMIT_REJECTED))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.COMMIT_ACCEPTED))
            f.advance(200, notify = false)
            assertEquals("prefix ", f.editor.text)
        }
    }

    @Test fun changedConnectionDuringAssistReadRefusesTheStaleSubmission() {
        DelayedEditorReplayFixture("prefix ").use { f ->
            clear()
            val replacement = DelayedEditorReplayFixture.Editor(View(f.service), "replacement")
            f.editor.beforeCursorRead = { f.connection = replacement }
            f.tap("q")
            assertEquals(1L, count(KeyLatencyProbe.Outcome.STALE_SESSION))
            assertEquals(0L, count(KeyLatencyProbe.Outcome.COMMIT_ATTEMPT))
            f.advance(200, notify = false)
            assertEquals("prefix ", f.editor.text)
            assertEquals("replacement", replacement.text)
        }
    }

    @Test fun outcomeSnapshotsContainOnlyFixedCategoriesAndCounts() {
        clear()
        KeyLatencyProbe.outcome(KeyLatencyProbe.Outcome.KEY_CONTACT)
        KeyLatencyProbe.outcome(KeyLatencyProbe.Outcome.KEY_CONTACT)
        val snapshot = KeyLatencyProbe.outcomeSnapshot(clear = true)
        assertEquals(KeyLatencyProbe.Outcome.entries.size, snapshot.size)
        assertEquals("OUTCOMES KEY_CONTACT=2", KeyLatencyProbe.summarizeOutcomes(snapshot))
        assertTrue(KeyLatencyProbe.outcomeSnapshot().values.all { it == 0L })
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
