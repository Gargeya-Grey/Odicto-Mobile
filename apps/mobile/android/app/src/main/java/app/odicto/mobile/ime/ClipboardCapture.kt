package app.odicto.mobile.ime

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import app.odicto.mobile.storage.ClipboardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal data class CapturedClipboard(val texts: List<String>, val token: String, val isChange: Boolean = false)

internal interface ClipboardSource {
    fun isDefaultIme(): Boolean
    fun isSensitive(): Boolean
    fun readPlainText(): CapturedClipboard?
    fun addListener(listener: () -> Unit)
    fun removeListener(listener: () -> Unit)
}

internal class ClipboardCaptureGate(
    private val source: ClipboardSource,
    private val captured: (CapturedClipboard) -> Unit,
) {
    private var enabled = false
    private var eligible = false
    private var listening = false
    private var closed = false
    private val listener: () -> Unit = { sample(isChange = true) }

    fun update(enabled: Boolean, editorEligible: Boolean) {
        this.enabled = enabled
        eligible = editorEligible
        refresh()
    }

    fun refresh() {
        val allowed = allowed()
        if (allowed && !listening) {
            try {
                source.addListener(listener)
                listening = true
                sample()
            } catch (_: SecurityException) { stop() }
        } else if (!allowed) stop()
    }

    private fun allowed(): Boolean = !closed && enabled && eligible &&
        try { source.isDefaultIme() } catch (_: SecurityException) { false }

    private fun sample(isChange: Boolean = false) {
        if (!allowed()) { stop(); return }
        try {
            if (source.isSensitive()) return
            val clip = source.readPlainText() ?: return
            if (allowed()) captured(clip.copy(isChange = isChange))
        } catch (_: SecurityException) { stop() }
    }

    private fun stop() {
        if (!listening) return
        listening = false
        try { source.removeListener(listener) } catch (_: SecurityException) { }
    }

    fun close() { closed = true; stop() }
}

internal class AndroidClipboardSource(context: Context) : ClipboardSource {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var platformListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun isDefaultIme(): Boolean {
        val current = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = current?.let { ComponentName.unflattenFromString(it) }
        return component == ComponentName(app, "app.odicto.mobile.ime.OdictoImeService")
    }

    override fun isSensitive(): Boolean = Build.VERSION.SDK_INT >= 24 &&
        manager.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true

    override fun readPlainText(): CapturedClipboard? {
        if (isSensitive()) return null
        val clip = manager.primaryClip ?: return null
        if (Build.VERSION.SDK_INT >= 24 &&
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return null
        val texts = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
            .filter { it.isNotEmpty() }
        val digest = MessageDigest.getInstance("SHA-256")
        for (text in texts) {
            digest.update(text.length.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(text.toByteArray(Charsets.UTF_8))
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val timestamp = if (Build.VERSION.SDK_INT >= 26) clip.description.timestamp else 0L
        return CapturedClipboard(texts, "$timestamp:$hash")
    }

    override fun addListener(listener: () -> Unit) {
        if (platformListener != null) return
        val callback = ClipboardManager.OnPrimaryClipChangedListener { listener() }
        manager.addPrimaryClipChangedListener(callback)
        platformListener = callback
    }

    override fun removeListener(listener: () -> Unit) {
        platformListener?.let { manager.removePrimaryClipChangedListener(it) }
        platformListener = null
    }
}

internal class ClipboardCapture(
    context: Context,
    ownerScope: CoroutineScope,
    private val repository: ClipboardRepository = ClipboardRepository.get(context),
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var enabled = false
    private var eligible = false
    private val gate = ClipboardCaptureGate(AndroidClipboardSource(app), ::persist)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { gate.refresh() }
    }

    private var observerRegistered = false

    init {
        try {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer,
            )
            observerRegistered = true
        } catch (_: SecurityException) { }
        scope.launch {
            try {
                repository.data.collect {
                    enabled = it.automaticCapture
                    gate.update(enabled, eligible)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                enabled = false
                gate.update(false, eligible)
                mutableError.value = "Clipboard storage is unavailable. Nothing was reset."
            }
        }
    }

    private fun persist(clip: CapturedClipboard) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repository.capture(clip.texts, clip.token, clip.isChange)
                mutableError.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = "Could not save this clipboard item." }
        }
    }

    fun onEditorChanged(info: EditorInfo?) {
        eligible = EditorPolicy.clipboardPermits(info)
        gate.update(enabled, eligible)
    }

    fun close() {
        gate.close()
        if (observerRegistered) {
            app.contentResolver.unregisterContentObserver(observer)
            observerRegistered = false
        }
        scope.cancel()
    }
}
