package app.odicto.mobile

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.dictation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceDeliveryTest {
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun startForegroundService(service: Intent) = ComponentName(this, "RecordingFixture")
        override fun startService(service: Intent) = ComponentName(this, "RecordingFixture")
    }
    private var commits = 0
    private val connection = object : BaseInputConnection(android.view.View(context), false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { commits++; return true }
    }
    @Before fun prepare() {
        DictationCoordinator.cancel()
        VoiceSession.update { VoiceUi() }
        VoiceSession.transportActive = false
        DictationCoordinator.registerTarget(object : DictationTarget {
            override val id = DictationTarget.IME
            override val identity: Any get() = connection
            override fun readSelection() = AiSelection(0, 0, "")
            override fun insert(text: String) = connection.commitText(text, 1)
        })
        DictationCoordinator.setImeAttached(true)
        DictationCoordinator.editorReady()
    }
    private fun complete(): String {
        VoiceSession.start(context)
        val id = VoiceSession.operationId
        VoiceSession.finish(context)
        val outcome = DictationCoordinator.deliver(VoiceSession.session, "Completed words", id)
        assertEquals(DeliveryOutcome.ACCEPTED_UNVERIFIED, outcome)
        VoiceSession.completed(id, "Completed words", outcome)
        return id
    }
    @Test fun acceptedButNoEditorChangeIsUnverifiedAndOnlySavedAfterPersistence() {
        val id = complete()
        assertEquals(1, commits)
        assertFalse(VoiceSession.state.value.inserted)
        assertTrue(VoiceSession.state.value.message.startsWith("Insertion unverified"))
        assertFalse(VoiceSession.state.value.message.contains("Saved"))
        assertEquals("Completed words", VoiceSession.state.value.expireResult(id).finalText)
        VoiceSession.historyPersisted(id, true)
        assertTrue(VoiceSession.state.value.message.contains("Saved to history"))
        assertEquals(1, commits)
    }
    @Test fun failedPersistenceKeepsCompletedOutputAndNeverClaimsSaved() {
        val id = complete()
        VoiceSession.historyPersisted(id, false)
        assertFalse(VoiceSession.state.value.message.contains("Saved"))
        assertTrue(VoiceSession.state.value.message.contains("History unavailable"))
        assertEquals("Completed words", VoiceSession.state.value.finalText)
    }
    @Test fun oldPersistenceAndCompletionCannotOverwriteNewRequest() {
        val old = complete()
        VoiceSession.transportActive = false
        VoiceSession.start(context)
        val current = VoiceSession.state.value
        VoiceSession.historyPersisted(old, false)
        VoiceSession.completed(old, "Stale output", DeliveryOutcome.CONFIRMED)
        assertEquals(current, VoiceSession.state.value)
        assertEquals(1, commits)
    }
    @Test fun staleServiceStopCannotCancelNewerRequest() {
        VoiceSession.start(context)
        val current = VoiceSession.state.value
        val controller = org.robolectric.Robolectric.buildService(app.odicto.mobile.recording.AudioCaptureService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(context, service.javaClass)
            .setAction(app.odicto.mobile.recording.AudioCaptureService.ACTION_STOP)
            .putExtra(app.odicto.mobile.recording.AudioCaptureService.EXTRA_OPERATION, "old"), 0, 1)
        controller.destroy()
        assertEquals(current, VoiceSession.state.value)
        assertTrue(VoiceSession.transportActive)
    }
    @After fun cleanup() {
        VoiceSession.cancel(context)
        VoiceSession.transportActive = false
        VoiceSession.update { VoiceUi() }
        DictationCoordinator.cancel()
        DictationCoordinator.unregisterTarget(DictationTarget.IME)
    }
}
