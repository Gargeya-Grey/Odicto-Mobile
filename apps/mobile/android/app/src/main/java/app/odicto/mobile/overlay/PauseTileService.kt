package app.odicto.mobile.overlay

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.odicto.mobile.R
import app.odicto.mobile.accessibility.AccessibilityState
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One tap in the shade to get Odicto out of the way. Banks and other protected apps refuse to run
 * while an overlay is drawn or an accessibility service is enabled, so pausing stops the floating
 * control and disables that service. Android only lets the user turn accessibility back on, so
 * resuming opens that screen instead of pretending the tap was enough.
 */
class PauseTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        VoicePreferences.initialize(this)
        scope.launch {
            VoicePreferences.ready.first { it }
            render()
        }
    }

    override fun onClick() {
        super.onClick()
        VoicePreferences.initialize(this)
        scope.launch {
            VoicePreferences.ready.first { it }
            val pausing = !VoicePreferences.state.value.paused
            VoicePreferences.update(this@PauseTileService) { it.copy(paused = pausing) }
            if (pausing) {
                stopService(Intent(this@PauseTileService, VoiceOverlayService::class.java))
            } else {
                VoiceOverlayController.sync(this@PauseTileService, scope)
                if (!AccessibilityState.enabled(this@PauseTileService)) openAccessibilitySettings()
            }
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val paused = VoicePreferences.state.value.paused
        qsTile?.apply {
            state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = getString(if (paused) R.string.pause_tile_paused else R.string.pause_tile_active)
            icon = Icon.createWithResource(this@PauseTileService, R.drawable.ic_launcher_foreground)
            updateTile()
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
