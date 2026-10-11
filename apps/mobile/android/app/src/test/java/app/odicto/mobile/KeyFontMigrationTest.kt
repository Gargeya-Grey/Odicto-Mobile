package app.odicto.mobile

import app.odicto.mobile.storage.KeySettings
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyFontMigrationTest {
    @Test fun onlyARecordedSystemChoiceStaysOffTheBrandFace() {
        assertEquals("system", KeySettings.resolveFont("system", choiceRecorded = true))
        for ((stored, recorded) in listOf(
            "system" to false, "sansflex" to true, "sansflex" to false, "lora" to true, "lora" to false,
            "instrument" to true, "instrument" to false, null to false, null to true, "unknown" to true,
        )) {
            assertEquals("$stored/$recorded", "instrument", KeySettings.resolveFont(stored, recorded))
        }
    }

    @Test fun theSettingAcceptsOnlyTheTwoCurrentFaces() {
        assertEquals(listOf("system", "instrument"), KeySettings.FONTS)
    }
}
