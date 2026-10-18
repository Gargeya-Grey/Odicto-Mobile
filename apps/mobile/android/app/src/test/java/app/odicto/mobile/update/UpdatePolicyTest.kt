package app.odicto.mobile.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val day = UpdatePolicy.CHECK_INTERVAL_MS
    private val hour = UpdatePolicy.RETRY_INTERVAL_MS
    private val now = 10 * day

    @Test fun neverAsksWhileTurnedOff() {
        assertFalse(UpdatePolicy.due(false, now, 0L, 0L))
    }

    @Test fun asksOnTheFirstOpen() {
        assertTrue(UpdatePolicy.due(true, now, 0L, 0L))
    }

    @Test fun asksAtMostOnceADayAfterASuccess() {
        assertFalse(UpdatePolicy.due(true, now, now - day + 1, now - day + 1))
        assertTrue(UpdatePolicy.due(true, now, now - day, now - day))
        assertTrue(UpdatePolicy.due(true, now, now - 3 * day, now - 3 * day))
    }

    @Test fun waitsAnHourAfterAFailureButNotADay() {
        val checked = now - 3 * day
        assertFalse(UpdatePolicy.due(true, now, checked, now - hour + 1))
        assertTrue(UpdatePolicy.due(true, now, checked, now - hour))
        assertTrue("a first-ever failure still retries after an hour", UpdatePolicy.due(true, now, 0L, now - hour))
        assertFalse(UpdatePolicy.due(true, now, 0L, now - 1))
    }

    @Test fun aClockThatMovedBackwardsDoesNotBlockChecking() {
        assertTrue(UpdatePolicy.due(true, now, now + 5 * day, now + 5 * day))
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