package app.odicto.mobile

import app.odicto.mobile.BuildCapability
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single build ships both optional capabilities and must claim both. */
class BuildCapabilityTest {
    @Test fun theBuildShipsOverlayAndAccessibility() {
        assertTrue("overlay capability must be shipped", BuildCapability.overlaySupported)
        assertTrue("accessibility capability must be shipped", BuildCapability.accessibilitySupported)
    }
}
