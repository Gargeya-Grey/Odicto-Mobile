package app.odicto.mobile.storage

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class ClipboardState(
    val history: List<String> = emptyList(),
    val pins: List<String> = emptyList(),
    val automaticCapture: Boolean = false,
    val migrationVersion: Int = 0,
    val lastCaptureToken: String = "",
)

internal object ClipboardSerializer : Serializer<ClipboardState> {
    override val defaultValue = ClipboardState()

    override suspend fun readFrom(input: InputStream): ClipboardState {
        try {
            val json = JSONObject(input.readBytes().toString(Charsets.UTF_8))
            if (json.getInt("version") != 1) throw CorruptionException("Unsupported clipboard format")
            fun strings(name: String): List<String> {
                val values = json.getJSONArray(name)
                return (0 until values.length()).map { values.getString(it) }
                    .filter { it.isNotEmpty() }.distinct()
            }
            val pins = strings("pins")
            return ClipboardState(
                history = strings("history").filter { it !in pins }.take(50),
                pins = pins,
                automaticCapture = json.getBoolean("automaticCapture"),
                migrationVersion = json.getInt("migrationVersion"),
                lastCaptureToken = json.getString("lastCaptureToken"),
            )
        } catch (_: JSONException) {
            throw CorruptionException("Invalid clipboard format")
        }
    }

    override suspend fun writeTo(t: ClipboardState, output: OutputStream) {
        val json = JSONObject()
            .put("version", 1)
            .put("history", JSONArray(t.history))
            .put("pins", JSONArray(t.pins))
            .put("automaticCapture", t.automaticCapture)
            .put("migrationVersion", t.migrationVersion)
            .put("lastCaptureToken", t.lastCaptureToken)
        output.write(json.toString().toByteArray(Charsets.UTF_8))
    }
}

internal class ClipboardMigration(
    private val cleanup: suspend () -> Unit = {},
    private val legacy: suspend () -> Preferences,
) : DataMigration<ClipboardState> {
    override suspend fun shouldMigrate(currentData: ClipboardState) = currentData.migrationVersion < 1

    override suspend fun migrate(currentData: ClipboardState): ClipboardState {
        if (!shouldMigrate(currentData)) return currentData
        val preferences = legacy()
        fun read(name: String): List<String> {
            val raw = preferences.asMap().entries.firstOrNull { it.key.name == name }?.value
            return when (raw) {
                is String -> raw.split('\u001F')
                is Set<*> -> raw.filterIsInstance<String>()
                else -> emptyList()
            }.filter { it.isNotEmpty() }.distinct()
        }
        val pins = (currentData.pins + read("voice_pinned_clips")).distinct()
        return currentData.copy(
            pins = pins,
            history = (currentData.history + read("voice_clip_history")).distinct().filter { it !in pins }.take(50),
            automaticCapture = true,
            migrationVersion = 1,
        )
    }

    override suspend fun cleanUp() = cleanup()
}

internal class ClipboardRepository(private val store: DataStore<ClipboardState>) {
    val data: Flow<ClipboardState> = store.data

    suspend fun capture(texts: List<String>, token: String, isChange: Boolean = false) {
        val incoming = texts.toList()
        store.updateData { state ->
            if (!state.automaticCapture || (!isChange && token == state.lastCaptureToken)) state
            else {
                var pins = state.pins
                var history = state.history
                for (text in incoming.asReversed()) {
                    if (text.isEmpty()) continue
                    if (text in pins) pins = listOf(text) + pins.filter { it != text }
                    else history = listOf(text) + history.filter { it != text }
                }
                state.copy(pins = pins, history = history.take(50), lastCaptureToken = token)
            }
        }
    }

    suspend fun setPinned(text: String, pinned: Boolean) {
        store.updateData { state ->
            when {
                pinned && text in state.history -> state.copy(
                    pins = listOf(text) + state.pins.filter { it != text },
                    history = state.history.filter { it != text },
                )
                !pinned && text in state.pins -> state.copy(
                    pins = state.pins.filter { it != text },
                    history = (listOf(text) + state.history.filter { it != text }).take(50),
                )
                else -> state
            }
        }
    }

    suspend fun delete(selected: Set<String>) {
        val selection = selected.toSet()
        store.updateData { it.copy(history = it.history.filter { text -> text !in selection }) }
    }

    suspend fun clearUnpinned() {
        store.updateData { it.copy(history = emptyList()) }
    }

    suspend fun setAutomaticCapture(enabled: Boolean) {
        store.updateData { it.copy(automaticCapture = enabled, migrationVersion = 1) }
    }

    companion object {
        @Volatile private var instance: ClipboardRepository? = null

        fun get(context: Context): ClipboardRepository = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                ClipboardRepository(DataStoreFactory.create(
                    serializer = ClipboardSerializer,
                    migrations = listOf(ClipboardMigration(
                        legacy = { app.dataStore.data.first() },
                        cleanup = {
                            app.dataStore.edit { preferences ->
                                preferences.asMap().keys.filter {
                                    it.name == "voice_pinned_clips" || it.name == "voice_clip_history"
                                }.forEach { preferences.remove(it) }
                            }
                        },
                    )),
                    produceFile = { File(app.noBackupFilesDir, "clipboard/history-v1.json") },
                )).also { instance = it }
            }
        }
    }
}
