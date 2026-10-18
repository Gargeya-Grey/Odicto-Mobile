package app.odicto.mobile.theme

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun readsTheThreeNames() {
        assertEquals(Appearance.SYSTEM, Appearance.parseOrNull("system"))
        assertEquals(Appearance.LIGHT, Appearance.parseOrNull("light"))
        assertEquals(Appearance.DARK, Appearance.parseOrNull("dark"))
    }

    @Test fun refusesAnythingElseButFallsBackToSystem() {
        for (bad in listOf(null, "", "DARK", "Light", "sepia", " dark", "auto")) {
            assertNull("'$bad' is not an appearance", Appearance.parseOrNull(bad))
            assertEquals(Appearance.SYSTEM, Appearance.parse(bad))
        }
    }

    @Test fun nightFollowsThePhoneOnlyWhenTheChoiceIsSystem() {
        assertTrue(OdictoTheme.isNight(Appearance.SYSTEM, systemNight = true))
        assertFalse(OdictoTheme.isNight(Appearance.SYSTEM, systemNight = false))
        for (phone in listOf(true, false)) {
            assertTrue("dark stays dark on a ${if (phone) "dark" else "light"} phone", OdictoTheme.isNight(Appearance.DARK, phone))
            assertFalse("light stays light on a ${if (phone) "dark" else "light"} phone", OdictoTheme.isNight(Appearance.LIGHT, phone))
        }
    }
}