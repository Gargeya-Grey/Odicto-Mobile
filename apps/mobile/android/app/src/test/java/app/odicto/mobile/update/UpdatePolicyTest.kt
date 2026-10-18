package app.odicto.mobile.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val interval = UpdatePolicy.CHECK_INTERVAL_MS
    private val retry = UpdatePolicy.RETRY_INTERVAL_MS
    private val now = 10 * interval

    @Test fun theIntervalIsAnHourAndTheRetryFifteenMinutes() {
        assertEquals(60L * 60 * 1000, UpdatePolicy.CHECK_INTERVAL_MS)
        assertEquals(15L * 60 * 1000, UpdatePolicy.RETRY_INTERVAL_MS)
    }

    @Test fun neverAsksWhileTurnedOff() {
        assertFalse(UpdatePolicy.due(false, now, 0L, 0L))
    }

    @Test fun asksOnTheFirstOpen() {
        assertTrue(UpdatePolicy.due(true, now, 0L, 0L))
    }

    @Test fun asksAtMostOnceAnIntervalAfterASuccess() {
        assertFalse(UpdatePolicy.due(true, now, now - interval + 1, now - interval + 1))
        assertTrue(UpdatePolicy.due(true, now, now - interval, now - interval))
        assertTrue(UpdatePolicy.due(true, now, now - 3 * interval, now - 3 * interval))
    }

    @Test fun waitsAShortRetryAfterAFailureNotAFullInterval() {
        val checked = now - 3 * interval
        assertFalse(UpdatePolicy.due(true, now, checked, now - retry + 1))
        assertTrue(UpdatePolicy.due(true, now, checked, now - retry))
        assertTrue("a first-ever failure still retries after an retry", UpdatePolicy.due(true, now, 0L, now - retry))
        assertFalse(UpdatePolicy.due(true, now, 0L, now - 1))
    }

    @Test fun aClockThatMovedBackwardsDoesNotBlockChecking() {
        assertTrue(UpdatePolicy.due(true, now, now + 5 * interval, now + 5 * interval))
    }

    private val installed = ReleaseVersion(1, 1, 1)

    @Test fun offersOnlyAVersionNewerThanInstalled() {
        assertEquals(ReleaseVersion(1, 2, 0), UpdatePolicy.offer(installed, ReleaseVersion(1, 2, 0), null))
        assertNull(UpdatePolicy.offer(installed, ReleaseVersion(1, 1, 1), null))
        assertNull(UpdatePolicy.offer(installed, ReleaseVersion(1, 0, 9), null))
    }

    @Test fun offersNothingWhenAVersionIsUnknown() {
        assertNull(UpdatePolicy.offer(null, ReleaseVersion(9, 0, 0), null))
        assertNull(UpdatePolicy.offer(installed, null, null))
    }

    @Test fun notNowHidesThatVersionOnlyUntilANewerOneArrives() {
        assertNull(UpdatePolicy.offer(installed, ReleaseVersion(1, 2, 0), ReleaseVersion(1, 2, 0)))
        assertEquals(ReleaseVersion(1, 2, 1), UpdatePolicy.offer(installed, ReleaseVersion(1, 2, 1), ReleaseVersion(1, 2, 0)))
    }
}