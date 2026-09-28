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

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            reads++
            this.text = this@Editor.text
            startOffset = 0
            partialStartOffset = -1
            selectionStart = start
            selectionEnd = end
        }
        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.selectAll) return false
            start = 0
            end = text.length
            return true
        }
        override fun getSelectedText(flags: Int): CharSequence = text.substring(minOf(start, end), maxOf(start, end))
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = text.substring(0, minOf(start, end)).takeLast(length)
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = text.substring(maxOf(start, end)).take(length)
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
            val low = minOf(start, end)
            this.text = this.text.replaceRange(low, maxOf(start, end), text.toString())
            start = low + text.toString().length
            end = start
            return true
        }
    }

    private val normal = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    @Test fun successRechecksAndReplacesOnlyTheOriginalField() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val editor = Editor("Helo")
            val finished = CompletableDeferred<String>()
            var submitted = ""
            var calls = 0
            val coordinator = FieldPolishCoordinator(scope, { PolishEditor(1L, editor, normal) }, { true }, { "key" },
                { "poolside/laguna-xs-2.1" to "" }, { text, _, _, _ -> calls++; submitted = text; FieldPolishResult.Success("Hello") },
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
                { "model" to "" }, { text, _, _, _ -> calls++; submitted = text; FieldPolishResult.Success("Hello") },
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
                { "model" to "" }, { text, _, _, _ -> submitted = text; FieldPolishResult.Success("👨‍💻") },
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
                { "model" to "" }, { _, _, _, _ -> calls++; FieldPolishResult.Success("Hello") },
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
                { "model" to "" }, { _, _, _, _ -> FieldPolishResult.Success("Hello") },
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
                { "model" to "" }, { _, _, _, _ -> FieldPolishResult.Success("Hello") },
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
                { "model" to "" }, { _, _, _, _ -> calls++; FieldPolishResult.Success("Hello $calls") },
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
                    { "model" to "" }, { _, _, _, _ ->
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
                { "model" to "" }, { _, _, _, _ ->
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
                    { "model" to "" }, { _, _, _, _ -> FieldPolishResult.Success(output) },
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
                { "model" to "" }, { _, _, _, _ -> FieldPolishResult.Success("Hello") },
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
            val coordinator = FieldPolishCoordinator(scope, { active }, { true }, { "key" }, { "model" to "" },
                { _, _, _, _ -> called.complete(Unit); response.await() },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            active = PolishEditor(2L, Editor("other"), normal)
            response.complete(FieldPolishResult.Success("Hello"))
            assertEquals("Field changed. Copy completed result within 120 seconds, or dismiss.", withTimeout(5_000) { finished.await() })
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
                { "model" to "" }, { _, _, _, _ -> called.complete(Unit); response.await() },
                { message, busy -> if (!busy && message.isNotEmpty()) finished.complete(message) }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            editor.text = "Helo!"
            response.complete(FieldPolishResult.Success("Hello"))
            assertEquals("Field changed. Copy completed result within 120 seconds, or dismiss.", withTimeout(5_000) { finished.await() })
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
                { "model" to "" }, { _, _, _, _ -> throw AssertionError("Should not call provider") },
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
                { "model" to "" }, { _, _, _, _ -> throw AssertionError("Should not call provider") },
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
                { "model" to "" }, { _, _, _, _ -> throw AssertionError("Should not call provider") },
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
                { "model" to "" }, { _, _, _, _ -> called.complete(Unit); response.await() }, { _, _ -> }, { _, _ -> })
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
                { "model" to "" }, { _, _, _, _ -> called.complete(Unit); response.await() }, { _, _ -> }, { _, _ -> })
            coordinator.start()
            withTimeout(5_000) { called.await() }
            coordinator.onSelectionChanged(2, 2)
            response.complete(FieldPolishResult.Success("Hello"))
            assertFalse(coordinator.busy)
            assertEquals("Helo", editor.text)
            assertEquals(0, editor.commits)
        } finally { scope.cancel() }
    }
}
