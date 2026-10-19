package app.odicto.mobile.plugins

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.odicto.mobile.BuildCapability
import app.odicto.mobile.storage.ClipboardRepository
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

@CapacitorPlugin(name = "OdictoOnboarding")
class OnboardingPlugin : Plugin() {
    /**
     * The keyboard picker is a system dialog over the app, so the page never leaves the screen
     * and has no moment to re-check. Android reports the change here instead; the page re-reads
     * status on "statusChanged". The event carries no data.
     */
    private val keyboardWatcher = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { notifyListeners("statusChanged", JSObject()) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun load() {
        super.load()
        for (key in listOf(Settings.Secure.DEFAULT_INPUT_METHOD, Settings.Secure.ENABLED_INPUT_METHODS)) {
            runCatching { context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, keyboardWatcher) }
        }
        // The keyboard panel has its own switch for the same setting; the page re-reads when either one changes.
        scope.launch {
            try {
                ClipboardRepository.get(context).data.map { it.automaticCapture }.distinctUntilChanged().drop(1)
                    .collect { notifyListeners("statusChanged", JSObject()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        runCatching { context.contentResolver.unregisterContentObserver(keyboardWatcher) }
        super.handleOnDestroy()
    }

    /** Null when the saved setting cannot be read, so the page can say so instead of claiming on or off. */
    private fun clipboardSaving(): Boolean? = try {
        runBlocking { ClipboardRepository.get(context).data.first().automaticCapture }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    @PluginMethod fun setClipboardSaving(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: return call.reject("enabled is required")
        scope.launch {
            try {
                ClipboardRepository.get(context).setAutomaticCapture(enabled)
                call.resolve()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { call.reject("Could not save this choice") }
        }
    }

    @PluginMethod fun status(call: PluginCall) {
        // ENABLED_INPUT_METHODS is restricted to system/legacy-target apps on modern Android.
        // Use InputMethodManager's public list so onboarding cannot crash the process.
        val enabled = try {
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.any { it.packageName == context.packageName } == true
        } catch (_: SecurityException) { false }
        val selected = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                .orEmpty().startsWith(context.packageName + "/")
        } catch (_: SecurityException) { false }
        val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // Ask BuildCapability rather than probing for a service the build may not ship.
        val overlay = BuildCapability.overlaySupported && Settings.canDrawOverlays(context)
        call.resolve(JSObject()
            .put("microphone", microphone)
            .put("imeEnabled", enabled)
            .put("imeSelected", selected)
            .put("notifications", notifications)
            .put("overlay", overlay)
            .put("overlaySupported", BuildCapability.overlaySupported)
            .put("clipboardSaving", clipboardSaving() ?: JSONObject.NULL))
    }
    @PluginMethod fun requestNotifications(call: PluginCall) { val host = activity ?: return call.reject("Odicto is not in the foreground"); host.runOnUiThread { if (android.os.Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(host, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42); call.resolve() } }
    @PluginMethod fun requestMicrophone(call: PluginCall) { val host = activity ?: return call.reject("Odicto is not in the foreground"); host.runOnUiThread { ActivityCompat.requestPermissions(host, arrayOf(Manifest.permission.RECORD_AUDIO), 41); call.resolve() } }
    @PluginMethod fun openKeyboardSettings(call: PluginCall) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve() }
    @PluginMethod fun showKeyboardPicker(call: PluginCall) { (context.getSystemService(InputMethodManager::class.java)).showInputMethodPicker(); call.resolve() }
    @PluginMethod fun openOverlaySettings(call: PluginCall) {
        if (!BuildCapability.overlaySupported) { call.reject("This build has no floating microphone"); return }
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve()
    }
}
