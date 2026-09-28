package app.odicto.mobile.accessibility

/**
 * Identity for the focused editor.
 *
 * Geometry is deliberately not part of the key. A field that moves or resizes while the user is
 * dictating (the IME appears, a list scrolls, the layout reflows) is still the same editor; treating
 * it as a new one closes the session mid-recording, so the finished transcript is saved instead of
 * inserted.
 *
 * Android 13+ reports a per-node unique id that survives re-layout. Older releases fall back to the
 * resource name or class, which is coarser but still stable while the field stays focused.
 */
internal object EditorIdentity {
    fun key(windowId: Int, uniqueId: String?, viewIdResourceName: String?, className: String?): String {
        val identity = uniqueId ?: viewIdResourceName ?: className ?: "field"
        return "$windowId:$identity"
    }
}
