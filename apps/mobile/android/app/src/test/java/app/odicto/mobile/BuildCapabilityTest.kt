package app.odicto.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

/** The single build ships the floating microphone, and nothing that Google Play Protect blocks on sideload. */
class BuildCapabilityTest {
    @Test fun theBuildShipsTheOverlay() {
        assertTrue("overlay capability must be shipped", BuildCapability.overlaySupported)
    }
}