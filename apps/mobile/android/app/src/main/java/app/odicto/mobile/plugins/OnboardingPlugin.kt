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
import app.odicto.mobile.accessibility.AccessibilityState
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "OdictoOnboarding")
class OnboardingPlugin : Plugin() {
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
        // The store build ships neither service, so never query for a capability it does not have.
        val overlay = BuildCapability.overlaySupported && Settings.canDrawOverlays(context)
        val accessibility = if (BuildCapability.accessibilitySupported) AccessibilityState.enabled(context) else false
        call.resolve(JSObject()
            .put("microphone", microphone)
            .put("imeEnabled", enabled)
            .put("imeSelected", selected)
            .put("notifications", notifications)
            .put("overlay", overlay)
            .put("accessibility", accessibility)
            .put("overlaySupported", BuildCapability.overlaySupported)
            .put("accessibilitySupported", BuildCapability.accessibilitySupported))
    }
    @PluginMethod fun requestNotifications(call: PluginCall) { val host = activity ?: return call.reject("Odicto is not in the foreground"); host.runOnUiThread { if (android.os.Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(host, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42); call.resolve() } }
    @PluginMethod fun requestMicrophone(call: PluginCall) { val host = activity ?: return call.reject("Odicto is not in the foreground"); host.runOnUiThread { ActivityCompat.requestPermissions(host, arrayOf(Manifest.permission.RECORD_AUDIO), 41); call.resolve() } }
    @PluginMethod fun openKeyboardSettings(call: PluginCall) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve() }
    @PluginMethod fun showKeyboardPicker(call: PluginCall) { (context.getSystemService(InputMethodManager::class.java)).showInputMethodPicker(); call.resolve() }
    @PluginMethod fun openOverlaySettings(call: PluginCall) {
        if (!BuildCapability.overlaySupported) { call.reject("This build has no floating microphone"); return }
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve()
    }
    @PluginMethod fun openAccessibilitySettings(call: PluginCall) {
        if (!BuildCapability.accessibilitySupported) { call.reject("This build has no accessibility service"); return }
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve()
    }
}
