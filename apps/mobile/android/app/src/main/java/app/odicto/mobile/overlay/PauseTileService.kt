package app.odicto.mobile.overlay

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.odicto.mobile.R
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One tap in the shade to get Odicto out of the way. Banks and other protected apps refuse to run
 * while an overlay is drawn, so pausing stops the floating control; resuming brings it back.
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
}
