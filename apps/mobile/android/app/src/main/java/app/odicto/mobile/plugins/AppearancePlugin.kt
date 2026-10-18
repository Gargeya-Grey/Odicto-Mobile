package app.odicto.mobile.plugins

import app.odicto.mobile.theme.Appearance
import app.odicto.mobile.theme.AppearanceApplier
import app.odicto.mobile.theme.AppearanceStore
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The one light/dark switch. The app screens read and write it here; the keyboard and the floating
 * microphone watch [AppearanceStore] and rebuild themselves when it changes.
 */
@CapacitorPlugin(name = "OdictoAppearance")
class AppearancePlugin : Plugin() {
    private fun answer() = JSObject().put("appearance", AppearanceStore.current.wire)

    @PluginMethod fun get(call: PluginCall) {
        AppearanceStore.initialize(context)
        call.resolve(answer())
    }

    @PluginMethod fun set(call: PluginCall) {
        val requested = Appearance.parseOrNull(call.getString("appearance")) ?: return call.reject("Unknown appearance")
        AppearanceStore.set(context, requested)
        val host = activity
        if (host == null) {
            call.resolve(answer())
            return
        }
        host.runOnUiThread {
            AppearanceApplier.apply(host, bridge?.webView)
            call.resolve(answer())
        }
    }
}