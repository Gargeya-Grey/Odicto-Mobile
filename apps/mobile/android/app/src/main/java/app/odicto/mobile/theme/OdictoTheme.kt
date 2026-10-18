package app.odicto.mobile.theme

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper

/**
 * Day or night for Odicto's own surfaces. The keyboard and the floating microphone are services, so Android's
 * day/night switching does not reach them, and the user's in-app choice can differ from the phone's. Each
 * builds its views from [themed]: a context whose resources resolve the day or the night colour file by the
 * answer here, whatever the phone says.
 */
object OdictoTheme {
    fun systemNight(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isNight(appearance: Appearance, systemNight: Boolean): Boolean = when (appearance) {
        Appearance.DARK -> true
        Appearance.LIGHT -> false
        Appearance.SYSTEM -> systemNight
    }

    /**
     * Whether Odicto should look dark now. The phone's own setting is read from the application context, which a
     * [themed] override on some other context can never change.
     */
    fun isNight(context: Context, appearance: Appearance = AppearanceStore.current): Boolean =
        isNight(appearance, systemNight(context.applicationContext.resources.configuration))

    /**
     * A context that resolves night or day resources by [night]. Only the night bit is overridden: orientation,
     * font size, density and language keep following the phone. Pass the style the views should be inflated with.
     */
    fun themed(base: Context, night: Boolean, themeRes: Int = 0): Context {
        val override = Configuration().apply {
            // fontScale 0 marks "not overridden", as AppCompat does for its own night override.
            fontScale = 0f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(unwrap(base), themeRes).also { it.applyOverrideConfiguration(override) }
    }

    /** A wrapper over a wrapper would carry the first override along; start from the real base. */
    private fun unwrap(context: Context): Context {
        var current = context
        while (current is ContextThemeWrapper) current = current.baseContext
        return current
    }
}