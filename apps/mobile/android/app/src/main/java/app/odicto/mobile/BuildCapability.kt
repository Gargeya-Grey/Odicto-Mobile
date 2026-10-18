package app.odicto.mobile

/**
 * Which optional capabilities this build actually ships.
 *
 * There is a single build and it ships the floating microphone and the Quick Settings pause tile. It ships no
 * accessibility service: Google Play Protect blocks an APK installed from a browser or file manager when it declares
 * that permission, and Odicto's own keyboard is the only insertion channel.
 *
 * Callers must still ask here instead of assuming a service exists, so a future restricted build
 * only has to flip these flags in one place.
 */
object BuildCapability {
    /** Whether this build can draw the floating microphone over other apps. */
    val overlaySupported: Boolean get() = true
}
