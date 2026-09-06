package app.odicto.mobile.plugins

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        call.resolve(JSObject().put("microphone", microphone).put("imeEnabled", enabled).put("imeSelected", selected).put("notifications", notifications).put("overlay", Settings.canDrawOverlays(context)))
    }
    @PluginMethod fun requestNotifications(call: PluginCall) { activity.runOnUiThread { if (android.os.Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42); call.resolve() } }
    @PluginMethod fun requestMicrophone(call: PluginCall) { activity.runOnUiThread { ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 41); call.resolve() } }
    @PluginMethod fun openKeyboardSettings(call: PluginCall) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve() }
    @PluginMethod fun showKeyboardPicker(call: PluginCall) { (context.getSystemService(InputMethodManager::class.java)).showInputMethodPicker(); call.resolve() }
    @PluginMethod fun openOverlaySettings(call: PluginCall) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve() }
}
