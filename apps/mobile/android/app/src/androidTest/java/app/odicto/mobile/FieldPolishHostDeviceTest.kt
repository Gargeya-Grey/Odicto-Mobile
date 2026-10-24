package app.odicto.mobile

import android.app.Service
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.odicto.mobile.ime.FieldPolishCoordinator
import app.odicto.mobile.ime.FieldPolishResult
import app.odicto.mobile.ime.FieldPolishSnapshotReader
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.PolishEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import app.odicto.mobile.ime.KeyboardExpansion
import app.odicto.mobile.theme.Appearance
import app.odicto.mobile.theme.AppearanceStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in only: the active host must contain exactly the generated fixture before any edit. */
@RunWith(AndroidJUnit4::class)
class FieldPolishHostDeviceTest {
    @Test fun liveProviderPolishesOnlyTheGeneratedFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odictoPolishHost") == "generated-live")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = "Helo, this is a test. Um I really really like this app.\n"
        var service: OdictoImeService? = null
        lateinit var coordinator: FieldPolishCoordinator
        instrumentation.runOnMainSync {
            service = activeService()
            val ime = requireNotNull(service)
            assertTrue(ime.currentInputEditorInfo?.packageName == "com.samsung.android.app.notes")
            assertTrue("Only the generated note may be sent", FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source)
            coordinator = OdictoImeService::class.java.getDeclaredField("polish").apply { isAccessible = true }.get(ime) as FieldPolishCoordinator
            val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
            root.findViewById<View>(R.id.ime_polish).performClick()
        }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var busy = true
        while (busy && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            instrumentation.runOnMainSync { busy = coordinator.busy }
        }
        instrumentation.runOnMainSync {
            val ime = requireNotNull(service)
            assertFalse("Live provider request timed out", busy)
            val status = OdictoImeService::class.java.getDeclaredField("polishStatus").apply { isAccessible = true }.get(ime) as android.widget.TextView
            assertTrue("Live result was not verified: ${status.text}", coordinator.undoAvailable.value && coordinator.completedResult() == null)
            val actual = FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text
            assertTrue("The live correction must replace the generated typo", actual != null && actual != source && actual.startsWith("Hello"))
            instrumentation.sendStatus(0, android.os.Bundle().apply { putBoolean("liveProviderReplacementVerified", true) })
        }
        val file = java.io.File(requireNotNull(service).getExternalFilesDir(null), "polish-live.png")
        java.io.FileOutputStream(file).use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun recoveryCardIsCompactAndActionableInBothThemes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odictoPolishHost") == "generated-recovery")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var service: OdictoImeService? = null
        instrumentation.runOnMainSync { service = activeService() }
        val ime = requireNotNull(service)
        val previousAppearance = AppearanceStore.current
        val polish = OdictoImeService::class.java.getDeclaredField("polish").apply { isAccessible = true }.get(ime) as FieldPolishCoordinator
        val retain = FieldPolishCoordinator::class.java.getDeclaredMethod("retain", Long::class.javaPrimitiveType, String::class.java, String::class.java)
            .apply { isAccessible = true }
        fun root() = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
        val explanation = "The field changed before the correction could be applied. You can copy the result instead."
        try {
            for ((index, appearance) in listOf(Appearance.DARK, Appearance.LIGHT).withIndex()) {
                instrumentation.runOnMainSync {
                    assertTrue(ime.currentInputEditorInfo?.packageName == "com.samsung.android.app.notes")
                    val text = FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text
                    assertTrue("Use only the generated fixture", text == "Helo, this is a test. Um I really really like this app.\n")
                    AppearanceStore.set(ime, appearance)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { retain.invoke(polish, 1000L + index, "Hello, this is a test. I really like this app.", explanation) }
                SystemClock.sleep(200)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val expansion = OdictoImeService::class.java.getDeclaredField("expansion").apply { isAccessible = true }.get(ime) as KeyboardExpansion
                    assertTrue("Recovery must stay compact", root().height - expansion.contentTop() <= (286 * root().resources.displayMetrics.density).toInt())
                    val labels = descendants(root()).filterIsInstance<android.widget.TextView>()
                    assertTrue(labels.any { it.text == explanation && it.isShown })
                    assertFalse(labels.any { Regex("\\d+s").matches(it.text.toString()) })
                }
                val file = java.io.File(ime.getExternalFilesDir(null), "polish-recovery-${appearance.wire}.png")
                java.io.FileOutputStream(file).use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                instrumentation.runOnMainSync {
                    val action = descendants(root()).filterIsInstance<android.widget.Button>().single { it.text == if (index == 0) "Copy" else "Dismiss" }
                    assertTrue(action.isShown && action.isEnabled)
                    action.performClick()
                    assertNull(polish.completedResult())
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                polish.completedResult()?.let { polish.dismissCompletedResult(it.operationId) }
                AppearanceStore.set(ime, previousAppearance)
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun polishesGeneratedTextThroughTheActiveImeConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("odictoPolishHost") == "generated-note")
        val source = "Helo, this is a test. Um I really really like this app.\n"
        val revised = "Hello, this is a test. I really like this app."
        var service: OdictoImeService? = null
        repeat(20) {
            instrumentation.runOnMainSync { service = activeService() }
            if (service == null) SystemClock.sleep(250)
        }
        val ime = requireNotNull(service) { "Select Odicto Debug in the generated test field first." }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val finished = CountDownLatch(1)
        val undone = CountDownLatch(1)
        var status = ""
        var previous: FieldPolishCoordinator? = null
        var injected = false
        val polishField = OdictoImeService::class.java.getDeclaredField("polish").apply { isAccessible = true }
        val sessionField = OdictoImeService::class.java.getDeclaredField("session").apply { isAccessible = true }
        val showStatus = OdictoImeService::class.java.getDeclaredMethod("showPolishStatus", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync {
                assertTrue("Use the disposable Samsung Notes test field only", ime.currentInputEditorInfo?.packageName == "com.samsung.android.app.notes")
                val connection = requireNotNull(ime.currentInputConnection)
                val snapshot = requireNotNull(FieldPolishSnapshotReader.capture(connection)) { "Host does not expose a safe complete snapshot" }
                assertTrue("Only the exact generated fixture may be changed", source == snapshot.text)
                assertFalse("Place the cursor; do not select text for this whole-field check", snapshot.hasSelection)
                previous = polishField.get(ime) as? FieldPolishCoordinator
                val coordinator = FieldPolishCoordinator(scope,
                    editor = {
                        val session = sessionField.get(ime) as? Long
                        val current = ime.currentInputConnection
                        if (session != null && current != null) PolishEditor(session, current, ime.currentInputEditorInfo) else null
                    },
                    allowed = { true }, readKey = { "synthetic-test-key" }, config = { "synthetic-test-model" to "" },
                    correct = { text, _, _, _ ->
                        assertEquals(source, text)
                        delay(150)
                        FieldPolishResult.Success(revised)
                    },
                    state = { message, busy ->
                        showStatus.invoke(ime, message, busy)
                        if (!busy && message.isNotEmpty()) {
                            status = message
                            if (message.startsWith("Polish undone")) undone.countDown() else finished.countDown()
                        }
                    }, applied = { _, _ -> }, diagnostic = { flags ->
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("deliveryChecks", flags) })
                    })
                polishField.set(ime, coordinator)
                injected = true
                val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
                root.findViewById<View>(R.id.ime_polish).performClick()
            }
            assertTrue("Polish did not finish", finished.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val actual = FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("polishStatus", status)
                    putBoolean("matchesCorrection", actual == revised)
                    putBoolean("matchesCorrectionWithFinalNewline", actual == revised + "\n")
                    putBoolean("matchesCorrectionWithTwoFinalNewlines", actual == revised + "\n\n")
                    putBoolean("snapshotReadable", actual != null)
                    val extracted = ime.currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    putInt("extractedLength", extracted?.text?.length ?: -1)
                    putBoolean("extractedMatchesCorrectionWithFinalNewline", extracted?.text?.toString() == revised + "\n")
                    putBoolean("extractedMatchesCorrectionWithTwoFinalNewlines", extracted?.text?.toString() == revised + "\n\n")
                    putInt("selectionStart", extracted?.selectionStart ?: -1)
                    putInt("selectionEnd", extracted?.selectionEnd ?: -1)
                })
                assertEquals("Text polished", status)
                assertTrue("The host must contain exactly the correction and its retained line boundary", actual == revised + "\n")
                assertNull((polishField.get(ime) as FieldPolishCoordinator).completedResult())
                if (InstrumentationRegistry.getArguments().getString("verifyUndo") == "true") {
                    (polishField.get(ime) as FieldPolishCoordinator).undo()
                }
                if (InstrumentationRegistry.getArguments().getString("verifyToolbarUndo") == "true") {
                    val root = OdictoImeService::class.java.getDeclaredField("inputRoot").apply { isAccessible = true }.get(ime) as View
                    root.findViewById<View>(R.id.ime_undo).performClick()
                }
            }
            if (InstrumentationRegistry.getArguments().getString("verifyUndo") == "true") {
                assertTrue("Undo did not finish", undone.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertEquals("Polish undone", status)
                    assertTrue("Undo restores exactly the generated original field", FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source)
                }
            }
            if (InstrumentationRegistry.getArguments().getString("verifyToolbarUndo") == "true") {
                var restored = false
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!restored && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(50)
                    instrumentation.runOnMainSync {
                        restored = FieldPolishSnapshotReader.capture(requireNotNull(ime.currentInputConnection))?.text == source
                    }
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putBoolean("toolbarUndoRestoredPolish", restored) })
                assertTrue("The general toolbar Undo must also restore the original Polish field", restored)
            }
        } finally {
            instrumentation.runOnMainSync { if (injected) polishField.set(ime, previous); scope.cancel() }
        }
    }

    private fun activeService(): OdictoImeService? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
        return services.values.filterIsInstance<Service>().filterIsInstance<OdictoImeService>().singleOrNull()
    }
}
