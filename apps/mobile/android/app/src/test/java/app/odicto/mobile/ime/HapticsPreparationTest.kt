package app.odicto.mobile.ime

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HapticsPreparationTest.CountingVibrator::class])
class HapticsPreparationTest {
    @Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
    class CountingVibrator {
        @Implementation fun hasVibrator(): Boolean {
            capabilityCalls++
            if (rejectCapability) throw SecurityException()
            return true
        }
        @Implementation fun vibrate(uid: Int, packageName: String?, effect: VibrationEffect, attributes: AudioAttributes?) {
            if (rejectRequest) throw IllegalStateException()
            requests += effect
        }
        companion object {
            var capabilityCalls = 0
            var rejectCapability = false
            var rejectRequest = false
            val requests = mutableListOf<VibrationEffect>()
        }
    }

    @Before fun clear() {
        CountingVibrator.capabilityCalls = 0
        CountingVibrator.rejectCapability = false
        CountingVibrator.rejectRequest = false
        CountingVibrator.requests.clear()
    }

    @Test fun unavailableHardwareCannotAbortKeyboardCreationOrTyping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CountingVibrator.rejectCapability = true
        Haptics.prepare(context)
        repeat(3) { assertEquals(0L, Haptics.tapMeasured(context, Haptics.MEDIUM)) }
        assertEquals(1, CountingVibrator.capabilityCalls)
    }

    @Test fun vibrationFailureCannotEscapeIntoEditorDispatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Haptics.prepare(context)
        CountingVibrator.rejectRequest = true
        repeat(3) { assertEquals(0L, Haptics.tapMeasured(context, Haptics.MEDIUM)) }
        assertTrue(CountingVibrator.requests.isEmpty())
    }

    @Test fun warmingCachesCapabilityAndReusesEffectsAcrossSynchronousRequests() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Haptics.prepare(context)
        val warmedCalls = CountingVibrator.capabilityCalls
        assertEquals(1, warmedCalls)
        repeat(3) { Haptics.tap(context, Haptics.MEDIUM) }
        assertEquals(warmedCalls, CountingVibrator.capabilityCalls)
        assertEquals(3, CountingVibrator.requests.size)
        assertSame(CountingVibrator.requests[0], CountingVibrator.requests[1])
        assertSame(CountingVibrator.requests[1], CountingVibrator.requests[2])
        Haptics.tap(context, Haptics.STRONG)
        assertNotSame(CountingVibrator.requests[0], CountingVibrator.requests[3])
    }

    @Test fun offDoesNotRequestVibrationAndEmphasisReusesItsOwnEffect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Haptics.prepare(context)
        Haptics.tap(context, Haptics.OFF)
        assertTrue(CountingVibrator.requests.isEmpty())
        Haptics.tap(context, Haptics.LIGHT, Haptics.EMPHASIS_LONG)
        Haptics.tap(context, Haptics.LIGHT, Haptics.EMPHASIS_LONG)
        assertEquals(2, CountingVibrator.requests.size)
        assertSame(CountingVibrator.requests[0], CountingVibrator.requests[1])
    }
}
