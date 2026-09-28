package app.odicto.mobile.overlay

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import app.odicto.mobile.MainActivity
import app.odicto.mobile.R
import app.odicto.mobile.dictation.*
import app.odicto.mobile.storage.VoicePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class VoiceOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var control: VoiceControlView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenOff = false
    private var recovery: Job? = null
    private var recoveryAttempts = 0
    private var promoted = false
    private var registered = false
    private var lastTargetRefresh = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOff = intent?.action == Intent.ACTION_SCREEN_OFF
            recoveryAttempts = 0
            if (screenOff && VoiceSession.state.value.active) VoiceSession.cancel(this@VoiceOverlayService)
            refresh()
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        // Promotion belongs in onCreate: a startForegroundService() launch that misses the promotion
        // window kills the process with ForegroundServiceDidNotStartInTimeException before any of
        // onStartCommand can run, and sticky restarts then loop on the same crash.
        if (!promote()) return
        VoicePreferences.initialize(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        registered = true
        scope.launch { combine(VoicePreferences.state, DictationCoordinator.protectedField, DictationCoordinator.state, DictationCoordinator.keyboardVisible) { _, _, _, _ -> Unit }.collect { refresh() } }
        // Audio-level ticks only update an existing view; they do not recheck window permissions.
        scope.launch { VoiceSession.state.collect { control?.render(it) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recoveryAttempts = 0
        // A startForegroundService start must reach startForeground even when the bubble is unusable.
        if (!promoted) return START_NOT_STICKY
        if (VoicePreferences.ready.value && unusable()) { stop(); return START_NOT_STICKY }
        if (intent?.action == ACTION_HIDE) hide() else refresh()
        return START_STICKY
    }
    /**
     * Foreground status keeps OEM freezers (Samsung suspended the process whenever another app was
     * in front) from leaving the bubble on screen but dead to touches.
     */
    private fun promote(): Boolean {
        if (promoted) return true
        if (Build.VERSION.SDK_INT >= 26) runCatching { getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Voice typing overlay", NotificationManager.IMPORTANCE_MIN)) }
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Odicto voice typing")
                .setContentText("Floating microphone active over text fields")
                .setContentIntent(open)
                .setOngoing(true)
                .build())
            promoted = true
            true
        } catch (_: Exception) { stop(); false }
    }
    // Durable conditions stop the service outright; a disabled or paused bubble must not hold a foreground slot.
    private fun unusable(): Boolean = !VoicePreferences.state.value.enabled || VoicePreferences.state.value.paused || !Settings.canDrawOverlays(this)
    private fun stop() {
        hide()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }
    // The bubble stays on screen whenever the user enabled it and the permission is granted.
    // Only the Odicto keyboard hides it, because that keyboard already shows the microphone.
    private fun refresh() {
        if (VoicePreferences.ready.value && unusable()) { stop(); return }
        if (!VoicePreferences.state.value.enabled || DictationCoordinator.keyboardVisible.value || screenOff || !Settings.canDrawOverlays(this)) { hide(); return }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hide(); recover(); return }
        if (control == null && DictationCoordinator.activeEditorSession == null) refreshTarget()
        if (control == null) show()
        control?.render(VoiceSession.state.value)
    }
    /**
     * Resolving the target reads the focused node across processes. Do it when the bubble is about to
     * appear, not on every settings or coordinator emission, and never more than once per
     * [TARGET_REFRESH_MS]. The touch surface re-resolves on demand for a session that was missed.
     */
    private fun refreshTarget() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTargetRefresh < TARGET_REFRESH_MS) return
        lastTargetRefresh = now
        DictationCoordinator.refreshTarget()
    }
    private fun show() {
        val view = VoiceControlView(this)
        val size = view.requiredWidth
        val bounds = bounds()
        val settings = VoicePreferences.state.value
        val layout = WindowManager.LayoutParams(size, view.requiredHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (settings.x >= 0) settings.x else bounds.first - size - view.dp(12)
            y = if (settings.y >= 0) settings.y else bounds.second - size - view.dp(100)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        control = view; params = layout
        view.resize = { width, height ->
            val micAnchorX = if (view.mirrored) layout.x + view.dp(8) else layout.x + layout.width - view.dp(64)
            layout.y -= height - layout.height
            layout.width = width
            layout.height = height
            layout.x = if (view.mirrored) micAnchorX - view.dp(8) else micAnchorX - (layout.width - view.dp(64))
            clamp(layout); update(view, layout)
        }
        view.drag = { dx, dy -> layout.x += dx.toInt(); layout.y += dy.toInt(); clamp(layout); update(view, layout) }
        view.dragEnd = {
            VoicePreferences.update(this) { it.copy(x = layout.x, y = layout.y) }
            view.mirrored = layout.x < bounds().first / 2
        }
        clamp(layout)
        try { manager.addView(view, layout); view.mirrored = layout.x < bounds.first / 2; view.render(VoiceSession.state.value) }
        catch (_: Exception) { hide(); recover() }
    }
    private fun bounds(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return (metrics.bounds.width() - inset.left - inset.right) to (metrics.bounds.height() - inset.top - inset.bottom)
        }
        return resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }
    private fun clamp(layout: WindowManager.LayoutParams) {
        val (w, h) = bounds(); layout.x = layout.x.coerceIn(0, maxOf(0, w - layout.width)); layout.y = layout.y.coerceIn(0, maxOf(0, h - layout.height))
    }
    private fun update(view: View, layout: WindowManager.LayoutParams) {
        if (view !== control) return
        try { manager.updateViewLayout(view, layout) } catch (_: Exception) { hide(); recover() }
    }
    // A failed window update or an unlock broadcast arriving before Keyguard clears
    // must not leave an enabled bubble absent until an unrelated state change.
    private fun recover() {
        if (recovery?.isActive == true || recoveryAttempts >= 3) return
        recoveryAttempts++
        recovery = scope.launch {
            delay(500)
            recovery = null
            refresh()
        }
    }
    private fun hide() {
        val view = control ?: return
        control = null; params = null
        view.resize = null; view.drag = null; view.dragEnd = null
        // addView registers the root before isAttachedToWindow becomes true.
        // Remove that root even when focus/permissions change in the same frame.
        try { manager.removeViewImmediate(view) } catch (_: IllegalArgumentException) {}
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); refresh(); params?.let { clamp(it); control?.let { view -> update(view, it) } } }
    override fun onDestroy() { if (registered) unregisterReceiver(receiver); hide(); scope.cancel(); super.onDestroy() }
    companion object { const val ACTION_SHOW = "app.odicto.SHOW_OVERLAY"; const val ACTION_HIDE = "app.odicto.HIDE_OVERLAY"; private const val CHANNEL = "odicto-overlay"; private const val NOTIFICATION_ID = 18; private const val TARGET_REFRESH_MS = 500L }
}
