package app.odicto.mobile

/**
 * Which optional capabilities this build actually ships.
 *
 * There is a single build and it ships everything: the floating microphone, the Quick Settings
 * pause tile, and the narrow accessibility service that inserts text while a non-Odicto keyboard
 * is active.
 *
 * Callers must still ask here instead of assuming a service exists, so a future restricted build
 * only has to flip these flags in one place.
 */
object BuildCapability {
    /** Whether this build can draw the floating microphone over other apps. */
    val overlaySupported: Boolean get() = true

    /** Whether this build can insert text while a non-Odicto keyboard is active. */
    val accessibilitySupported: Boolean get() = true
}
