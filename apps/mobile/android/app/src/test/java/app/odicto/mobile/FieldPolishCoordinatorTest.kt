package app.odicto.mobile

import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.FieldPolishCoordinator
import app.odicto.mobile.ime.FieldPolishResult
import app.odicto.mobile.ime.PolishConfig
import app.odicto.mobile.ime.PolishEditor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FieldPolishCoordinatorTest {
    private class Editor(var text: String) : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
        var start = text.length
        var end = text.length
        var reads = 0
        var commits = 0
        var rejectCommit = false
        var ignoreCommit = false
        var rejectSelection = false
        var keepsFinalNewline = false
        var readbackDelayMs = 0L
        private var previousReadback: Triple<String, Int, Int>? = null
        private var readbackReadyAt = 0L
        private fun readback(): Triple<String, Int, Int> = previousReadback?.takeIf {
            System.nanoTime() / 1_000_000 < readbackReadyAt
        } ?: Triple(text, start, end)

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            reads++
            val shown = readback()
            this.text = shown.first
            startOffset = 0
            partialStartOffset = -1
            selectionStart = shown.second
            selectionEnd = shown.third
        }
        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.selectAll) return false
            start = 0
            end = text.length
            return true
        }
        override fun getSelectedText(flags: Int): CharSequence = readback().let { it.first.substring(minOf(it.second, it.third), maxOf(it.second, it.third)) }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = readback().let { it.first.substring(0, minOf(it.second, it.third)).takeLast(length) }
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = readback().let { it.first.substring(maxOf(it.second, it.third)).take(length) }
        override fun setSelection(start: Int, end: Int): Boolean {
            if (rejectSelection) return false
            this.start = start
            this.end = end
            return true
        }
        override fun finishComposingText(): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            if (rejectCommit) return false
            if (ignoreCommit) return true
            previousReadback = Triple(this.text, start, end)
            readbackReadyAt = System.nanoTime() / 1_000_000 + readbackDelayMs
            val low = minOf(start, end)
            this.text = this.text.replaceRange(low, maxOf(start, end), text.toString())
            if (keepsFinalNewline && !this.text.endsWith("\n")) this.text += "\n"
            start = low + text.toString().length
            end = start
            return true
        }
    }

    private val normal = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    @Test fun unreadableSettingsRefuseBeforeReadingAKeyOrContactingAProvider() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            var keyReads = 0
            var requests = 0
            var message = ""
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true },
                { keyReads++; "old-openrouter-key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = false) },
                { _, _, _ -> requests++; FieldPolishResult.Success("Hello") },
                { result, _ -> message = result }, { _, _ -> })
            coordinator.start()
            assertEquals("Could not load Polish settings. Restart Odicto and check Settings.", message)
            assertEquals(0, keyReads)
            assertEquals(0, requests)
            assertEquals(0, editor.commits)
            assertEquals("Helo", editor.text)
            assertFalse(coordinator.busy)
        } finally { scope.cancel() }
    }

    @Test fun providerAndKeyAreBoundToTheSettingsAtTheMomentPolishStarts() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val editor = Editor("Helo")
            var selected = PolishConfig("gemini", "gemini-fixture", "prompt", settingsReadable = true)
            var requestedProvider = ""
            var sent: Triple<String, PolishConfig, String>? = null
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true },
                { provider -> requestedProvider = provider; entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); "gemini-key" },
                { selected }, { text, config, key -> sent = Triple(text, config, key); FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            selected = PolishConfig("openrouter", "other-model", "other-prompt", settingsReadable = true)
            release.countDown()
            assertEquals("Text polished", withTimeout(5_000) { finished.await() })
            assertEquals("gemini", requestedProvider)
            assertEquals(Triple("Helo", PolishConfig("gemini", "gemini-fixture", "prompt", settingsReadable = true), "gemini-key"), sent)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun missingGeminiKeyExplainsWhichKeyToAdd() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true },
                { null }, { PolishConfig("gemini", "gemini-fixture", "", settingsReadable = true) },
                { _, _, _ -> throw AssertionError("No key means no request") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Add a Gemini key in Odicto settings.", withTimeout(5_000) { finished.await() })
            assertEquals("Helo", editor.text)
        } finally { scope.cancel() }
    }

    @Test fun wholeFieldPolishPreservesTheHostFinalLineBoundaryAndCanBeUndone() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo\n").apply { keepsFinalNewline = true }
            val finished = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.trySend(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Text polished", withTimeout(5_000) { finished.receive() })
            assertEquals("Hello\n", editor.text)
            assertEquals(6, editor.start)
            assertEquals(null, coordinator.completedResult())
            assertEquals(1, editor.commits)
            coordinator.undo()
            assertEquals("Polish undone", withTimeout(5_000) { finished.receive() })
            assertEquals("Helo\n", editor.text)
            assertEquals(2, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun delayedReadbackConfirmsPolishAndUndoWithoutRepeatingEitherWrite() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo\n").apply { keepsFinalNewline = true; readbackDelayMs = 60 }
            val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            var requests = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> requests++; FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) messages.trySend(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Text polished", withTimeout(5_000) { messages.receive() })
            assertEquals("Hello\n", editor.text)
            assertEquals(1, editor.commits)
            assertEquals(1, requests)
            coordinator.undo()
            assertEquals("Polish undone", withTimeout(5_000) { messages.receive() })
            assertEquals("Helo\n", editor.text)
            assertEquals(2, editor.commits)
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }

    @Test fun changingTargetsDuringReadbackNeverWritesToTheNewFieldOrReportsSuccess() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val original = Editor("Helo").apply { readbackDelayMs = 100 }
            val other = Editor("Unrelated")
            var current = original
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(if (current === original) 1L else 2L, current, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { while (original.commits == 0) kotlinx.coroutines.delay(1) }
            current = other
            assertTrue(withTimeout(5_000) { finished.await() }.startsWith("Correction delivery not verified"))
            assertEquals("Unrelated", other.text)
            assertEquals(0, other.commits)
            assertEquals(1, original.commits)
        } finally { scope.cancel() }
    }

    @Test fun aHostCannotAddAParagraphMarkerThatWasNotInTheOriginalField() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo").apply { keepsFinalNewline = true }
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertTrue(withTimeout(5_000) { finished.await() }.startsWith("Correction delivery not verified"))
            assertEquals("Hello", coordinator.completedResult()!!.text)
            assertFalse(coordinator.undoAvailable.value)
            assertEquals(1, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun successRechecksAndReplacesOnlyTheOriginalField() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val finished = CompletableDeferred<String>()
            var submitted = ""
            var calls = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "poolside/laguna-xs-2.1", "", settingsReadable = true) }, { text, _, _ -> calls++; submitted = text; FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Text polished", withTimeout(5_000) { finished.await() })
            assertEquals("Hello", editor.text)
            assertEquals("Helo", submitted)
            assertEquals(1, calls)
            assertEquals(null, coordinator.completedResult())
            assertEquals(5, editor.start)
            assertEquals(1, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun existingPartialSelectionSendsAndReplacesOnlyCapturedRange() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo Helo\n untouched  ").apply { start = 5; end = 9 }
            val finished = CompletableDeferred<String>()
            var submitted = ""
            var calls = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { text, _, _ -> calls++; submitted = text; FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Text polished", withTimeout(5_000) { finished.await() })
            assertEquals("Helo", submitted)
            assertEquals("Helo Hello\n untouched  ", editor.text)
            assertEquals(5, editor.start)
            assertEquals(10, editor.end)
            assertEquals(1, editor.commits)
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun reversedUnicodeSelectionReplacesExactRangeAndSelectsResultBackwards() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("x👩‍💻e\u0301z").apply { start = 8; end = 1 }
            val finished = CompletableDeferred<String>()
            var submitted = ""
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { text, _, _ -> submitted = text; FieldPolishResult.Success("👨‍💻") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Text polished", withTimeout(5_000) { finished.await() })
            assertEquals("👩‍💻e\u0301", submitted)
            assertEquals("x👨‍💻z", editor.text)
            assertEquals(6, editor.start)
            assertEquals(1, editor.end)
            assertEquals(1, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun rejectedCommitKeepsOneCompletedResultForExplicitCopyDismissAndExpiry() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo").apply { rejectCommit = true }
            val finished = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            var now = 1_000L
            var calls = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> calls++; FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.trySend(message) }, { _, _ -> },
                now = { now })
            suspend fun complete() {
                coordinator.start()
                withTimeout(5_000) { finished.receive() }
            }
            complete()
            val first = coordinator.completedResult()!!
            assertEquals("Hello", first.text)
            assertEquals(121_000L, first.expiresAtElapsedRealtime)
            assertEquals("Helo", editor.text)
            assertEquals(4, editor.start)
            assertFalse(coordinator.copyCompletedResult(first.operationId) { false })
            assertEquals(first, coordinator.completedResult())
            var copied = ""
            assertTrue(coordinator.copyCompletedResult(first.operationId) { copied = it; true })
            assertEquals("Hello", copied)
            assertEquals(null, coordinator.completedResult())
            complete()
            val second = coordinator.completedResult()!!
            assertFalse(coordinator.dismissCompletedResult(first.operationId))
            assertTrue(coordinator.dismissCompletedResult(second.operationId))
            assertEquals(null, coordinator.completedResult())
            complete()
            val third = coordinator.completedResult()!!
            now = third.expiresAtElapsedRealtime
            assertFalse(coordinator.copyCompletedResult(third.operationId) { throw AssertionError("Expired result copied") })
            assertEquals(null, coordinator.recovery.value)
            assertEquals(3, calls)
            assertEquals(3, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun acceptedButUnchangedEditorKeepsRecoveryWithoutAppliedSignal() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo").apply { ignoreCommit = true }
            val finished = CompletableDeferred<String>()
            var applied = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> applied++ })
            coordinator.start()
            assertTrue(withTimeout(5_000) { finished.await() }.contains("not verified"))
            assertEquals("Helo", editor.text)
            assertEquals("Hello", coordinator.completedResult()!!.text)
            assertEquals(0, applied)
            assertEquals(1, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun recoveryExpiresAutomaticallyAndDoesNotPersistAcrossCoordinatorLifetime() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo").apply { rejectCommit = true }
            val finished = CompletableDeferred<Unit>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(Unit) }, { _, _ -> },
                recoveryTtlMs = 100L)
            coordinator.start()
            withTimeout(5_000) { finished.await() }
            withTimeout(5_000) { coordinator.recovery.first { it == null } }
            assertEquals(null, coordinator.completedResult())
        } finally { scope.cancel() }
    }

    @Test fun newerCompletedResultReplacesOldRecoveryAndScopeCancellationClearsIt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo").apply { rejectCommit = true }
            val finished = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            var calls = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> calls++; FieldPolishResult.Success("Hello $calls") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.trySend(Unit) }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { finished.receive() }
            val old = coordinator.completedResult()!!
            coordinator.start()
            withTimeout(5_000) { finished.receive() }
            assertEquals("Hello 2", coordinator.completedResult()!!.text)
            assertFalse(coordinator.copyCompletedResult(old.operationId) { throw AssertionError("Old result copied") })
            scope.cancel()
            withTimeout(5_000) { coordinator.recovery.first { it == null } }
            assertEquals(null, coordinator.completedResult())
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun movedAwayAndBackSelectionDiscardsEvenNonCancellableProviderCompletion() = runBlocking {
        for (result in listOf(FieldPolishResult.Success("Hello"),
            FieldPolishResult.Failure(FieldPolishResult.Reason.NETWORK, "late failure"))) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val editor = Editor("Helo")
                val called = CompletableDeferred<Unit>()
                val response = CompletableDeferred<FieldPolishResult>()
                val returned = CompletableDeferred<Unit>()
                val states = mutableListOf<String>()
                var calls = 0
                val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                    { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ ->
                        calls++
                        withContext(NonCancellable) {
                            called.complete(Unit)
                            val answer = response.await()
                            returned.complete(Unit)
                            answer
                        }
                    }, { message, _ -> states.add(message) }, { _, _ -> throw AssertionError("Stale apply") })
                coordinator.start()
                withTimeout(5_000) { called.await() }
                coordinator.onSelectionChanged(2, 2)
                coordinator.onSelectionChanged(4, 4)
                val count = states.size
                response.complete(result)
                withTimeout(5_000) { returned.await() }
                assertEquals(count, states.size)
                assertEquals(null, coordinator.completedResult())
                assertEquals("Helo", editor.text)
                assertEquals(0, editor.commits)
                assertEquals(1, calls)
                assertFalse(coordinator.busy)
            } finally { scope.cancel() }
        }
    }

    @Test fun supersededNonCooperativeResponseCannotOverwriteNewerResultOrStatus() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo").apply { rejectCommit = true }
            val firstCall = CompletableDeferred<kotlin.coroutines.Continuation<FieldPolishResult>>()
            val finished = CompletableDeferred<String>()
            val states = mutableListOf<String>()
            var calls = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ ->
                    calls++
                    if (calls == 1) kotlin.coroutines.suspendCoroutine { firstCall.complete(it) }
                    else FieldPolishResult.Success("Newest")
                }, { message, busy ->
                    states.add(message)
                    if (!busy && message.isNotEmpty()) finished.complete(message)
                }, { _, _ -> throw AssertionError("Rejected edit applied") })
            coordinator.start()
            val pending = withTimeout(5_000) { firstCall.await() }
            coordinator.cancel()
            coordinator.start()
            withTimeout(5_000) { finished.await() }
            val latest = coordinator.completedResult()!!
            val count = states.size
            pending.resumeWith(Result.success(FieldPolishResult.Success("Obsolete")))
            yield()
            assertEquals(latest, coordinator.completedResult())
            assertEquals("Newest", latest.text)
            assertEquals(count, states.size)
            assertEquals(2, calls)
            assertEquals(1, editor.commits)
            assertEquals("Helo", editor.text)
        } finally { scope.cancel() }
    }

    @Test fun invalidCompletedOutputNeverReplacesOrCreatesRecovery() = runBlocking {
        for (output in listOf("", " ", "x".repeat(20_001))) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val editor = Editor("Helo")
                val finished = CompletableDeferred<String>()
                val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                    { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success(output) },
                    { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
                coordinator.start()
                assertTrue(withTimeout(5_000) { finished.await() }.contains("empty or oversized"))
                assertEquals("Helo", editor.text)
                assertEquals(0, editor.commits)
                assertEquals(null, coordinator.completedResult())
            } finally { scope.cancel() }
        }
    }

    @Test fun rejectedRangeSelectionNeverCommitsAndKeepsRecovery() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo there").apply { start = 0; end = 4; rejectSelection = true }
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success("Hello") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertTrue(withTimeout(5_000) { finished.await() }.contains("rejected"))
            assertEquals("Helo there", editor.text)
            assertEquals(0, editor.commits)
            assertEquals("Hello", coordinator.completedResult()!!.text)
        } finally { scope.cancel() }
    }

    @Test fun changedTextOrEditorCannotReceiveTheNetworkResult() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val response = CompletableDeferred<FieldPolishResult>()
            val called = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<String>()
            var active: PolishEditor? = PolishEditor(1L, editor, normal)
            val coordinator = FieldPolishCoordinator(scope, { active }, { true }, { "key" }, { PolishConfig("openrouter", "model", "", settingsReadable = true) },
                { _, _, _ -> called.complete(Unit); response.await() },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            active = PolishEditor(2L, Editor("other"), normal)
            response.complete(FieldPolishResult.Success("Hello"))
            assertEquals("Field changed. You can copy the result or dismiss it.", withTimeout(5_000) { finished.await() })
            assertEquals("Hello", coordinator.completedResult()!!.text)
            assertEquals("Helo", editor.text)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun sameFieldEditedDuringRequestIsNotOverwritten() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val called = CompletableDeferred<Unit>()
            val response = CompletableDeferred<FieldPolishResult>()
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> called.complete(Unit); response.await() },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            editor.text = "Helo!"
            response.complete(FieldPolishResult.Success("Hello"))
            assertEquals("Field changed. You can copy the result or dismiss it.", withTimeout(5_000) { finished.await() })
            assertEquals("Hello", coordinator.completedResult()!!.text)
            assertEquals("Helo!", editor.text)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun missingOpenRouterKeyNeverCallsProviderOrChangesText() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val finished = CompletableDeferred<String>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { null },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> throw AssertionError("Should not call provider") },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            assertEquals("Add an OpenRouter key in Odicto settings.", withTimeout(5_000) { finished.await() })
            assertEquals("Helo", editor.text)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun emptyFieldGivesAnAccurateRefusalWithoutCallingProvider() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("")
            var message = ""
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> throw AssertionError("Should not call provider") },
                { value, _ -> message = value }, { _, _ -> })
            coordinator.start()
            assertEquals("Nothing to polish. Type some text first.", message)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun protectedEditorIsRefusedBeforeAnyRead() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("private")
            val password = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            var message = ""
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, password) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> throw AssertionError("Should not call provider") },
                { value, _ -> message = value }, { _, _ -> })
            coordinator.start()
            assertTrue(message.contains("password"))
            assertEquals(0, editor.reads)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun aUserSelectAllCancelsTheRequest() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val called = CompletableDeferred<Unit>()
            val response = CompletableDeferred<FieldPolishResult>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> called.complete(Unit); response.await() }, { _, _ -> }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            coordinator.onSelectionChanged(0, editor.text.length)
            response.complete(FieldPolishResult.Success("Hello"))
            assertFalse(coordinator.busy)
            assertEquals("Helo", editor.text)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    @Test fun movingSelectionCancelsWithoutApplyingStaleResult() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val called = CompletableDeferred<Unit>()
            val response = CompletableDeferred<FieldPolishResult>()
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> called.complete(Unit); response.await() }, { _, _ -> }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            coordinator.onSelectionChanged(2, 2)
            response.complete(FieldPolishResult.Success("Hello"))
            assertFalse(coordinator.busy)
            assertEquals("Helo", editor.text)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }

    private class UndoRig(scope: CoroutineScope, var editor: Editor, var session: Long = 1L, allowed: () -> Boolean = { true }) {
        val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val applied = mutableListOf<Pair<Int, Int>>()
        var replies = ArrayDeque<String>()
        val coordinator = FieldPolishCoordinator(scope, { PolishEditor(session, editor, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }) },
            allowed, { "key" }, { PolishConfig("openrouter", "model", "", settingsReadable = true) }, { _, _, _ -> FieldPolishResult.Success(replies.removeFirst()) },
            { message, busy -> if (!busy && message.isNotEmpty()) messages.trySend(message) }, { s, e -> applied += s to e })

        suspend fun polish(reply: String): String {
            replies.addLast(reply)
            coordinator.start()
            return withTimeout(5_000) { messages.receive() }
        }

        suspend fun undo(): String {
            coordinator.undo()
            return withTimeout(5_000) { messages.receive() }
        }
    }

    @Test fun undoRestoresTheOriginalWholeFieldAndItsCursor() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val rig = UndoRig(scope, Editor("Helo wrold").apply { start = 4; end = 4 })
            assertFalse(rig.coordinator.undoAvailable.value)
            assertEquals("Text polished", rig.polish("Hello world"))
            assertEquals("Hello world", rig.editor.text)
            assertTrue(rig.coordinator.undoAvailable.value)
            assertEquals("Polish undone", rig.undo())
            assertEquals("Helo wrold", rig.editor.text)
            assertEquals(4, rig.editor.start)
            assertEquals(4, rig.editor.end)
            assertEquals(4 to 4, rig.applied.last())
            assertFalse(rig.coordinator.undoAvailable.value)
            assertEquals("Nothing to undo.", rig.undo())
        } finally { scope.cancel() }
    }

    @Test fun undoRestoresASelectedRangeAndKeepsTextTypedAfterIt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val rig = UndoRig(scope, Editor("Say Helo now").apply { start = 4; end = 8 })
            assertEquals("Text polished", rig.polish("Hello"))
            assertEquals("Say Hello now", rig.editor.text)
            rig.editor.text += " and more"
            assertEquals("Polish undone", rig.undo())
            assertEquals("Say Helo now and more", rig.editor.text)
            assertEquals(4, rig.editor.start)
            assertEquals(8, rig.editor.end)
        } finally { scope.cancel() }
    }

    @Test fun undoRefusesWithoutWritingWhenTheTextBeforeOrInsideTheRangeChanged() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            for (edit in listOf<(String) -> String>({ "X$it" }, { it.replace("Hello", "Hullo") })) {
                val rig = UndoRig(scope, Editor("Say Helo now").apply { start = 4; end = 8 })
                rig.polish("Hello")
                val changed = edit(rig.editor.text)
                rig.editor.text = changed
                val commits = rig.editor.commits
                assertEquals("Text changed since polish. Nothing was undone.", rig.undo())
                assertEquals(changed, rig.editor.text)
                assertEquals(commits, rig.editor.commits)
                assertFalse("a refused entry is spent", rig.coordinator.undoAvailable.value)
            }
        } finally { scope.cancel() }
    }

    @Test fun undoStepsBackThroughConsecutivePolishesNewestFirst() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val rig = UndoRig(scope, Editor("helo"))
            rig.polish("Helo")
            rig.polish("Hello")
            assertEquals("Hello", rig.editor.text)
            assertEquals("Polish undone", rig.undo())
            assertEquals("Helo", rig.editor.text)
            assertTrue(rig.coordinator.undoAvailable.value)
            assertEquals("Polish undone", rig.undo())
            assertEquals("helo", rig.editor.text)
            assertFalse(rig.coordinator.undoAvailable.value)
        } finally { scope.cancel() }
    }

    @Test fun undoIsRefusedInAnotherFieldOrEditorSession() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val first = Editor("Helo")
            val rig = UndoRig(scope, first)
            rig.polish("Hello")
            rig.editor = Editor("Helo")
            assertEquals("Undo only works in the field you polished.", rig.undo())
            assertEquals("Helo", rig.editor.text)
            assertEquals(0, rig.editor.commits)
            assertFalse(rig.coordinator.undoAvailable.value)

            val sessioned = UndoRig(scope, Editor("Helo"))
            sessioned.polish("Hello")
            sessioned.session = 2L
            assertEquals("Undo only works in the field you polished.", sessioned.undo())
            assertEquals("Hello", sessioned.editor.text)
        } finally { scope.cancel() }
    }

    @Test fun clearingUndoDropsRetainedText() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val rig = UndoRig(scope, Editor("Helo"))
            rig.polish("Hello")
            rig.coordinator.clearUndo()
            assertFalse(rig.coordinator.undoAvailable.value)
            assertEquals("Nothing to undo.", rig.undo())
            assertEquals("Hello", rig.editor.text)
        } finally { scope.cancel() }
    }

    @Test fun undoWaitsForVoiceWork() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var allowed = true
            val rig = UndoRig(scope, Editor("Helo"), allowed = { allowed })
            rig.polish("Hello")
            allowed = false
            assertEquals("Finish voice typing before undoing.", rig.undo())
            assertEquals("Hello", rig.editor.text)
            assertTrue("a deferred undo is not spent", rig.coordinator.undoAvailable.value)
            allowed = true
            assertEquals("Polish undone", rig.undo())
        } finally { scope.cancel() }
    }
}
