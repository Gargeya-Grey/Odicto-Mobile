package app.odicto.mobile.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun clean() {
        context.getSharedPreferences("odicto-appearance", Context.MODE_PRIVATE).edit().clear().commit()
        AppearanceStore.reset()
    }

    @Test fun followsThePhoneByDefault() {
        AppearanceStore.initialize(context)
        assertEquals(Appearance.SYSTEM, AppearanceStore.current)
    }

    @Test fun aChoiceIsKeptAcrossARestart() {
        AppearanceStore.set(context, Appearance.DARK)
        assertEquals(Appearance.DARK, AppearanceStore.state.value)
        AppearanceStore.reset()
        AppearanceStore.initialize(context)
        assertEquals("read back from the saved file", Appearance.DARK, AppearanceStore.current)
        AppearanceStore.set(context, Appearance.LIGHT)
        AppearanceStore.reset()
        AppearanceStore.initialize(context)
        assertEquals(Appearance.LIGHT, AppearanceStore.current)
    }

    @Test fun aDamagedValueFallsBackToSystem() {
        context.getSharedPreferences("odicto-appearance", Context.MODE_PRIVATE).edit().putString("appearance", "sepia").commit()
        AppearanceStore.initialize(context)
        assertEquals(Appearance.SYSTEM, AppearanceStore.current)
    }
}