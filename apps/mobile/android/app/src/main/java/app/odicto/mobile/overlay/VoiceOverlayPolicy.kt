package app.odicto.mobile.overlay

import app.odicto.mobile.storage.VoiceSettings

internal object VoiceOverlayPolicy {
    /** A paused app stays out of the way: protected apps refuse to run while an overlay is drawn. */
    fun shouldStart(settings: VoiceSettings, overlayAllowed: Boolean): Boolean = settings.enabled && !settings.paused && overlayAllowed
}
