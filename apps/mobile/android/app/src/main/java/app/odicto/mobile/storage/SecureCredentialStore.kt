package app.odicto.mobile.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureCredentialStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "odicto-secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun get(name: String): String? = preferences.getString(name, null)
    fun put(name: String, value: String) = preferences.edit().putString(name, value).apply()
    fun remove(name: String) = preferences.edit().remove(name).apply()
}
