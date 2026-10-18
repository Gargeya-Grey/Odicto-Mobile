package app.odicto.mobile.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The saved light/dark choice, shared by the app screens, the keyboard and the floating microphone so one
 * switch changes all three. It lives in a tiny SharedPreferences file rather than the DataStore because the
 * launch screen needs it synchronously, before any coroutine has run.
 */
object AppearanceStore {
    private const val FILE = "odicto-appearance"
    private const val KEY = "appearance"

    private val mutable = MutableStateFlow(Appearance.SYSTEM)
    val state: StateFlow<Appearance> = mutable.asStateFlow()
    val current: Appearance get() = mutable.value

    @Volatile private var loaded = false

    @JvmStatic @Synchronized fun initialize(context: Context) {
        if (loaded) return
        loaded = true
        mutable.value = Appearance.parse(prefs(context).getString(KEY, null))
    }

    @JvmStatic @Synchronized fun set(context: Context, value: Appearance) {
        initialize(context)
        prefs(context).edit().putString(KEY, value.wire).apply()
        mutable.value = value
    }

    /** Test seam: forget what was loaded so the next [initialize] reads the file again. */
    internal fun reset() {
        loaded = false
        mutable.value = Appearance.SYSTEM
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}