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
    private val interval = UpdatePolicy.CHECK_INTERVAL_MS
    private val retry = UpdatePolicy.RETRY_INTERVAL_MS
    private val base = 1_000 * interval

    // The app's DataStore is one per process, so every test starts and ends with it empty.
    @Before @After fun clean() { runBlocking { context.dataStore.edit { it.clear() } } }

    private fun feedWith(vararg tags: String) = "[" + tags.joinToString(",") { """{"tag_name":"$it","draft":false,"prerelease":true}""" } + "]"

    private fun installed(): ReleaseVersion = ReleaseVersion.parse(runBlocking { UpdateChecker.snapshot(context) }.current)
        ?: error("the test app must carry a plain MAJOR.MINOR.PATCH versionName")

    private class Feed(private val answer: (String?) -> FeedAnswer) {
        var calls = 0
        val etags = mutableListOf<String?>()
        suspend fun fetch(etag: String?): FeedAnswer { calls++; etags += etag; return answer(etag) }
    }

    private fun newer(): String = installed().let { "v${it.major}.${it.minor + 1}.0" }

    @Test fun aNewerReleaseIsOfferedAndTheIntervalHolds() = runBlocking {
        val feed = Feed { FeedAnswer(feedWith("v0.0.1", newer())) }
        val first = UpdateChecker.check(context, now = base, fetch = feed::fetch)
        assertEquals(newer().removePrefix("v"), first.offer.toString())
        assertEquals(base, first.checkedAt)
        assertFalse(first.failed)
        assertEquals(1, feed.calls)

        UpdateChecker.check(context, now = base + interval - 1, fetch = feed::fetch)
        assertEquals("a second open inside the interval does not ask again", 1, feed.calls)

        UpdateChecker.check(context, now = base + interval, fetch = feed::fetch)
        assertEquals("an hour later it asks again", 2, feed.calls)
    }

    @Test fun aReleasePublishedAfterTheLastCheckIsFoundTheNextHour() = runBlocking {
        val current = installed()
        val none = UpdateChecker.check(context, now = base) { FeedAnswer(feedWith("v$current")) }
        assertNull(none.offer)
        val found = UpdateChecker.check(context, now = base + interval) { FeedAnswer(feedWith("v$current", newer())) }
        assertEquals("the case that went unnoticed for a day before", newer().removePrefix("v"), found.offer.toString())
    }

    @Test fun anUnchangedListIsAskedForWithItsTagAndKeepsTheKnownOffer() = runBlocking {
        val feed = Feed { etag -> if (etag == "\"abc\"") FeedAnswer(null) else FeedAnswer(feedWith(newer()), "\"abc\"") }
        UpdateChecker.check(context, now = base, fetch = feed::fetch)
        val second = UpdateChecker.check(context, now = base + interval, fetch = feed::fetch)
        assertEquals(listOf(null, "\"abc\""), feed.etags)
        assertNotNull("a 304 keeps what was already known", second.offer)
        assertEquals("and still counts as a successful check", base + interval, second.checkedAt)
        assertFalse(second.failed)
    }

    @Test fun aListThatCouldNotBeReadIsNeverRememberedAsUnchanged() = runBlocking {
        val feed = Feed { FeedAnswer("""{"message":"rate limited"}""", "\"bad\"") }
        UpdateChecker.check(context, now = base, fetch = feed::fetch)
        UpdateChecker.check(context, now = base + interval, fetch = feed::fetch)
        assertEquals(listOf<String?>(null, null), feed.etags)
    }

    @Test fun checkNowSkipsTheTimingRulesButNotTheSwitch() = runBlocking {
        val feed = Feed { FeedAnswer(feedWith(newer())) }
        UpdateChecker.check(context, now = base, fetch = feed::fetch)
        UpdateChecker.check(context, now = base + 1, force = true, fetch = feed::fetch)
        assertEquals("forced, one second after the last check", 2, feed.calls)
        UpdateChecker.setEnabled(context, false)
        UpdateChecker.check(context, now = base + 2, force = true, fetch = feed::fetch)
        assertEquals("never while the switch is off", 2, feed.calls)
    }

    @Test fun theSameOrAnOlderReleaseOffersNothing() = runBlocking {
        val current = installed()
        val snapshot = UpdateChecker.check(context, now = base) { FeedAnswer(feedWith("v$current", "v0.0.1")) }
        assertNull(snapshot.offer)
    }

    @Test fun turnedOffNeverAsksAndShowsNothing() = runBlocking {
        UpdateChecker.check(context, now = base) { FeedAnswer(feedWith(newer())) }
        val off = UpdateChecker.setEnabled(context, false)
        assertFalse(off.enabled)
        assertNull("an offer found earlier is hidden too", off.offer)

        val feed = Feed { FeedAnswer(feedWith(newer())) }
        val later = UpdateChecker.check(context, now = base + 10 * interval, fetch = feed::fetch)
        assertEquals(0, feed.calls)
        assertNull(later.offer)

        val on = UpdateChecker.setEnabled(context, true)
        assertTrue(on.enabled)
        assertNotNull("turning it back on brings the known offer back", on.offer)
    }

    @Test fun aFailedRequestIsReportedShowsNothingNewAndRetriesSoon() = runBlocking {
        val feed = Feed { throw java.io.IOException("offline") }
        val first = UpdateChecker.check(context, now = base, fetch = feed::fetch)
        assertNull(first.offer)
        assertTrue("the screen can say the check failed", first.failed)
        assertEquals(0L, first.checkedAt)
        assertEquals(1, feed.calls)

        UpdateChecker.check(context, now = base + retry - 1, fetch = feed::fetch)
        assertEquals("not retried inside the short wait", 1, feed.calls)

        UpdateChecker.check(context, now = base + retry, fetch = feed::fetch)
        assertEquals("retried after it", 2, feed.calls)
    }

    @Test fun aFailureKeepsWhatWasAlreadyKnownAndASuccessClearsTheFailure() = runBlocking {
        UpdateChecker.check(context, now = base) { FeedAnswer(feedWith(newer())) }
        val failed = UpdateChecker.check(context, now = base + interval) { throw java.io.IOException("offline") }
        assertNotNull(failed.offer)
        assertTrue(failed.failed)
        val recovered = UpdateChecker.check(context, now = base + interval + retry) { FeedAnswer(feedWith(newer())) }
        assertFalse(recovered.failed)
    }

    @Test fun notNowHidesThatVersionUntilANewerOneIsPublished() = runBlocking {
        UpdateChecker.check(context, now = base) { FeedAnswer(feedWith(newer())) }
        assertNull(UpdateChecker.dismiss(context).offer)
        assertNull("it stays hidden on the next check", UpdateChecker.check(context, now = base + 2 * interval) { FeedAnswer(feedWith(newer())) }.offer)

        val current = installed()
        val evenNewer = "v${current.major}.${current.minor + 2}.0"
        val shown = UpdateChecker.check(context, now = base + 4 * interval) { FeedAnswer(feedWith(newer(), evenNewer)) }
        assertEquals(evenNewer.removePrefix("v"), shown.offer.toString())
    }

    @Test fun theDownloadPageIsBuiltFromTheVersionNotFromTheFeed() {
        assertEquals("https://github.com/Gargeya-Grey/Odicto-Mobile/releases/tag/v1.2.0", UpdateChecker.releasePageUrl(ReleaseVersion(1, 2, 0)))
    }
}