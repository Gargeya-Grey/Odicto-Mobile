package app.odicto.mobile.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.storage.dataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = UpdatePolicy.CHECK_INTERVAL_MS
    private val hour = UpdatePolicy.RETRY_INTERVAL_MS

    // The app's DataStore is one per process, so every test starts and ends with it empty.
    @Before @After fun clean() { runBlocking { context.dataStore.edit { it.clear() } } }

    private fun feedWith(vararg tags: String) = "[" + tags.joinToString(",") { """{"tag_name":"$it","draft":false,"prerelease":true}""" } + "]"

    private fun installed(): ReleaseVersion = ReleaseVersion.parse(runBlocking { UpdateChecker.snapshot(context) }.current)
        ?: error("the test app must carry a plain MAJOR.MINOR.PATCH versionName")

    private class Counter(private val body: () -> String) {
        var calls = 0
        suspend fun fetch(): String { calls++; return body() }
    }

    private fun newer(): String = installed().let { "v${it.major}.${it.minor + 1}.0" }

    @Test fun aNewerReleaseIsOfferedAndTheDayLimitHolds() = runBlocking {
        val feed = Counter { feedWith("v0.0.1", newer()) }
        val first = UpdateChecker.check(context, now = 1_000 * day, fetch = feed::fetch)
        assertEquals(newer().removePrefix("v"), first.offer.toString())
        assertEquals(1, feed.calls)

        UpdateChecker.check(context, now = 1_000 * day + hour, fetch = feed::fetch)
        assertEquals("a second open within the day does not ask again", 1, feed.calls)

        UpdateChecker.check(context, now = 1_001 * day + 1, fetch = feed::fetch)
        assertEquals("the next day it asks again", 2, feed.calls)
    }

    @Test fun theSameOrAnOlderReleaseOffersNothing() = runBlocking {
        val current = installed()
        val snapshot = UpdateChecker.check(context, now = 1_000 * day) { feedWith("v$current", "v0.0.1") }
        assertNull(snapshot.offer)
    }

    @Test fun turnedOffNeverAsksAndShowsNothing() = runBlocking {
        UpdateChecker.check(context, now = 1_000 * day) { feedWith(newer()) }
        val off = UpdateChecker.setEnabled(context, false)
        assertFalse(off.enabled)
        assertNull("an offer found earlier is hidden too", off.offer)

        val feed = Counter { feedWith(newer()) }
        val later = UpdateChecker.check(context, now = 1_010 * day, fetch = feed::fetch)
        assertEquals(0, feed.calls)
        assertNull(later.offer)

        val on = UpdateChecker.setEnabled(context, true)
        assertTrue(on.enabled)
        assertNotNull("turning it back on brings the known offer back", on.offer)
    }

    @Test fun aFailedRequestShowsNothingNewAndRetriesAfterAnHour() = runBlocking {
        val feed = Counter { throw java.io.IOException("offline") }
        val first = UpdateChecker.check(context, now = 1_000 * day, fetch = feed::fetch)
        assertNull(first.offer)
        assertEquals(1, feed.calls)

        UpdateChecker.check(context, now = 1_000 * day + hour - 1, fetch = feed::fetch)
        assertEquals("not retried inside the hour", 1, feed.calls)

        UpdateChecker.check(context, now = 1_000 * day + hour, fetch = feed::fetch)
        assertEquals("retried after the hour", 2, feed.calls)
    }

    @Test fun aFailureKeepsWhatWasAlreadyKnown() = runBlocking {
        UpdateChecker.check(context, now = 1_000 * day) { feedWith(newer()) }
        val after = UpdateChecker.check(context, now = 1_002 * day) { throw java.io.IOException("offline") }
        assertNotNull(after.offer)
    }

    @Test fun notNowHidesThatVersionUntilANewerOneIsPublished() = runBlocking {
        UpdateChecker.check(context, now = 1_000 * day) { feedWith(newer()) }
        assertNull(UpdateChecker.dismiss(context).offer)
        assertNull("it stays hidden on the next day's check", UpdateChecker.check(context, now = 1_002 * day) { feedWith(newer()) }.offer)

        val current = installed()
        val evenNewer = "v${current.major}.${current.minor + 2}.0"
        val shown = UpdateChecker.check(context, now = 1_004 * day) { feedWith(newer(), evenNewer) }
        assertEquals(evenNewer.removePrefix("v"), shown.offer.toString())
    }

    @Test fun theDownloadPageIsBuiltFromTheVersionNotFromTheFeed() {
        assertEquals("https://github.com/Gargeya-Grey/Odicto-Mobile/releases/tag/v1.2.0", UpdateChecker.releasePageUrl(ReleaseVersion(1, 2, 0)))
    }
}