package app.odicto.mobile.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.dataStore by preferencesDataStore("odicto-settings")
class SettingsRepository(private val context: Context) {
    private val onboardingKey = booleanPreferencesKey("onboarding_complete")
    val onboardingComplete = context.dataStore.data.map { it[onboardingKey] ?: false }
    suspend fun setOnboardingComplete(value: Boolean) { context.dataStore.edit { it[onboardingKey] = value } }
}
