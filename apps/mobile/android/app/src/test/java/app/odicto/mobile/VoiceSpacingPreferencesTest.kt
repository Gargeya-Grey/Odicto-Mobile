package app.odicto.mobile

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSpacingPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @org.junit.After fun restoreDefaults() = runBlocking {
        VoicePreferences.save(context, VoiceSettings())
    }

    @Test fun spacingPersistenceLifecycle() {
        gapHelperClampsWithoutMutatingSettings()
        resetStore()
        absentSpacingKeepsExistingDefaults()
        storedSpacingPresetsAndBoundsLoadWithoutChangingOtherFields()
        malformedStoredSpacingFallsBackLocally()
        saveNormalizesPublishedAndPersistedSpacing()
        updateNormalizesBeforeImmediatePublicationAndPersistence()
    }

    private fun resetStore() = runBlocking {
        val marker = "baseline-${System.nanoTime()}"
        context.dataStore.edit {
            it.clear()
            it[stringPreferencesKey("voice_model")] = marker
            it[intPreferencesKey("voice_key_gap_dp")] = 0
        }
        VoicePreferences.initialize(context)
        withTimeout(5_000) { VoicePreferences.state.first { it.model == marker } }
        Unit
    }

    private fun absentSpacingKeepsExistingDefaults() = runBlocking {
        VoicePreferences.initialize(context)
        withTimeout(5_000) { VoicePreferences.ready.first { it } }
        assertEquals(2, VoicePreferences.state.value.keys.tapTolerance)
        assertEquals("medium", VoicePreferences.state.value.keys.keySize)
        assertEquals("instrument", VoicePreferences.state.value.keys.keyFont)
    }

    private fun storedSpacingPresetsAndBoundsLoadWithoutChangingOtherFields() = runBlocking {
        VoicePreferences.initialize(context)
        for ((stored, expected) in listOf(0 to 0, 1 to 1, 2 to 2, -10 to 0, 20 to 2)) {
            context.dataStore.edit {
                it[intPreferencesKey("voice_tap_tolerance")] = stored
                it[stringPreferencesKey("voice_model")] = "model-$stored"
            }
            val value = withTimeout(5_000) { VoicePreferences.state.first { it.model == "model-$stored" } }
            assertEquals(expected, value.keys.tapTolerance)
        }
    }

    private fun malformedStoredSpacingFallsBackLocally() = runBlocking {
        context.dataStore.edit {
            it[stringPreferencesKey("voice_tap_tolerance")] = "broken"
            it[stringPreferencesKey("voice_model")] = "retained-model"
            it[booleanPreferencesKey("voice_haptics")] = false
            it[stringPreferencesKey("voice_key_size")] = "large"
        }
        VoicePreferences.initialize(context)
        withTimeout(5_000) { VoicePreferences.ready.first { it } }
        val value = withTimeout(5_000) { VoicePreferences.state.first { it.model == "retained-model" } }
        assertEquals(2, value.keys.tapTolerance)
        assertEquals("retained-model", value.model)
        assertEquals(false, value.haptics)
        assertEquals("large", value.keys.keySize)
    }

    private fun saveNormalizesPublishedAndPersistedSpacing() = runBlocking {
        for ((requested, expected) in listOf(-4 to 0, 0 to 0, 1 to 1, 2 to 2, 9 to 2)) {
            VoicePreferences.save(context, VoiceSettings(model = "keep", keys = KeySettings(tapTolerance = requested)))
            assertEquals(expected, VoicePreferences.state.value.keys.tapTolerance)
            assertEquals(expected, context.dataStore.data.first()[intPreferencesKey("voice_tap_tolerance")])
            assertEquals("keep", VoicePreferences.state.value.model)
        }
    }

    private fun updateNormalizesBeforeImmediatePublicationAndPersistence() = runBlocking {
        val persistenceScope = CoroutineScope(coroutineContext + Job())
        try {
            val persisted = context.dataStore.data.stateIn(persistenceScope)
            VoicePreferences.update(context) { it.copy(model = "updated", keys = it.keys.copy(tapTolerance = -7)) }
            assertEquals(0, VoicePreferences.state.value.keys.tapTolerance)
            val stored = withTimeout(5_000) { persisted.first { it[stringPreferencesKey("voice_model")] == "updated" } }
            assertEquals(0, stored[intPreferencesKey("voice_tap_tolerance")])
        } finally {
            persistenceScope.cancel()
        }
    }

    private fun gapHelperClampsWithoutMutatingSettings() {
        assertEquals(2, VoicePreferences.toleranceOf(VoiceSettings()))
        assertEquals(0, VoicePreferences.toleranceOf(VoiceSettings(keys = KeySettings(tapTolerance = -1))))
        assertEquals(1, VoicePreferences.toleranceOf(VoiceSettings(keys = KeySettings(tapTolerance = 1))))
        assertEquals(2, VoicePreferences.toleranceOf(VoiceSettings(keys = KeySettings(tapTolerance = 99))))
    }
}
