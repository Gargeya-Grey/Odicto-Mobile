package app.odicto.mobile.ime

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.odicto.mobile.R
import kotlinx.coroutines.*

/** Content-only presentation; local dictionary work never runs on the key-dispatch thread. */
internal class SpellingStrip(
    private val row: LinearLayout,
    private val choose: (String) -> Unit,
    private val undo: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private var generation = 0L
    private var shownWord: String? = null
    private var shownUndo: String? = null
    private var available = false
    private var statusVisible = false
    private val assistance = row.parent as View

    init {
        // All populated rows use the same font-aware height; empty rows take no space.
        val metrics = TextView(row.context).apply { textSize = 16f }.paint.fontMetricsInt
        row.layoutParams = row.layoutParams.apply { height = maxOf(height, metrics.bottom - metrics.top) }
        updateVisibility()
    }

    fun render(visible: Boolean, word: String?, original: String?) {
        if (visible == available && word == shownWord && original == shownUndo) return
        available = visible
        shownWord = word
        shownUndo = original
        val version = ++generation
        request?.cancel()
        if (!visible || word == null || original != null) row.removeAllViews()
        if (original != null) {
            if (visible) button("Undo · $original", "Undo autocorrect", version) { undo() }
            updateVisibility()
            return
        }
        updateVisibility()
        if (!visible) return
        request = scope.launch {
            // Warm the bundled lexicon even before the first word. Typed words remain transient.
            val dictionary = withContext(Dispatchers.Default) {
                runCatching { EnglishSpelling.load(row.context.applicationContext) }.getOrNull()
            }
            if (word == null) return@launch
            delay(70)
            val suggestions = withContext(Dispatchers.Default) { dictionary?.suggest(word).orEmpty() }
            if (version != generation || !available) return@launch
            // Keep the last choices/Undo during a short lookup to avoid flashing the header.
            // Their generation guard already prevents applying them to the new word.
            row.removeAllViews()
            suggestions.forEach { candidate ->
                button(candidate, "Suggestion $candidate", version) { choose(candidate) }
            }
            updateVisibility()
        }
    }

    /** A message owns the shared header while present; an entirely empty header is removed. */
    fun setStatusVisible(visible: Boolean) {
        if (statusVisible == visible) return
        statusVisible = visible
        updateVisibility()
    }

    private fun updateVisibility() {
        val populated = available && row.childCount > 0
        row.visibility = when {
            !populated -> View.GONE
            !statusVisible -> View.VISIBLE
            else -> View.INVISIBLE
        }
        assistance.visibility = if (populated || statusVisible) View.VISIBLE else View.GONE
        row.importantForAccessibility = if (populated && !statusVisible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun button(label: String, description: String, version: Long, action: () -> Unit) {
        row.addView(TextView(row.context).apply {
            text = label
            contentDescription = description
            textSize = 16f
            setTextColor(Color.rgb(220, 211, 255))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.odicto_key_background)
            setOnClickListener { if (version == generation && available && !statusVisible) action() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            val margin = (3 * row.resources.displayMetrics.density).toInt()
            setMargins(margin, 0, margin, 0)
        })
    }

    fun dispose() { generation++; scope.cancel(); row.removeAllViews(); available = false; updateVisibility() }

}
