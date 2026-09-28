package app.odicto.mobile.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Whether this app's accessibility service is enabled, read without touching restricted secure settings. */
internal object AccessibilityState {
    fun enabled(context: Context): Boolean = try {
        context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
    } catch (_: Exception) { false }
}
