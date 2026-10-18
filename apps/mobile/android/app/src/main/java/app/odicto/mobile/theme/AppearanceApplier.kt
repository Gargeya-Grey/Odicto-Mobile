package app.odicto.mobile.theme

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import app.odicto.mobile.R

/** Brings the app screen's own chrome (system bars, window background) in line with the saved appearance. */
object AppearanceApplier {
    private fun nightMode(appearance: Appearance) = when (appearance) {
        Appearance.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    /** Before the activity's own onCreate, so the very first frame already uses the right day/night resources. */
    @JvmStatic fun beforeCreate(activity: Activity) {
        AppearanceStore.initialize(activity)
        AppCompatDelegate.setDefaultNightMode(nightMode(AppearanceStore.current))
    }

    /** Call on the main thread: after create, after the choice changes, and when the phone's setting changes. */
    @JvmStatic fun apply(activity: Activity, webView: View?) {
        val appearance = AppearanceStore.current
        AppCompatDelegate.setDefaultNightMode(nightMode(appearance))
        val night = OdictoTheme.isNight(activity, appearance)
        val canvas = OdictoTheme.themed(activity, night).getColor(R.color.odicto_canvas)
        val window = activity.window
        @Suppress("DEPRECATION")
        window.statusBarColor = canvas
        @Suppress("DEPRECATION")
        window.navigationBarColor = canvas
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        // The page's own background paints almost at once, but the first frame and any over-scroll show this.
        webView?.setBackgroundColor(canvas)
    }
}