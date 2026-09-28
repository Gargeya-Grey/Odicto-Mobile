package app.odicto.mobile.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

class SecureCredentialStore(context: Context) {
    // Keystore decryption is too slow for the press path, so decrypted values are cached per process.
    // Every write goes through this class, which keeps the cache authoritative.
    private val app = context.applicationContext
    private val preferences by lazy {
        EncryptedSharedPreferences.create(
            app,
            "odicto-secure",
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    fun get(name: String): String? = values[name] ?: preferences.getString(name, null)?.also { values[name] = it }
    fun put(name: String, value: String) { values[name] = value; preferences.edit().putString(name, value).apply() }
    fun remove(name: String) { values.remove(name); preferences.edit().remove(name).apply() }
    companion object { private val values = ConcurrentHashMap<String, String>() }
}
