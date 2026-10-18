package app.odicto.mobile.plugins

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import app.odicto.mobile.update.UpdateChecker
import app.odicto.mobile.update.UpdateSnapshot
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.PluginMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** "A new version is available": see [UpdateChecker] for what is asked, when, and what is never done. */
@CapacitorPlugin(name = "OdictoUpdate")
class UpdatePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun JSObject.fill(snapshot: UpdateSnapshot): JSObject = put("enabled", snapshot.enabled)
        .put("current", snapshot.current)
        .put("available", snapshot.offer != null)
        .put("version", snapshot.offer?.toString())
        .put("checkedAt", snapshot.checkedAt)
        .put("failed", snapshot.failed)

    private fun reply(call: PluginCall, message: String, work: suspend () -> UpdateSnapshot) {
        scope.launch {
            try { call.resolve(JSObject().fill(work())) } catch (_: Exception) { call.reject(message) }
        }
    }

    /** What is already known, without any network request, so the card can show at once. */
    @PluginMethod fun status(call: PluginCall) = reply(call, "Could not read update status") { UpdateChecker.snapshot(context) }

    /** Asks for the release list if a check is due (hourly), or at once when "force" is set; otherwise the same answer as [status]. */
    @PluginMethod fun check(call: PluginCall) {
        val force = call.getBoolean("force") ?: false
        reply(call, "Could not check for updates") { UpdateChecker.check(context, force = force) }
    }

    @PluginMethod fun setEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: return call.reject("Missing setting")
        reply(call, "Could not save the update setting") { UpdateChecker.setEnabled(context, enabled) }
    }

    @PluginMethod fun dismiss(call: PluginCall) = reply(call, "Could not hide the update") { UpdateChecker.dismiss(context) }

    /** Opens the release page for the offered version in the browser. Android does the install, never this app. */
    @PluginMethod fun openDownload(call: PluginCall) {
        scope.launch {
            try {
                val offer = UpdateChecker.snapshot(context).offer ?: return@launch call.reject("No update is available")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.releasePageUrl(offer))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                call.resolve()
            } catch (_: ActivityNotFoundException) {
                call.reject("No browser is available to open the download page")
            } catch (_: Exception) {
                call.reject("Could not open the download page")
            }
        }
    }

    override fun handleOnDestroy() { scope.cancel(); super.handleOnDestroy() }
}
