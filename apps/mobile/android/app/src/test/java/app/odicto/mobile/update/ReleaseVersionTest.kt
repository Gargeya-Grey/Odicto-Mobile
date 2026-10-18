package app.odicto.mobile.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun readsPlainAndTaggedVersions() {
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("1.2.3"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("v1.2.3"))
        assertEquals(ReleaseVersion(10, 0, 21), ReleaseVersion.parse("  v10.0.21 "))
        assertEquals("1.2.3", ReleaseVersion.parse("v1.2.3").toString())
    }

    @Test fun refusesAnythingThatIsNotAPlainRelease() {
        for (bad in listOf(null, "", "v", "1", "1.2", "1.2.3.4", "1.2.3-beta", "1.2.3+5", "a.b.c", "1.2.x", "-1.2.3", "99999.0.0", "v 1.2.3")) {
            assertNull("'$bad' is not a release", ReleaseVersion.parse(bad))
        }
    }

    @Test fun comparesNumbersNotText() {
        assertTrue(ReleaseVersion(1, 10, 0) > ReleaseVersion(1, 9, 9))
        assertTrue(ReleaseVersion(2, 0, 0) > ReleaseVersion(1, 99, 99))
        assertTrue(ReleaseVersion(1, 1, 10) > ReleaseVersion(1, 1, 9))
        assertEquals(0, ReleaseVersion(1, 2, 3).compareTo(ReleaseVersion(1, 2, 3)))
    }
}