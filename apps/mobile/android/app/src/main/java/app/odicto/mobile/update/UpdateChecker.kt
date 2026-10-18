package app.odicto.mobile.update

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.odicto.mobile.storage.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the app shows: whether checking is on, the installed version, and the version on offer, if any. */
data class UpdateSnapshot(val enabled: Boolean, val current: String, val offer: ReleaseVersion?)

/**
 * Tells the user when a newer Odicto has been published. It asks GitHub for the repository's release list,
 * at most once a day, only while the setting is on, and only when the app is opened. It sends no identifier,
 * account, or app data, reads nothing but release numbers from the answer, and never downloads or installs:
 * the offer opens the release page in the browser, built from the validated version number and never from a
 * link in the response.
 */
object UpdateChecker {
    const val REPOSITORY = "Gargeya-Grey/Odicto-Mobile"
    private const val FEED_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    private const val MAX_FEED_BYTES = 1_000_000

    private val enabledKey = booleanPreferencesKey("update_check_enabled")
    private val latestKey = stringPreferencesKey("update_latest")
    private val dismissedKey = stringPreferencesKey("update_dismissed")
    private val checkedAtKey = longPreferencesKey("update_checked_at")
    private val attemptedAtKey = longPreferencesKey("update_attempted_at")

    /** One request at a time, so a quick return to the app cannot start a second check. */
    private val lock = Mutex()
    private val client by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }

    fun releasePageUrl(version: ReleaseVersion): String = "https://github.com/$REPOSITORY/releases/tag/v$version"

    suspend fun snapshot(context: Context): UpdateSnapshot {
        val prefs = context.dataStore.data.first()
        val name = installedName(context)
        val enabled = prefs[enabledKey] ?: true
        return UpdateSnapshot(
            enabled = enabled,
            current = name,
            // Turned off means nothing is shown, even a version found before it was turned off.
            offer = if (!enabled) null else UpdatePolicy.offer(
                installed = ReleaseVersion.parse(name),
                latest = ReleaseVersion.parse(prefs[latestKey]),
                dismissed = ReleaseVersion.parse(prefs[dismissedKey]),
            ),
        )
    }

    /** Asks for the release list when one is due, then returns what to show. A failed request changes nothing visible. */
    suspend fun check(
        context: Context,
        now: Long = System.currentTimeMillis(),
        fetch: suspend () -> String = ::fetchFeed,
    ): UpdateSnapshot = lock.withLock {
        val prefs = context.dataStore.data.first()
        val due = UpdatePolicy.due(prefs[enabledKey] ?: true, now, prefs[checkedAtKey] ?: 0L, prefs[attemptedAtKey] ?: 0L)
        if (due) {
            try {
                val newest = ReleaseFeed.newest(fetch())
                context.dataStore.edit {
                    if (newest != null) it[latestKey] = newest.toString()
                    it[checkedAtKey] = now
                    it[attemptedAtKey] = now
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                context.dataStore.edit { it[attemptedAtKey] = now }
            }
        }
        snapshot(context)
    }

    suspend fun setEnabled(context: Context, enabled: Boolean): UpdateSnapshot {
        context.dataStore.edit { it[enabledKey] = enabled }
        return snapshot(context)
    }

    /** "Not now": hides this version; a newer one shows again. */
    suspend fun dismiss(context: Context): UpdateSnapshot {
        val offered = snapshot(context).offer
        if (offered != null) context.dataStore.edit { it[dismissedKey] = offered.toString() }
        return snapshot(context)
    }

    private fun installedName(context: Context): String =
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }

    private suspend fun fetchFeed(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(FEED_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Release list request failed (${response.code})")
            val stream = response.body?.byteStream() ?: throw IOException("Release list was empty")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_FEED_BYTES) throw IOException("Release list was too large")
                out.write(buffer, 0, read)
            }
            out.toString("UTF-8")
        }
    }
}
