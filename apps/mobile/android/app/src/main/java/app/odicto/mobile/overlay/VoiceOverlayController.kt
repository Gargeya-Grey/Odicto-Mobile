package app.odicto.mobile.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import app.odicto.mobile.BuildCapability
import androidx.core.content.ContextCompat
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starts the floating microphone only after persisted settings are known to be usable. */
object VoiceOverlayController {
    fun sync(context: Context, scope: CoroutineScope) {
        if (!BuildCapability.overlaySupported) return
        val app = context.applicationContext
        VoicePreferences.initialize(app)
        scope.launch {
            VoicePreferences.ready.first { it }
            withContext(Dispatchers.Main.immediate) {
                val intent = Intent(app, VoiceOverlayService::class.java)
                if (VoiceOverlayPolicy.shouldStart(VoicePreferences.state.value, Settings.canDrawOverlays(app))) {
                    try {
                        ContextCompat.startForegroundService(app, intent.setAction(VoiceOverlayService.ACTION_SHOW))
                    } catch (error: Exception) {
                        Log.e("OdictoOverlay", "Could not start floating microphone service", error)
                    }
                } else {
                    app.stopService(intent)
                }
            }
        }
    }
}
