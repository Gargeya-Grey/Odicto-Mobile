package app.odicto.mobile.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import app.odicto.mobile.BuildConfig
import app.odicto.mobile.R
import app.odicto.mobile.dictation.*
import app.odicto.mobile.overlay.*
import app.odicto.mobile.storage.SecureCredentialStore
import app.odicto.mobile.storage.VoicePreferences
import app.odicto.mobile.theme.AppearanceStore
import app.odicto.mobile.theme.OdictoTheme
import kotlinx.coroutines.*

class OdictoImeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var control: VoiceControlView? = null
    private var controller: KeyboardController? = null
    private var expansion: KeyboardExpansion? = null
    private var inputRoot: View? = null
    private var session: Long? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    private var selectionGeneration = 0L
    private var liveInsertion: LiveInsertion? = null
    private var liveEditor: Long? = null
    private var liveConnection: android.view.inputmethod.InputConnection? = null
    private var clipboardCapture: ClipboardCapture? = null
    private var recoveryRow: LinearLayout? = null
    private var recoveryCovered = emptyMap<View, Int>()
    private var recoveryCopy: Button? = null
    private var recoveryExplanation: TextView? = null
    private var polish: FieldPolishCoordinator? = null
    private val polishClient = FieldPolishClient()
    private var polishButton: ImageButton? = null
    private var undoButton: ImageButton? = null
    private var editorUndo: EditorUndoCoordinator? = null
    private var undoEditorEpoch = 0L
    private var undoEditorActive = false
    /** The just-copied text offered for one-tap paste. Held here so it outlives any one field or app. */
    internal val pasteSuggestion = PasteSuggestion()
    private var polishBusyDrawable: PolishBusyDrawable? = null
    private var polishStatus: TextView? = null
    private val statusPresentation = KeyboardStatusPresentation()
    private var statusExpiry: Runnable? = null
    private var statusViewActive = false
    private var polishBusy = false
    private var compactTop = false
    private val statusHandler = Handler(Looper.getMainLooper())
    private var ownEditorTarget: android.view.inputmethod.InputConnection? = null
    private var ownEditorConnection: OwnEditorConnection? = null

    /**
     * Every editor read goes through here. Odicto's own fields share this main thread,
     * so they get a connection that never blocks on a read (see [OwnEditorConnection]).
     * One wrapper per connection keeps the identity checks that detect editor changes.
     */
    override fun getCurrentInputConnection(): android.view.inputmethod.InputConnection? {
        val connection = super.getCurrentInputConnection() ?: return null
        if (!EditorPolicy.ownEditor(currentInputEditorInfo)) return connection
        if (ownEditorTarget !== connection) {
            ownEditorTarget = connection
            ownEditorConnection = OwnEditorConnection(connection)
        }
        return ownEditorConnection
    }

    private val target = object : DictationTarget {
        override val id = DictationTarget.IME
        override val identity: Any get() = currentInputConnection ?: this
        override val selectionVersion: Long get() = selectionGeneration
        override fun readSelection() = this@OdictoImeService.readSelection()
        override fun insert(text: String): Boolean { val current = session ?: return false; return insertIfCurrent(current, text) }
        override fun selectAll() = this@OdictoImeService.selectAll()
        override fun beginLive() = this@OdictoImeService.beginLive()
        override fun writeLive(text: String, finish: Boolean) = this@OdictoImeService.writeLive(text, finish)
        override fun endLive() = this@OdictoImeService.endLive()
    }
    /** Whether the keyboard is built for the night palette, and the context that builds it. See [OdictoTheme]. */
    private var uiNight: Boolean? = null
    private var uiContext: Context? = null

    /** The context every keyboard view is built from: day or night colours, whatever the phone's own setting is. */
    private fun ui(): Context = uiContext ?: rebuildUiContext().let { uiContext!! }

    /** Returns true when the palette changed, so the keyboard must be rebuilt. */
    private fun rebuildUiContext(): Boolean {
        val night = OdictoTheme.isNight(this)
        if (night == uiNight && uiContext != null) return false
        val changed = uiNight != null
        uiNight = night
        uiContext = OdictoTheme.themed(this, night, R.style.OdictoImeTheme)
        return changed
    }

    /**
     * The light/dark choice (or the phone's setting, when the choice is "system") changed while the keyboard
     * exists: rebuild it from the new palette and repeat what showing it does. The expanded state and any open
     * panel start over; that is the cost of a theme switch, and it only happens when the user flips it.
     */
    private fun applyAppearance() {
        if (!rebuildUiContext() || inputRoot == null) return
        val visible = DictationCoordinator.keyboardVisible.value
        setInputView(onCreateInputView())
        if (!visible) return
        paintNavigationStrip()
        control?.render(VoiceSession.state.value)
        renderTopStatus()
        controller?.onExternalEdit(selectionStart, selectionEnd)
        renderRecovery()
        inputRoot?.post { publishKeyboardBounds() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The phone's day/night setting may have flipped, which matters while the choice is "system".
        applyAppearance()
    }

    override fun onCreate() {
        // Before super: InputMethodService creates its window from this theme.
        setTheme(R.style.OdictoImeTheme)
        super.onCreate()
        AppearanceStore.initialize(this)
        VoicePreferences.initialize(this); DictationCoordinator.registerTarget(target)
        scope.launch { AppearanceStore.state.collect { applyAppearance() } }
        clipboardCapture = ClipboardCapture(this, scope).also { capture ->
            capture.onFreshClip = { text, token, copiedAt ->
                if (pasteSuggestion.offer(text, token, copiedAt)) controller?.refreshPaste()
            }
            scope.launch { capture.error.collect { message ->
                if (message != null) controller?.showClipboardNotice(message)
            } }
        }
        scope.launch { fieldPolish().recovery.collect { renderRecovery() } }
        scope.launch { editorUndo().busy.collect { renderUndoButton() } }
        scope.launch {
            var previous: VoiceUi? = null
            VoiceSession.state.collect { state ->
                // Ten meter ticks a second while recording change only the level: redraw the control, skip the rest.
                val meterOnly = previous?.let(state::sameExceptLevel) == true
                previous = state
                if (state.busy) {
                    cancelFieldPolish()
                    cancelEditorUndo()
                    // Speaking is the user's chosen action: the copy is no longer offered.
                    pasteSuggestion.spend()
                }
                if (!meterOnly) {
                    controller?.refreshSpelling()
                    observeVoiceStatus()
                    renderUndoButton()
                }
                if (DictationCoordinator.keyboardVisible.value) {
                    control?.render(state)
                    if (!meterOnly) renderTopStatus()
                }
            }
        }
        scope.launch { VoicePreferences.state.collect {
            controller?.refreshAppearance()
            if (DictationCoordinator.keyboardVisible.value) control?.render(VoiceSession.state.value)
        } }
    }
    override fun onDestroy() {
        pauseStatusView()
        cancelFieldPolish()
        cancelEditorUndo()
        polish?.clearUndo()
        controller?.dispose()
        controller = null
        expansion?.dispose(); expansion = null
        recoveryRow = null; recoveryCopy = null; recoveryExplanation = null
        cancelStatusCallback()
        DictationCoordinator.keyboardVisible.value = false
        DictationCoordinator.keyboardTopPx.value = null
        inputRoot = null
        DictationCoordinator.unregisterTarget(DictationTarget.IME)
        clipboardCapture?.close()
        clipboardCapture = null
        scope.cancel()
        super.onDestroy()
    }
    /**
     * The system draws the IME's navigation strip and paints it black unless the window draws its
     * own bar backgrounds. The keyboard's own surface colour keeps the strip continuous with it.
     */
    private fun paintNavigationStrip() {
        val keyboardWindow = window?.window ?: return
        runCatching {
            keyboardWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            keyboardWindow.navigationBarColor = ui().getColor(R.color.odicto_keyboard_background)
            if (Build.VERSION.SDK_INT >= 29) keyboardWindow.isNavigationBarContrastEnforced = false
            // Dark navigation icons on the light keyboard, light icons on the dark one.
            val lightBars = if (uiNight == true) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            if (Build.VERSION.SDK_INT >= 30) keyboardWindow.insetsController?.setSystemBarsAppearance(
                lightBars, android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        paintNavigationStrip()
        statusViewActive = true
        DictationCoordinator.keyboardVisible.value = true
        inputRoot?.post { publishKeyboardBounds() }
        control?.render(VoiceSession.state.value)
        renderTopStatus()
        renderRecovery()
        renderUndoButton()
    }
    override fun onWindowHidden() {
        pauseStatusView()
        cancelFieldPolish()
        cancelEditorUndo()
        DictationCoordinator.keyboardVisible.value = false
        DictationCoordinator.keyboardTopPx.value = null
        controller?.onFinishInput()
        expansion?.reset()
        if (BuildConfig.DEBUG) KeyLatencyProbe.flushAsync()
        super.onWindowHidden()
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View {
        pauseStatusView()
        cancelFieldPolish()
        cancelEditorUndo()
        controller?.dispose()
        expansion?.dispose()
        val ui = ui()
        val root = android.view.LayoutInflater.from(ui).inflate(R.layout.ime_keyboard, null)
        inputRoot = root
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            root.post { if (inputRoot === root) publishKeyboardBounds() }
        }
        val column = root.findViewById<LinearLayout>(R.id.ime_column)
        column.layoutParams = FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER_HORIZONTAL)
        // Let the grid absorb a height shortage instead of clipping the bottom tools in landscape.
        root.findViewById<View>(R.id.ime_content).layoutParams = LinearLayout.LayoutParams(-1, -2, 1f)
        attachRecoveryControls(root)
        val row = root.findViewById<FrameLayout>(R.id.ime_top_row)
        val voice = VoiceControlView(ui, true)
        var topCell = 48
        fun voiceParams(width: Int, height: Int) = FrameLayout.LayoutParams(width, height, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            marginEnd = voice.dp(topCell)
        }
        voice.resize = { width, height, _ -> voice.layoutParams = voiceParams(width, height) }
        control = voice
        row.addView(voice, 0, voiceParams(voice.requiredWidth, voice.requiredHeight))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        polishButton = root.findViewById(R.id.ime_polish)
        val busyDrawable = PolishBusyDrawable(resources.displayMetrics.density, ui.getColor(R.color.odicto_accent)) {
            if (Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
            else android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        polishBusyDrawable = busyDrawable
        polishButton?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (polishButton === view && polishBusy) busyDrawable.start()
            }
            override fun onViewDetachedFromWindow(view: View) {
                busyDrawable.stop()
            }
        })
        undoButton = root.findViewById<ImageButton>(R.id.ime_undo).apply {
            contentDescription = "Undo last text edit"
            setOnClickListener {
                Haptics.tap(this@OdictoImeService, VoicePreferences.levelOf(VoicePreferences.state.value))
                pasteSuggestion.spend()
                if (polish?.busy == true) {
                    cancelFieldPolish()
                    showKeyboardNotice("Polish cancelled")
                    return@setOnClickListener
                }
                controller?.onExternalEdit(selectionStart, selectionEnd)
                editorUndo().undo()
            }
        }
        renderUndoButton()
        polishStatus = root.findViewById(R.id.ime_polish_status)
        polishStatus?.apply {
            isClickable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        voice.keyboardCopyAllowed = ::canCopyRecovery
        voice.keyboardNotice = ::showKeyboardNotice
        polishButton?.setOnClickListener {
            cancelEditorUndo()
            Haptics.tap(this, VoicePreferences.levelOf(VoicePreferences.state.value))
            pasteSuggestion.spend()
            val refusal = EditorPolicy.voiceRefusal(currentInputEditorInfo)
            when {
                refusal != null -> showPolishStatus(refusal.replace("Voice", "Polish"), false)
                !VoicePreferences.ready.value -> showPolishStatus("Loading polish settings. Try again.", false)
                else -> {
                    controller?.onExternalEdit(selectionStart, selectionEnd)
                    fieldPolish().start()
                }
            }
        }
        compactTop = false
        row.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val cell = ((right - left) / resources.displayMetrics.density / TOP_CELLS).toInt().coerceIn(32, 48)
            if (topCell != cell) {
                topCell = cell
                compactTop = cell < 48
                voice.setKeyboardCellWidth(cell)
                voice.layoutParams = voiceParams(voice.requiredWidth, voice.requiredHeight)
                // The icon is the cell minus its padding, so the padding has to shrink with the cell: a fixed 13dp
                // left almost nothing to draw in on a narrow screen.
                val padding = voice.dp(cell * 13 / 48)
                listOf(R.id.ime_keyboard_toggle, R.id.ime_undo, R.id.ime_polish).forEach { id ->
                    root.findViewById<View>(id).apply {
                        layoutParams = layoutParams.apply { width = voice.dp(cell) }
                        setPadding(padding, padding, padding, padding)
                    }
                }
                root.findViewById<View>(R.id.ime_undo).apply {
                    layoutParams = (layoutParams as FrameLayout.LayoutParams).apply { marginStart = voice.dp(cell) }
                }
                renderTopStatus()
            }
            if (BuildConfig.DEBUG && (left != oldLeft || right != oldRight)) row.post { logKeyboardGeometry(root) }
        }
        controller = KeyboardController(this, root)
        expansion = KeyboardExpansion(root, { controller?.cancelTouch() }) {
            // A new inset reaches the window manager on the next traversal of the IME window.
            if (inputRoot === root) { root.requestLayout(); root.post { if (inputRoot === root) publishKeyboardBounds() } }
        }
        expansion?.onPresentationChanged = {
            controller?.onKeyboardPresentationChanged(it)
        }
        controller?.attachVoiceControl(voice)
        controller?.onStartInput(currentInputEditorInfo)
        clipboardCapture?.error?.value?.let { controller?.showClipboardNotice(it) }
        renderRecovery()
        updatePolishButton(currentInputEditorInfo)
        statusViewActive = DictationCoordinator.keyboardVisible.value
        renderTopStatus()
        if (BuildConfig.DEBUG) row.post { logKeyboardGeometry(root) }
        return root
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cancelEditorUndo()
        undoEditorEpoch++
        undoEditorActive = attribute != null
        if (!restarting) expansion?.reset()
        clipboardCapture?.onEditorChanged(attribute)
        pauseStatusView()
        cancelFieldPolish()
        controller?.stopRepeating()
        liveInsertion?.stop(); liveInsertion = null; liveEditor = null; liveConnection = null
        selectionStart = attribute?.initialSelStart ?: -1; selectionEnd = attribute?.initialSelEnd ?: -1
        if (EditorPolicy.permits(attribute) && currentInputConnection != null) { session = DictationCoordinator.editorReady() }
        else { session = null; DictationCoordinator.editorClosed(true); if (VoiceSession.state.value.active) VoiceSession.cancel(this) }
        control?.visibility = View.VISIBLE
        control?.render(VoiceSession.state.value)
        controller?.onStartInput(attribute)
        updatePolishButton(attribute)
        renderUndoButton()
        renderRecovery()
        VoiceOverlayController.sync(this, scope)
    }

    private fun publishKeyboardBounds() {
        val root = inputRoot ?: return
        if (!DictationCoordinator.keyboardVisible.value || !root.isAttachedToWindow) return
        val position = IntArray(2)
        root.getLocationOnScreen(position)
        DictationCoordinator.keyboardTopPx.value = position[1] + (expansion?.contentTop() ?: 0)
    }

    /**
     * The window always holds the expanded keyboard; only the part from [KeyboardExpansion.contentTop]
     * down is keyboard. The host app keeps that part clear, and touches above it reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // A hardware keyboard hides the input view while the root keeps its last bounds.
        if (!isInputViewShown) return
        val root = inputRoot ?: return
        val expansion = expansion ?: return
        val decor = window?.window?.decorView ?: return
        if (!root.isAttachedToWindow || root.height == 0 || decor.height == 0) return
        val position = IntArray(2)
        root.getLocationInWindow(position)
        val top = (position[1] + expansion.contentTop()).coerceIn(0, decor.height)
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, decor.width, decor.height)
    }
    override fun onFinishInput() {
        undoEditorActive = false
        undoEditorEpoch++
        cancelEditorUndo()
        clipboardCapture?.onEditorChanged(null)
        pauseStatusView()
        cancelFieldPolish()
        polish?.clearUndo()
        controller?.stopRepeating()
        controller?.onFinishInput()
        endLive()
        session = null; DictationCoordinator.editorClosed(); control?.visibility = View.VISIBLE
        if (VoiceSession.state.value.active) VoiceSession.finish(this)
        renderRecovery()
        super.onFinishInput()
    }
    override fun onUnbindInput() {
        undoEditorActive = false
        undoEditorEpoch++
        cancelEditorUndo()
        clipboardCapture?.onEditorChanged(null)
        pauseStatusView()
        cancelFieldPolish()
        polish?.clearUndo()
        controller?.onFinishInput()
        session = null
        renderRecovery()
        super.onUnbindInput()
    }

    fun isPolishBusy(): Boolean = polish?.busy == true

    private fun attachRecoveryControls(root: View) {
        recoveryCovered = emptyMap()
        val ui = root.context
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val row = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = PressSurface.tile(ui, 16f, ui.getColor(R.color.odicto_panel_card))
            isClickable = true
            visibility = View.GONE
        }
        val title = TextView(ui).apply {
            text = "Polish result ready"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ui.getColor(R.color.odicto_text_strong))
        }
        val explanation = TextView(ui).apply {
            textSize = 13f
            setTextColor(ui.getColor(R.color.odicto_text_muted))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val actions = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        val touchHeight = dp(48)
        val copy = Button(ui).apply {
            text = "Copy"
            contentDescription = "Copy completed result"
            isAllCaps = false
            textSize = 14f
            minimumWidth = 0
            minWidth = 0
            minimumHeight = touchHeight
            setPadding(dp(12), 0, dp(12), 0)
            backgroundTintList = null
            background = PressSurface.tile(ui, 12f, ui.getColor(R.color.odicto_key_accent))
            setTextColor(ui.getColor(R.color.odicto_on_accent))
            setOnClickListener {
                val id = row.tag as? Long ?: return@setOnClickListener
                val copied = polish?.copyCompletedResult(id) { text ->
                    // Check at the explicit tap, not just when enabling the control.
                    if (!canCopyRecovery() || Build.VERSION.SDK_INT < 24) false
                    else try {
                        val clip = ClipData.newPlainText("Completed polish result", text)
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        true
                    } catch (_: Exception) { false }
                } == true
                if (copied) showKeyboardNotice("Copied")
                else if (polish?.completedResult()?.operationId == id) {
                    val message = "Could not copy. Focus an eligible text field and try again."
                    showKeyboardNotice(message)
                    explanation.text = message
                }
                if (copied || polish?.completedResult()?.operationId != id) renderRecovery()
            }
        }
        val dismiss = Button(ui).apply {
            text = "Dismiss"
            contentDescription = "Dismiss completed result"
            isAllCaps = false
            textSize = 14f
            minimumWidth = 0
            minWidth = 0
            minimumHeight = touchHeight
            setPadding(dp(12), 0, dp(12), 0)
            backgroundTintList = null
            background = PressSurface.tile(ui, 12f, ui.getColor(R.color.odicto_surface_raised))
            setTextColor(ui.getColor(R.color.odicto_text_strong))
            setOnClickListener {
                (row.tag as? Long)?.let { polish?.dismissCompletedResult(it) }
                renderRecovery()
            }
        }
        actions.addView(copy, LinearLayout.LayoutParams(0, touchHeight, 1f))
        actions.addView(dismiss, LinearLayout.LayoutParams(0, touchHeight, 1f).apply { marginStart = dp(8) })
        row.addView(title, LinearLayout.LayoutParams(-1, -2))
        row.addView(explanation, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        recoveryRow = row; recoveryCopy = copy; recoveryExplanation = explanation
        root.findViewById<FrameLayout>(R.id.ime_content).addView(row, FrameLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (recoveryRow === row && row.visibility == View.VISIBLE) {
                expansion?.recoveryVisible(true, row.height + dp(16))
            }
        }
    }

    private fun canCopyRecovery(): Boolean = session != null && currentInputConnection != null &&
        DictationCoordinator.keyboardVisible.value && EditorPolicy.clipboardPermits(currentInputEditorInfo)

    private fun renderRecovery() {
        val result = polish?.completedResult()
        recoveryRow?.tag = result?.operationId
        val parent = recoveryRow?.parent as? ViewGroup
        if (result != null && parent != null) {
            if (recoveryCovered.isEmpty()) recoveryCovered = (0 until parent.childCount)
                .map { parent.getChildAt(it) }.filter { it !== recoveryRow }.associateWith { it.visibility }
            // INVISIBLE preserves measurement while hiding covered typing/panel targets.
            recoveryCovered.keys.forEach { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
        } else {
            recoveryCovered.forEach { (view, visibility) -> view.visibility = visibility }
            recoveryCovered = emptyMap()
        }
        recoveryRow?.visibility = if (result == null) View.GONE else View.VISIBLE
        val margins = (recoveryRow?.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0
        expansion?.recoveryVisible(result != null, (recoveryRow?.height ?: 0) + margins)
        recoveryCopy?.isEnabled = result != null && canCopyRecovery()
        recoveryExplanation?.text = result?.explanation.orEmpty()
    }

    fun cancelFieldPolish() {
        polishBusyDrawable?.stop()
        if (polish?.busy == true) polish?.cancel()
        else if (polishBusy) showPolishStatus("", false)
    }

    fun cancelEditorUndo() { editorUndo?.cancel() }

    private fun undoTarget(): UndoEditor? {
        if (!undoEditorActive) return null
        val connection = currentInputConnection ?: return null
        return UndoEditor(undoEditorEpoch, connection, currentInputEditorInfo)
    }

    private fun editorUndo(): EditorUndoCoordinator = editorUndo ?: EditorUndoCoordinator(
        scope = scope,
        editor = ::undoTarget,
        allowed = { DictationCoordinator.keyboardVisible.value && !VoiceSession.state.value.busy && DictationCoordinator.aiSelection == null },
        state = { message, busy ->
            observeVoiceStatus()
            if (message.isNotEmpty() || !VoiceSession.state.value.busy) {
                statusPresentation.notice(message, busy, SystemClock.uptimeMillis(), statusTimeout())
            }
            controller?.refreshSpelling()
            renderTopStatus()
        },
        dispatched = { polish?.clearUndo() },
        applied = { start, end -> controller?.onExternalEdit(start, end) },
    ).also { editorUndo = it }

    private fun fieldPolish(): FieldPolishCoordinator {
        polish?.let { return it }
        return FieldPolishCoordinator(
            scope = scope,
            editor = {
                val id = session
                val connection = currentInputConnection
                if (id != null && connection != null) PolishEditor(id, connection, currentInputEditorInfo) else null
            },
            allowed = { DictationCoordinator.keyboardVisible.value && !VoiceSession.state.value.busy && DictationCoordinator.aiSelection == null },
            readKey = { provider -> SecureCredentialStore(this).get("voice-$provider") },
            config = { VoicePreferences.state.value.let { PolishConfig(it.polishProvider, it.polishModel, it.polishSystemPrompt, app.odicto.mobile.dictation.PromptMode.forStored(it.polishSystemPromptMode, it.polishSystemPrompt), it.polishSettingsReadable) } },
            correct = { text, config, key ->
                polishClient.correct(text, config.model, config.prompt, key, config.promptMode, config.provider)
            },
            state = ::showPolishStatus,
            applied = { start, end -> controller?.onExternalEdit(start, end) },
            diagnostic = { flags -> if (BuildConfig.DEBUG) Log.i("OdictoPolishDelivery", flags) },
        ).also { polish = it }
    }

    /** The host owns history and exposes no universal canUndo query. An eligible field can request it. */
    private fun renderUndoButton() {
        val available = EditorUndoCoordinator.permits(undoTarget()?.info) && DictationCoordinator.keyboardVisible.value &&
            !VoiceSession.state.value.busy && DictationCoordinator.aiSelection == null && editorUndo?.busy?.value != true
        undoButton?.let { PressSurface.fadeTo(it, if (available) 1f else 0.35f) }
    }

    private fun updatePolishButton(info: EditorInfo?) {
        polishButton?.alpha = if (session != null && EditorPolicy.permits(info)) 1f else 0.35f
        polishButton?.contentDescription = if (polishBusy) "Cancel text polish" else if (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd) "Polish selected text" else "Polish all text in this field"
    }

    private fun showPolishStatus(message: String, busy: Boolean) {
        observeVoiceStatus()
        if (message.isNotEmpty() || !VoiceSession.state.value.busy) {
            statusPresentation.notice(message, busy, SystemClock.uptimeMillis(), statusTimeout())
        }
        polishBusy = busy
        controller?.refreshSpelling()
        if (BuildConfig.DEBUG && message.isNotEmpty()) {
            val reason = when {
                message.startsWith("Polishing") -> "pending"
                message.startsWith("Nothing to polish") -> "empty_field"
                message.startsWith("This field does not expose") -> "incomplete_field"
                message.startsWith("Polish is off") -> "protected_field"
                message.startsWith("Polish needs") -> "no_editor"
                message.startsWith("Add an OpenRouter key") -> "missing_key"
                message.startsWith("Field changed") -> "stale_field"
                message.startsWith("Text polished") -> "applied"
                message.startsWith("Polish undone") -> "undone"
                message.startsWith("Nothing to undo") -> "nothing_to_undo"
                else -> "error"
            }
            Log.i("OdictoPolishState", "reason=$reason busy=$busy")
        }
        updatePolishButton(currentInputEditorInfo)
        polishButton?.let { button ->
            if (busy) {
                val drawable = polishBusyDrawable
                if (button.drawable !== drawable) button.setImageDrawable(drawable)
                if (button.isAttachedToWindow) drawable?.start()
            } else {
                polishBusyDrawable?.stop()
                button.setImageResource(R.drawable.odicto_ic_polish)
            }
        }
        cancelStatusCallback()
        renderTopStatus()
    }

    private fun statusTimeout(): Long {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return if (Build.VERSION.SDK_INT >= 29) manager.getRecommendedTimeoutMillis(3_000,
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong() else 3_000L
    }

    private fun observeVoiceStatus() {
        statusPresentation.voice(VoiceSession.state.value, VoiceSession.operationId, SystemClock.uptimeMillis(), statusTimeout())
    }

    internal fun showKeyboardNotice(message: String) {
        observeVoiceStatus()
        statusPresentation.notice(message, false, SystemClock.uptimeMillis(), statusTimeout())
        renderTopStatus()
    }

    private fun cancelStatusCallback() {
        statusExpiry?.let { statusHandler.removeCallbacks(it) }
        statusExpiry = null
    }

    private fun pauseStatusView() { statusViewActive = false; cancelStatusCallback() }

    private fun renderTopStatus() {
        observeVoiceStatus()
        val feedback = statusPresentation.current(SystemClock.uptimeMillis())
        controller?.setSpellingStatusVisible(feedback != null)
        polishStatus?.apply {
            val summary = feedback?.summary.orEmpty()
            if (text.toString() != summary) {
                text = summary
                // transitions.dev text swap: a new line rises into place instead of replacing the old one in a blink.
                if (summary.isNotEmpty()) PressSurface.riseIn(this)
            }
            contentDescription = feedback?.description
            visibility = if (feedback == null) View.INVISIBLE else View.VISIBLE
            // Hidden mid-rise must not leave it half-faded for the next message.
            if (feedback == null) PressSurface.rest(this)
        }
        cancelStatusCallback()
        if (statusViewActive && feedback?.deadline != null) {
            val callback = Runnable {
                statusPresentation.expire(feedback.generation, SystemClock.uptimeMillis())
                renderTopStatus()
            }
            statusExpiry = callback
            statusHandler.postAtTime(callback, feedback.deadline)
        }
    }

    internal fun logKeyboardGeometry(root: View) {
        val row = root.findViewById<FrameLayout>(R.id.ime_top_row)
        val keyboard = root.findViewById<OdictoKeyboardView>(R.id.ime_keyboard)
        val targets = setOf("q", "w", "e", "Space", "Backspace")
        val bounds = mutableListOf<String>()
        val rect = Rect()
        if (control?.getGlobalVisibleRect(rect) == true) bounds += "Voice=${rect.toShortString()}"
        if (polishButton?.getGlobalVisibleRect(rect) == true) bounds += "Polish=${rect.toShortString()}"
        for (index in 0 until keyboard.childCount) {
            val line = keyboard.getChildAt(index) as? ViewGroup ?: continue
            for (key in 0 until line.childCount) {
                val cap = line.getChildAt(key)
                val name = cap.contentDescription?.toString() ?: continue
                if (name in targets && cap.getGlobalVisibleRect(rect)) bounds += "$name=${rect.toShortString()}"
            }
        }
        Log.i("OdictoKeyboardGeometry", "topWidthPx=${row.width} compact=$compactTop ${bounds.joinToString(" ")}")
    }

    fun insertIfCurrent(editorSession: Long, text: String): Boolean {
        val connection = currentInputConnection ?: return false
        if (session != editorSession || !DictationCoordinator.canInsert(editorSession) || !EditorPolicy.permits(currentInputEditorInfo)) return false
        val generation = selectionGeneration
        val target = DictationCoordinator.aiSelection
        if (target != null && !connection.finishComposingText()) return false
        if (target != null && !target.matches(readSelection())) return false
        if (currentInputConnection !== connection || session != editorSession || selectionGeneration != generation || !EditorPolicy.permits(currentInputEditorInfo)) return false
        controller?.onExternalEdit(selectionStart, selectionEnd)
        return connection.commitText(text, 1)
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (selectionStart != newSelStart || selectionEnd != newSelEnd) selectionGeneration++
        selectionStart = newSelStart; selectionEnd = newSelEnd
        updatePolishButton(currentInputEditorInfo)
        polish?.onSelectionChanged(newSelStart, newSelEnd)
        controller?.onSelectionChanged(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        DictationCoordinator.selectionUpdated(newSelStart, newSelEnd)
    }
    // AI and Live need the field's text. Odicto's own fields cannot give it, so they refuse
    // before any recording or request; a guessed selection would fail only at delivery.
    private fun readSelection(): AiSelection? {
        if (session == null || !EditorPolicy.permits(currentInputEditorInfo) || EditorPolicy.ownEditor(currentInputEditorInfo)) return null
        val connection = currentInputConnection ?: return null
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val selected = connection.getSurroundingText(0, 0, 0)
                if (selected == null || selected.offset < 0) {
                    return AiSelection(selectionStart, selectionEnd, connection.getSelectedText(0)?.toString().orEmpty()).takeIf { it.valid }
                }
                val start = selected.selectionStart; val end = selected.selectionEnd
                AiSelection(selected.offset + start, selected.offset + end,
                    selected.text.subSequence(minOf(start, end), maxOf(start, end)).toString()).takeIf { it.valid }
            } else {
                AiSelection(selectionStart, selectionEnd, connection.getSelectedText(0)?.toString().orEmpty()).takeIf { it.valid }
            }
        } catch (_: Exception) { null }
    }
    private fun selectAll(): Boolean {
        if (session == null || !EditorPolicy.permits(currentInputEditorInfo) || EditorPolicy.ownEditor(currentInputEditorInfo)) return false
        val connection = currentInputConnection ?: return false
        return try {
            connection.performContextMenuAction(android.R.id.selectAll) || run {
                val before = connection.getTextBeforeCursor(AiSelection.MAX_TEXT, 0)?.length ?: 0
                val after = connection.getTextAfterCursor(AiSelection.MAX_TEXT, 0)?.length ?: 0
                before + after > 0 && connection.setSelection(0, before + after)
            }
        } catch (_: Exception) { false }
    }
    private fun beginLive(): Boolean {
        val initial = readSelection() ?: return false
        if (currentInputConnection?.finishComposingText() != true) return false
        controller?.onExternalEdit(selectionStart, selectionEnd)
        liveInsertion = LiveInsertion(initial); liveEditor = session; liveConnection = currentInputConnection
        return true
    }
    private fun writeLive(text: String, finish: Boolean): Boolean {
        if (session == null || session != liveEditor || currentInputConnection !== liveConnection || !EditorPolicy.permits(currentInputEditorInfo)) return false
        val writer = liveInsertion ?: return false
        val connection = currentInputConnection ?: return false
        return try {
            val preceding = if (writer.text.isEmpty()) "" else connection.getTextBeforeCursor(writer.text.length, 0)?.toString().orEmpty()
            val ok = writer.update(readSelection(), preceding, text) {
                controller?.onExternalEdit(selectionStart, selectionEnd)
                connection.setComposingText(it, 1)
            }
            ok && (!finish || connection.finishComposingText())
        } catch (_: Exception) { writer.stop(); false }
    }
    private fun endLive() {
        if (liveInsertion != null && session != null && session == liveEditor && currentInputConnection === liveConnection) {
            try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
        }
        liveInsertion?.stop(); liveInsertion = null; liveEditor = null; liveConnection = null
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) expansion?.reset()
        statusViewActive = true
        renderTopStatus()
        controller?.onStartInput(info)
        controller?.onExternalEdit(selectionStart, selectionEnd)
        renderRecovery()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        pauseStatusView(); cancelFieldPolish(); cancelEditorUndo(); controller?.onFinishInput(); super.onFinishInputView(finishingInput)
    }

    private companion object {
        /** Toggle, Undo, six voice cells, Polish. */
        const val TOP_CELLS = 9f
    }
}
