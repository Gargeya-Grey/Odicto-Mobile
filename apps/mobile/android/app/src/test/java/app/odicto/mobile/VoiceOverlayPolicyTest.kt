package app.odicto.mobile

import app.odicto.mobile.overlay.VoiceOverlayPolicy
import app.odicto.mobile.storage.VoiceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOverlayPolicyTest {
    @Test fun foregroundServiceStartsOnlyForEnabledOverlayWithPermission() {
        assertFalse(VoiceOverlayPolicy.shouldStart(VoiceSettings(enabled = false), overlayAllowed = false))
        assertFalse(VoiceOverlayPolicy.shouldStart(VoiceSettings(enabled = true), overlayAllowed = false))
        assertTrue(VoiceOverlayPolicy.shouldStart(VoiceSettings(enabled = true), overlayAllowed = true))
    }

    @Test fun aPausedAppKeepsTheOverlayOffTheScreen() {
        assertFalse(VoiceOverlayPolicy.shouldStart(VoiceSettings(enabled = true, paused = true), overlayAllowed = true))
        assertTrue(VoiceOverlayPolicy.shouldStart(VoiceSettings(enabled = true, paused = false), overlayAllowed = true))
    }
}
