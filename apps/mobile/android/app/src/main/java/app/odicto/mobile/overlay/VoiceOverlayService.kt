package app.odicto.mobile.overlay

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import app.odicto.mobile.MainActivity
import app.odicto.mobile.R
import app.odicto.mobile.dictation.*
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.theme.AppearanceStore
import app.odicto.mobile.theme.OdictoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

class VoiceOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var control: VoiceControlView? = null
    /** The microphone alone, in a window that never changes size: see [VoiceControlView.detachMic]. It sits above [control]'s window. */
    private var micHost: FrameLayout? = null
    private var micParams: WindowManager.LayoutParams? = null
    private var params: WindowManager.LayoutParams? = null
    private var preferredBottom: Int? = null
    private var anchorMicLeft = 0
    private var anchorXShare = OverlayGeometry.ANCHOR_SCALE
    private var anchorYShare = 0
    /** True once a drag has actually moved the window up or down; a sideways drag leaves the vertical place alone. */
    private var movedVertically = false
    private var lastGeometry = ""
    private var lastScaleKey = ""
    /** Whether the view on screen was built for the night palette; a different answer now means a fresh view. */
    private var viewNight: Boolean? = null
    private var dragOriginSet = false
    private var dragStartMicLeft = 0
    private var dragStartBottom = 0
    /** Screen bounds held for one drag: each move would otherwise ask the window manager twice. Cleared when it ends or the screen changes. */
    private var dragBounds: Pair<Int, Int>? = null
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
        lastScaleKey = "${resources.configuration.fontScale}/${resources.configuration.densityDpi}"
        OverlayPresentation.start(OverlayPresentation.recommendedTimeout(this))
        VoicePreferences.initialize(this)
        AppearanceStore.initialize(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        registered = true
        scope.launch { combine(VoicePreferences.state, DictationCoordinator.protectedField, DictationCoordinator.state, DictationCoordinator.keyboardVisible, DictationCoordinator.keyboardTopPx) { _, _, _, _, _ -> Unit }.collect { refresh() } }
        // Audio-level ticks only update an existing view; they do not recheck window permissions.
        scope.launch { VoiceSession.state.collect { control?.render(it) } }
        // One switch for the whole app: the bubble follows the saved light/dark choice, or the phone when it says system.
        scope.launch { AppearanceStore.state.collect { restyle() } }
    }

    /** The text and colours are chosen when the view is built, so a new palette needs a fresh view in the same place. */
    private fun restyle() {
        if (control == null || OdictoTheme.isNight(this) == viewNight) return
        hide()
        refresh()
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
    // Both surfaces control the same VoiceSession; IME visibility never disables the bubble.
    private fun refresh() {
        if (VoicePreferences.ready.value && unusable()) { stop(); return }
        if (!VoicePreferences.state.value.enabled || screenOff || !Settings.canDrawOverlays(this)) { hide(); return }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hide(); recover(); return }
        // If even the mic cannot fit, the visible IME microphone remains the fallback.
        if (bounds().second < (72 * resources.displayMetrics.density).toInt()) { hide(); return }
        if (control == null && DictationCoordinator.activeEditorSession == null) refreshTarget()
        if (control == null) show()
        control?.setOverlayAvailableHeight(bounds().second)
        control?.render(VoiceSession.state.value)
        val view = control ?: return
        val layout = params ?: return
        // A rotation, a display-size change or an unfold: the saved shares are turned into pixels again.
        if (geometryKey() != lastGeometry) { lastGeometry = geometryKey(); placeFromAnchors(view, layout, update = true) }
        val previousY = layout.y
        preferredBottom?.let { layout.y = it - layout.height }
        clamp(layout)
        if (layout.y != previousY) update(view, layout)
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
        val night = OdictoTheme.isNight(this)
        viewNight = night
        val view = VoiceControlView(OdictoTheme.themed(this, night, R.style.OdictoImeTheme))
        view.setOverlayAvailableHeight(bounds().second)
        loadAnchors(view)
        val layout = WindowManager.LayoutParams(view.requiredWidth, view.requiredHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            // Android slides a window that changes place and size in one update. Opening toward the left or above does both, and
            // the slide would start the drawer away from the microphone instead of under it.
            if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
        }
        // The microphone leaves the drawer's view for a window of its own, added second so it is drawn above the drawer.
        val mic = view.detachMic()
        val micSize = view.dp(OverlayGeometry.MIC_DP)
        val host = FrameLayout(this).also { it.addView(mic, FrameLayout.LayoutParams(micSize, micSize)) }
        val hostParams = WindowManager.LayoutParams(micSize, micSize,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        if (Build.VERSION.SDK_INT >= 29) host.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // Keep the system's Back gesture off the microphone so it can sit at the very edge.
            v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
        }
        micHost = host; micParams = hostParams
        control = view; params = layout
        lastGeometry = geometryKey()
        placeFromAnchors(view, layout, update = false)
        // The microphone never moves for anything else: whenever the view changes size it says where the
        // microphone sits in its new window, and the window is placed so that spot lands on the anchor.
        view.resize = { width, height, micOffset ->
            layout.width = width
            layout.height = height
            layout.y = (preferredBottom ?: (layout.y + layout.height)) - height
            layout.x = anchorMicLeft - micOffset
            clamp(layout); update(view, layout)
        }
        view.dragStart = {
            dragOriginSet = false
            movedVertically = false
            dragBounds = null; dragBounds = bounds()
        }
        view.drag = { totalX, totalY ->
            // Place the microphone from the finger's total travel, not from accumulated steps: it stays exactly
            // the grabbed distance from the finger, and a clamp at an edge cannot make it drift out of step.
            // The drag starts from the anchor, where the microphone's own window is. It is not read back from the drawer window:
            // folding the open drawer changes the microphone's offset at once but shrinks that window a few frames later.
            if (!dragOriginSet) {
                dragOriginSet = true
                dragStartMicLeft = anchorMicLeft
                dragStartBottom = layout.y + layout.height
            }
            val micLeft = clampMic(view, dragStartMicLeft + totalX.roundToInt())
            layout.x = micLeft - view.micOffsetPx
            // A purely sideways drag leaves the vertical place alone: while the keyboard is up the window may be
            // pushed above where the user put it, and that temporary position must not be saved as theirs.
            val dy = totalY.roundToInt()
            if (dy != 0 || movedVertically) {
                layout.y = dragStartBottom + dy - layout.height
                movedVertically = true
            }
            clamp(layout)
            if (movedVertically) preferredBottom = layout.y + layout.height
            anchorMicLeft = micLeft
            update(view, layout)
        }
        view.dragEnd = {
            dragBounds = null
            saveAnchors(view)
            view.setPlacement(anchorMicLeft, bounds().first)
        }
        try {
            manager.addView(view, layout)
            placeMic(view, layout)
            manager.addView(host, hostParams)
            view.render(VoiceSession.state.value)
        }
        catch (_: Exception) { hide(); recover() }
    }

    /**
     * The room the microphone's left edge can move in: anywhere on screen, right up to either side. The
     * microphone asks the system to leave swipes that start on it alone (see [VoiceControlView.onLayout]), so
     * the Back-gesture strips do not need to be avoided.
     */
    private fun micRange(view: VoiceControlView): IntRange = 0..maxOf(0, bounds().first - view.dp(MIC_DP))
    private fun clampMic(view: VoiceControlView, micLeft: Int): Int = micLeft.coerceIn(micRange(view))
    /** Vertical room of the collapsed window's top edge, ignoring the keyboard so it does not shift the share. */
    private fun topRange(view: VoiceControlView): IntRange = 0..maxOf(0, bounds(withKeyboard = false).second - view.dp(COLLAPSED_DP))
    private fun share(value: Int, range: IntRange): Int = OverlayGeometry.shareOf(value, range.first, range.last)
    private fun valueAt(share: Int, range: IntRange): Int = OverlayGeometry.valueAt(share, range.first, range.last)

    /** Starts from the saved share, or an older pixel position, or the right edge a little above the bottom. */
    private fun loadAnchors(view: VoiceControlView) {
        val saved = VoicePreferences.state.value
        val xs = micRange(view); val ys = topRange(view)
        anchorXShare = when {
            saved.anchorX >= 0 -> saved.anchorX
            saved.x >= 0 -> share(saved.x + view.dp(MIC_INSET_DP), xs)
            else -> OverlayGeometry.ANCHOR_SCALE
        }
        anchorYShare = when {
            saved.anchorY >= 0 -> saved.anchorY
            saved.y >= 0 -> share(saved.y, ys)
            else -> share(ys.last - view.dp(100), ys)
        }
    }
    private fun saveAnchors(view: VoiceControlView) {
        val xs = micRange(view); val ys = topRange(view)
        anchorXShare = share(anchorMicLeft, xs)
        val pixelX = anchorMicLeft - view.dp(MIC_INSET_DP)
        // Only a drag that really moved vertically changes the vertical place. Otherwise it is kept exactly,
        // not re-derived from a rounded share, and not clamped by whatever the keyboard happens to be doing.
        if (movedVertically) anchorYShare = share((preferredBottom ?: 0) - view.dp(COLLAPSED_DP), ys)
        val pixelY = (preferredBottom ?: 0) - view.dp(COLLAPSED_DP)
        val moved = movedVertically
        VoicePreferences.update(this) { it.copy(x = pixelX, y = if (moved) pixelY else it.y, anchorX = anchorXShare, anchorY = anchorYShare) }
        movedVertically = false
    }

    /** Turns the saved shares into pixels for the screen as it is now. */
    private fun placeFromAnchors(view: VoiceControlView, layout: WindowManager.LayoutParams, update: Boolean) {
        anchorMicLeft = valueAt(anchorXShare, micRange(view))
        preferredBottom = valueAt(anchorYShare, topRange(view)) + view.dp(COLLAPSED_DP)
        view.setPlacement(anchorMicLeft, bounds().first)
        layout.x = anchorMicLeft - view.micOffsetPx
        layout.y = preferredBottom!! - layout.height
        clamp(layout)
        if (update) update(view, layout)
    }
    private fun geometryKey(): String {
        val (w, h) = bounds(withKeyboard = false)
        return "$w/$h/${resources.displayMetrics.density}"
    }

    private fun bounds(withKeyboard: Boolean = true): Pair<Int, Int> {
        if (withKeyboard) dragBounds?.let { return it }
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val height = metrics.bounds.height() - inset.top - inset.bottom
            return (metrics.bounds.width() - inset.left - inset.right) to (if (withKeyboard) visibleHeight(height, inset.top) else height)
        }
        val height = resources.displayMetrics.heightPixels
        return resources.displayMetrics.widthPixels to (if (withKeyboard) visibleHeight(height, 0) else height)
    }
    private fun visibleHeight(screenHeight: Int, topInset: Int): Int {
        val keyboardTop = DictationCoordinator.keyboardTopPx.value
        if (!DictationCoordinator.keyboardVisible.value || keyboardTop == null) return screenHeight
        val gap = (8 * resources.displayMetrics.density).toInt()
        return minOf(screenHeight, keyboardTop - topInset - gap).coerceAtLeast(0)
    }
    private fun clamp(layout: WindowManager.LayoutParams) {
        val (w, h) = bounds()
        // The window's empty margin may hang past the side of the screen so the microphone itself can touch it.
        val overhang = (OverlayGeometry.INSET_DP * resources.displayMetrics.density).roundToInt()
        layout.x = layout.x.coerceIn(-overhang, maxOf(-overhang, w - layout.width + overhang)); layout.y = layout.y.coerceIn(0, maxOf(0, h - layout.height))
    }    private fun update(view: View, layout: WindowManager.LayoutParams) {
        if (view !== control) return
        try { manager.updateViewLayout(view, layout) } catch (_: Exception) { hide(); recover(); return }
        (view as? VoiceControlView)?.let { placeMic(it, layout) }
    }

    /**
     * The microphone's own window sits exactly where the microphone is: its left edge is the anchor, and its bottom is 8dp above the
     * drawer window's bottom (the drawer window always grows upward from there). Nothing about the drawer's size is involved, so the
     * microphone cannot move or flash when the drawer opens or closes.
     */
    private fun placeMic(view: VoiceControlView, layout: WindowManager.LayoutParams) {
        val host = micHost ?: return
        val p = micParams ?: return
        val x = anchorMicLeft
        val y = layout.y + layout.height - view.dp(OverlayGeometry.MIC_DP + OverlayGeometry.INSET_DP)
        if (p.x == x && p.y == y) return
        p.x = x; p.y = y
        if (host.isAttachedToWindow) try { manager.updateViewLayout(host, p) } catch (_: Exception) { hide(); recover() }
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
        control = null; params = null; preferredBottom = null; dragBounds = null
        val host = micHost; micHost = null; micParams = null
        view.resize = null; view.dragStart = null; view.drag = null; view.dragEnd = null
        if (host != null) try { manager.removeViewImmediate(host) } catch (_: IllegalArgumentException) {}
        // addView registers the root before isAttachedToWindow becomes true.
        // Remove that root even when focus/permissions change in the same frame.
        try { manager.removeViewImmediate(view) } catch (_: IllegalArgumentException) {}
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dragBounds = null
        // Text is sized when the view is built, so a font-size or display-size change needs a fresh view. The saved
        // placement puts it back exactly where it was.
        val scaleKey = "${newConfig.fontScale}/${newConfig.densityDpi}"
        if (scaleKey != lastScaleKey) { lastScaleKey = scaleKey; hide() }
        restyle()
        refresh()
        params?.let { clamp(it); control?.let { view -> update(view, it) } }
    }
    override fun onDestroy() { if (registered) unregisterReceiver(receiver); hide(); scope.cancel(); super.onDestroy() }
    companion object { const val ACTION_SHOW = "app.odicto.SHOW_OVERLAY"; const val ACTION_HIDE = "app.odicto.HIDE_OVERLAY"; private const val CHANNEL = "odicto-overlay"; private const val NOTIFICATION_ID = 18; private const val TARGET_REFRESH_MS = 500L
        private const val MIC_DP = 56; private const val MIC_INSET_DP = 8; private const val COLLAPSED_DP = 72 }
}
