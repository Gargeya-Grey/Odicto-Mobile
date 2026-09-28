package app.odicto.mobile

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.odicto.mobile.storage.ClipboardMigration
import app.odicto.mobile.storage.ClipboardRepository
import app.odicto.mobile.storage.ClipboardSerializer
import app.odicto.mobile.storage.ClipboardState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun exactPayloadAndPinsSurviveClearRestartAndOnlyAnActualRecopyReturns() = runBlocking {
        val file = folder.newFile("clipboard.json").apply { delete() }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun repository() = ClipboardRepository(DataStoreFactory.create(
            serializer = ClipboardSerializer,
            migrations = listOf(ClipboardMigration { mutablePreferencesOf() }),
            scope = scope,
            produceFile = { file },
        ))
        var repo = repository()
        val text = " \n" + "x".repeat(800) + "\u001F👩🏽‍💻\n "
        repo.capture(listOf(text, text + "second"), "event-1")
        assertEquals(listOf(text, text + "second"), repo.data.first().history)
        repo.setPinned(text, true)
        repo.clearUnpinned()
        assertEquals(listOf(text), repo.data.first().pins)
        assertTrue(repo.data.first().history.isEmpty())
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = repository()
        assertEquals(listOf(text), repo.data.first().pins)
        assertTrue(repo.data.first().history.isEmpty())
        repo.capture(listOf(text + "second"), "event-1")
        assertTrue(repo.data.first().history.isEmpty())
        repo.capture(listOf(text + "second"), "event-1", isChange = true)
        assertEquals(listOf(text + "second"), repo.data.first().history)
        repo.setAutomaticCapture(false)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = repository()
        assertFalse(repo.data.first().automaticCapture)
        repo.capture(listOf("disabled"), "event-3")
        assertEquals(listOf(text + "second"), repo.data.first().history)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun migrationPreservesRecoverableWhitespaceOrderingAndAllPinsWithoutChangingSettings() = runBlocking {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("voice_clip_history") to " a \u001Fline\n👩🏽‍💻",
            stringSetPreferencesKey("voice_pinned_clips") to (1..8).map { " pin-$it " }.toSet(),
            stringPreferencesKey("voice_model") to "unchanged-model",
        )
        val migration = ClipboardMigration { prefs }
        val state = migration.migrate(ClipboardState())
        assertEquals(listOf(" a ", "line\n👩🏽‍💻"), state.history)
        assertEquals(8, state.pins.size)
        assertTrue(" pin-1 " in state.pins)
        assertTrue(state.automaticCapture)
        assertFalse(migration.shouldMigrate(state.copy(automaticCapture = false)))
        assertEquals("unchanged-model", prefs[stringPreferencesKey("voice_model")])
    }

    @Test fun migrationCleanupRunsOnlyAfterTheNewJsonIsDurable() = runBlocking {
        val file = folder.newFile("migration.json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var cleaned = false
        val migration = ClipboardMigration(
            legacy = { mutablePreferencesOf(stringPreferencesKey("voice_clip_history") to " exact ") },
            cleanup = {
                val committed = file.inputStream().use { ClipboardSerializer.readFrom(it) }
                assertEquals(listOf(" exact "), committed.history)
                assertEquals(1, committed.migrationVersion)
                cleaned = true
            },
        )
        try {
            val repo = ClipboardRepository(DataStoreFactory.create(
                serializer = ClipboardSerializer,
                migrations = listOf(migration),
                scope = scope,
                produceFile = { file },
            ))
            assertEquals(listOf(" exact "), repo.data.first().history)
            assertTrue(cleaned)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun rapidTransactionalMutationsKeep50UnpinnedAndEveryPin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = folder.newFile("rapid.json").apply { delete() }
            val repo = ClipboardRepository(DataStoreFactory.create(
                serializer = ClipboardSerializer,
                migrations = listOf(ClipboardMigration { mutablePreferencesOf() }),
                scope = scope,
                produceFile = { file },
            ))
            repeat(8) {
                repo.capture(listOf("pin-$it"), "p-$it")
                repo.setPinned("pin-$it", true)
            }
            coroutineScope { repeat(100) { launch { repo.capture(listOf("copy-$it"), "c-$it") } } }
            assertEquals(50, repo.data.first().history.size)
            assertEquals(8, repo.data.first().pins.size)
            repo.delete(setOf("pin-0", "copy-99"))
            assertTrue("pin-0" in repo.data.first().pins)
            repo.setPinned("pin-0", false)
            assertEquals("pin-0", repo.data.first().history.first())
            assertTrue(repo.data.first().history.size <= 50)
            repo.clearUnpinned()
            assertEquals(7, repo.data.first().pins.size)
            assertTrue(repo.data.first().history.isEmpty())
            coroutineScope {
                repeat(20) { index ->
                    launch {
                        val text = "interleaved-$index"
                        repo.capture(listOf(text), "interleaved-event-$index")
                        repo.setPinned(text, true)
                        repo.delete(setOf(text))
                    }
                }
            }
            assertEquals(27, repo.data.first().pins.size)
            assertTrue(repo.data.first().history.isEmpty())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
