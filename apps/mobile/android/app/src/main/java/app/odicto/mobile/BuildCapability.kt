package app.odicto.mobile

/**
 * Which optional capabilities this build actually ships.
 *
 * The floating microphone and accessibility insertion ship only in the legacy flavor. The store
 * build uses the Odicto keyboard microphone and does not register cross-app overlay capabilities.
 *
 * Callers must ask here instead of assuming a service exists, so a missing service is never a
 * silent no-op.
 */
object BuildCapability {
    /** Whether this build can draw the floating microphone over other apps. */
    val overlaySupported: Boolean get() = BuildConfig.LEGACY_EXTRAS

    /** Whether this build can insert text while a non-Odicto keyboard is active. */
    val accessibilitySupported: Boolean get() = BuildConfig.LEGACY_EXTRAS
}
