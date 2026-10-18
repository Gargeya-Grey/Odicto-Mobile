package app.odicto.mobile.theme

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
class OdictoPaletteTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun color(night: Boolean, id: Int): Int = OdictoTheme.themed(base, night).getColor(id)

    /** A translucent colour is judged as it looks over [under]. */
    private fun over(fg: Int, under: Int): Int {
        val a = Color.alpha(fg) / 255f
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).toInt()
        return Color.rgb(mix(Color.red(fg), Color.red(under)), mix(Color.green(fg), Color.green(under)), mix(Color.blue(fg), Color.blue(under)))
    }

    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun check(night: Boolean, name: String, ink: Int, surface: Int, minimum: Double) {
        val shown = over(color(night, ink), color(night, R.color.odicto_keyboard_background))
        val ratio = contrast(shown, over(color(night, surface), color(night, R.color.odicto_keyboard_background)))
        assertTrue("${if (night) "dark" else "light"} $name is %.2f:1, needs $minimum".format(ratio), ratio >= minimum)
    }

    @Test fun eachThemeAnswersWithItsOwnColoursWhateverThePhoneSays() {
        assertEquals(0xFF17191C.toInt(), color(true, R.color.odicto_keyboard_background))
        assertEquals(0xFFE6EAE5.toInt(), color(false, R.color.odicto_keyboard_background))
        assertEquals(0xFF8EDCBB.toInt(), color(true, R.color.odicto_accent))
        assertEquals(0xFF1F6B54.toInt(), color(false, R.color.odicto_accent))
        assertEquals(0xFFFFFFFF.toInt(), color(false, R.color.odicto_key_normal))
        assertEquals(0xFF303438.toInt(), color(true, R.color.odicto_key_normal))
    }

    @Config(qualifiers = "night")
    @Test fun aNightPhoneCanStillShowTheLightTheme() {
        assertEquals(0xFFE6EAE5.toInt(), color(false, R.color.odicto_keyboard_background))
        assertEquals(0xFF17191C.toInt(), color(true, R.color.odicto_keyboard_background))
    }

    @Test fun theJadeActionFillIsTheSameInBothThemes() {
        assertEquals(color(true, R.color.odicto_key_accent), color(false, R.color.odicto_key_accent))
    }

    @Test fun textStaysReadableInBothThemes() {
        for (night in listOf(true, false)) {
            check(night, "key label on a key", R.color.odicto_key_label, R.color.odicto_key_normal, 7.0)
            check(night, "key label on a function key", R.color.odicto_key_label, R.color.odicto_key_function, 4.5)
            check(night, "key label on a pressed key", R.color.odicto_key_label, R.color.odicto_key_pressed, 4.5)
            check(night, "text on the keyboard", R.color.odicto_text_strong, R.color.odicto_keyboard_background, 7.0)
            check(night, "muted text on the keyboard", R.color.odicto_text_muted, R.color.odicto_keyboard_background, 4.5)
            check(night, "accent text on the keyboard", R.color.odicto_accent, R.color.odicto_keyboard_background, 4.5)
            check(night, "accent text on a card", R.color.odicto_accent, R.color.odicto_panel_card, 4.5)
            check(night, "text on a card", R.color.odicto_text_strong, R.color.odicto_panel_card, 7.0)
            check(night, "muted text on a card", R.color.odicto_text_muted, R.color.odicto_panel_card, 4.5)
            check(night, "ink on the jade action fill", R.color.odicto_on_accent, R.color.odicto_key_accent, 4.5)
            check(night, "ink on the error disc", R.color.odicto_on_danger, R.color.odicto_danger, 4.5)
            check(night, "ink on the done disc", R.color.odicto_on_success, R.color.odicto_success, 4.5)
            check(night, "selected punctuation ink", R.color.odicto_strip_selected_ink, R.color.odicto_strip_selected, 4.5)
            check(night, "error icon on a card", R.color.odicto_danger, R.color.odicto_panel_card, 3.0)
            check(night, "toolbar icon on the keyboard", R.color.odicto_toolbar_icon, R.color.odicto_keyboard_background, 4.5)
            check(night, "label on the paste chip", R.color.odicto_text_muted, R.color.odicto_paste_fill, 4.5)
            check(night, "accent on the paste chip", R.color.odicto_accent, R.color.odicto_paste_fill, 4.5)
        }
    }

    @Test fun keysStandOutFromTheKeyboardSurface() {
        for (night in listOf(true, false)) {
            val surface = color(night, R.color.odicto_keyboard_background)
            for (key in listOf(R.color.odicto_key_normal, R.color.odicto_key_function)) {
                val ratio = contrast(color(night, key), surface)
                assertTrue("${if (night) "dark" else "light"} key vs surface is %.2f:1".format(ratio), ratio >= 1.1)
            }
        }
    }
}