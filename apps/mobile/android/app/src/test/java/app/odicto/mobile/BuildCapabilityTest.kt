package app.odicto.mobile

import app.odicto.mobile.BuildCapability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two flavors must disagree about exactly these two capabilities, and nothing may claim a
 * capability the build does not ship. The test is flavor-aware because it runs in both source sets.
 */
class BuildCapabilityTest {
    @Test fun capabilitiesMatchTheFlavorBeingBuilt() {
        val legacy = BuildConfig.LEGACY_EXTRAS
        assertEquals("overlay capability must match the flavor", legacy, BuildCapability.overlaySupported)
        assertEquals("accessibility capability must match the flavor", legacy, BuildCapability.accessibilitySupported)
    }

    @Test fun theStoreBuildOmitsOverlayAndAccessibility() {
        if (BuildConfig.LEGACY_EXTRAS) return
        assertEquals(false, BuildCapability.overlaySupported)
        assertEquals(false, BuildCapability.accessibilitySupported)
    }
}
