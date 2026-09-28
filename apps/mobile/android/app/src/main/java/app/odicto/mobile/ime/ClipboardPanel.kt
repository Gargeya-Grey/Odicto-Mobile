package app.odicto.mobile.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.odicto.mobile.R
import app.odicto.mobile.storage.ClipboardRepository
import app.odicto.mobile.storage.ClipboardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class ClipboardPanel(private val context: Context) {
    private val repository by lazy { ClipboardRepository.get(context) }
    private var open = false
    private var state = ClipboardState()
    private var collector: Job? = null
    private var binding: ClipboardPanelBinding? = null
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val isOpen: Boolean get() = open
    val isEmpty: Boolean get() = state.history.isEmpty() && state.pins.isEmpty()

    fun submit(value: ClipboardState) {
        state = value
        binding?.submit(value)
    }

    fun openForDisplay(): List<String> {
        open = true
        return readForDisplay()
    }

    fun readForDisplay(): List<String> = state.pins + state.history

    fun close() {
        open = false
        collector?.cancel()
        collector = null
        binding = null
        state = ClipboardState()
    }

    fun mount(container: LinearLayout, canPaste: () -> Boolean, onPaste: (String) -> Unit) {
        close()
        open = true
        lateinit var view: ClipboardPanelBinding
        fun mutate(block: suspend () -> Unit) {
            writes.launch {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (binding === view) view.showError()
                }
            }
        }
        view = ClipboardPanelBinding(context, OdictoKeyboardView.typefaceFor(context),
            ClipboardPanelActions(
                paste = { if (canPaste()) onPaste(it) },
                pin = { text, pinned -> mutate { repository.setPinned(text, pinned) } },
                delete = { texts -> mutate { repository.delete(texts) } },
                clear = { mutate { repository.clearUnpinned() } },
                automaticCapture = { enabled -> mutate { repository.setAutomaticCapture(enabled) } },
            ))
        binding = view
        container.removeAllViews()
        container.addView(view.root, LinearLayout.LayoutParams(-1, -1))
        view.showLoading()
        collector = writes.launch {
            try { repository.data.collect { submit(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (binding === view) view.showError() }
        }
    }

    companion object {
        const val DISCLOSURE = "Copied plain text stays only on this device: 50 recent items plus pins. Capture works only while Odicto is the default keyboard in an eligible field and Android allows access. Marked-sensitive clips are skipped; unmarked secrets cannot be detected. Turn off Auto-save to disable automatic saving. Clearing history does not clear the system clipboard. Disabling saving does not erase saved clips. Clipboard history is excluded from backup, logs, and network."

        fun preview(text: String, maxCodePoints: Int = 160): String {
            require(maxCodePoints >= 0)
            if (text.codePointCount(0, text.length) <= maxCodePoints) return text
            return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…"
        }
    }
}

internal data class ClipboardPanelActions(
    val paste: (String) -> Unit = {},
    val pin: (String, Boolean) -> Unit = { _, _ -> },
    val delete: (Set<String>) -> Unit = {},
    val clear: () -> Unit = {},
    val automaticCapture: (Boolean) -> Unit = {},
)

internal class ClipboardPanelBinding(
    private val context: Context,
    private val font: Typeface,
    private val actions: ClipboardPanelActions = ClipboardPanelActions(),
) {
    private val ink = 0xFFF3EFFB.toInt()
    private val muted = 0xFFBBB7C6.toInt()
    private val accent = 0xFFC5B3FF.toInt()
    private val surface = 0xFF292933.toInt()
    private var state = ClipboardState()
    private var selecting = false
    private var expanded = false
    private var confirming = false
    private var updating = false
    private val selected = linkedSetOf<String>()
    private val rows = linkedMapOf<String, LinearLayout>()
    val root = column().apply { setBackgroundColor(0xFF0E0E10.toInt()) }
    private val title = label("Clipboard", 16f)
    private val count = label("0 clips", 12f, muted)
    private val select = icon("Select clips", R.drawable.odicto_clipboard_select) {
        selecting = !selecting
        selected.clear()
        confirming = false
        render()
    }
    private val clear = icon("Clear all unpinned", R.drawable.odicto_clipboard_delete) {
        confirming = true
        render()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }
    private val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
    private val content = column().apply { setPadding(dp(8), 0, dp(8), dp(8)) }
    private val status = label("", 13f, muted).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val autoSave = Switch(context).apply {
        text = "Auto-save"
        contentDescription = "Save copied text automatically"
        typeface = font
        textSize = 13f
        setTextColor(ink)
        minimumHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66C5B3FF, 0xFF45454F.toInt()))
        setOnCheckedChangeListener { _, enabled -> if (!updating) actions.automaticCapture(enabled) }
    }
    private val privacy = icon("Local only. Show clipboard privacy details", R.drawable.odicto_clipboard_info) {
        expanded = !expanded
        render()
    }
    private val details = label(ClipboardPanel.DISCLOSURE, 13f, muted)
    private val selectionBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val selectedCount = label("", 13f, accent)
    private val all = icon("Select all unpinned", R.drawable.odicto_clipboard_select) {
        selected.addAll(state.history.filter { it !in state.pins })
        render()
    }
    private val delete = icon("Delete selected clips", R.drawable.odicto_clipboard_delete) {
        val safe = selected.filter { it in state.history && it !in state.pins }.toSet()
        if (safe.isNotEmpty()) actions.delete(safe)
    }
    private val confirmation = column()
    private val list = column()

    init {
        title.setPadding(0, 0, 0, 0)
        count.setPadding(0, 0, 0, 0)
        val heading = column().apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            addView(title)
            addView(count)
        }
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            addView(select)
            addView(clear)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.addView(content)
        content.addView(status)
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(autoSave, LinearLayout.LayoutParams(0, -2, 1f))
            addView(privacy)
        })
        content.addView(details)
        selectionBar.addView(selectedCount, LinearLayout.LayoutParams(0, -2, 1f))
        selectionBar.addView(all)
        selectionBar.addView(delete)
        content.addView(selectionBar)
        confirmation.addView(label("Clear recent clips? Pins stay saved. This cannot be undone.", 13f))
        confirmation.addView(LinearLayout(context).apply {
            addView(textAction("Cancel") { confirming = false; render(); clear.requestFocus() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(textAction("Clear recent") {
                confirming = false
                render()
                actions.clear()
                clear.requestFocus()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        content.addView(confirmation)
        content.addView(list)
        render()
    }

    fun showLoading() {
        status.text = "Loading saved clipboard…"
        status.visibility = View.VISIBLE
        autoSave.isEnabled = false
    }

    fun showError() {
        updating = true
        autoSave.isChecked = state.automaticCapture
        updating = false
        status.text = "Clipboard storage is unavailable. Nothing was reset."
        status.visibility = View.VISIBLE
    }

    fun submit(value: ClipboardState) {
        state = value
        selected.retainAll(value.history.filter { it !in value.pins }.toSet())
        status.visibility = View.GONE
        autoSave.isEnabled = true
        render()
    }

    private fun render() {
        val previousY = scroll.scrollY
        val anchor = rows.entries.filter { it.value.parent === list && list.top + it.value.bottom > previousY }
            .minByOrNull { it.value.top }
        val anchorOffset = anchor?.let { list.top + it.value.top - previousY }
        updating = true
        autoSave.isChecked = state.automaticCapture
        updating = false
        count.text = "${state.pins.size + state.history.size} clips · Local only"
        select.setImageResource(if (selecting) R.drawable.odicto_clipboard_done else R.drawable.odicto_clipboard_select)
        select.contentDescription = if (selecting) "Done selecting clips" else "Select clips"
        select.isSelected = selecting
        clear.isEnabled = state.history.isNotEmpty()
        clear.alpha = if (clear.isEnabled) 1f else 0.4f
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        privacy.contentDescription = if (expanded) "Local only. Hide clipboard privacy details" else "Local only. Show clipboard privacy details"
        privacy.isSelected = expanded
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selectedCount.text = "${selected.size} selected"
        delete.isEnabled = selected.isNotEmpty()
        delete.alpha = if (delete.isEnabled) 1f else 0.4f
        confirmation.visibility = if (confirming) View.VISIBLE else View.GONE
        val payloads = state.pins + state.history.filter { it !in state.pins }
        val oldOrder = (0 until list.childCount).map { list.getChildAt(it).tag }
        val newOrder = buildList<Any> {
            if (state.pins.isNotEmpty()) add(true)
            addAll(state.pins)
            if (state.history.isNotEmpty()) add(false)
            addAll(state.history.filter { it !in state.pins })
        }
        if (oldOrder != newOrder || payloads.isEmpty()) {
            list.removeAllViews()
            rows.keys.retainAll(payloads.toSet())
            if (payloads.isEmpty()) list.addView(label("No saved clips yet. Copy text in an eligible field while Odicto is active.", 14f, muted))
            for (item in newOrder) {
                if (item is Boolean) list.addView(label(if (item) "Pinned" else "Recent", 12f, muted).apply {
                    tag = item
                    setPadding(dp(8), dp(8), dp(8), dp(4))
                    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                })
                else if (item is String) list.addView(rows.getOrPut(item) { clipRow(item) })
            }
            list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    view.removeOnLayoutChangeListener(this)
                    val target = anchor?.key?.let { rows[it] }
                    scroll.scrollTo(0, if (target != null && anchorOffset != null) list.top + target.top - anchorOffset else previousY)
                }
            })
        }
        rows.forEach { (payload, row) ->
            val pinned = payload in state.pins
            val chosen = payload in selected
            val text = row.getChildAt(0) as Button
            text.isSelected = chosen
            text.contentDescription = "${if (pinned) "Pinned clip" else "Clip"}. ${ClipboardPanel.preview(payload)}. " +
                if (selecting) { if (pinned) "Protected from selection" else if (chosen) "Selected. Deselect clip" else "Select clip" } else "Paste clip"
            text.setCompoundDrawablesRelativeWithIntrinsicBounds(if (chosen) R.drawable.odicto_clipboard_done else 0, 0, 0, 0)
            val pin = row.getChildAt(1) as ImageButton
            pin.setImageResource(if (pinned) R.drawable.odicto_clipboard_unpin else R.drawable.odicto_clipboard_pin)
            pin.contentDescription = if (pinned) "Unpin clip" else "Pin clip"
            pin.isSelected = false
            pin.imageTintList = ColorStateList.valueOf(if (pinned) accent else muted)
        }
    }

    private fun clipRow(payload: String) = LinearLayout(context).apply {
        tag = payload
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPaddingRelative(0, 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        background = shape(surface)
        addView(textAction(ClipboardPanel.preview(payload)) {
            if (selecting) {
                if (payload !in state.pins && !selected.add(payload)) selected.remove(payload)
                render()
            } else actions.paste(payload)
        }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = dp(72)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = dp(8)
            setPadding(dp(12), dp(12), dp(8), dp(12))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon("Pin clip", R.drawable.odicto_clipboard_pin) { actions.pin(payload, payload !in state.pins) }.apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float, color: Int = ink) = TextView(context).apply {
        text = value
        textSize = size
        typeface = font
        setTextColor(color)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun textAction(value: String, click: () -> Unit) = Button(context).apply {
        text = value
        textSize = 14f
        typeface = font
        isAllCaps = false
        setTextColor(ink)
        minWidth = 0
        minimumWidth = dp(48)
        minHeight = 0
        minimumHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        stateListAnimator = null
        elevation = 0f
        background = feedback()
        setOnClickListener { click() }
    }
    private fun icon(name: String, drawable: Int, click: () -> Unit) = ImageButton(context).apply {
        contentDescription = name
        if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = name
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(accent)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = feedback()
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { click() }
    }
    private fun shape(color: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
        if (border) setStroke(dp(2), accent)
    }
    private fun feedback() = RippleDrawable(ColorStateList.valueOf(0x33C5B3FF), StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(surface, true))
        addState(intArrayOf(android.R.attr.state_selected), shape(0xFF393044.toInt(), true))
        addState(intArrayOf(), shape(android.graphics.Color.TRANSPARENT))
    }, shape(android.graphics.Color.WHITE))
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
