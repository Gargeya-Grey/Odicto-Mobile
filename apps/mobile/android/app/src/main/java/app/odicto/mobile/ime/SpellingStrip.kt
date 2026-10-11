package app.odicto.mobile.ime

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.odicto.mobile.R
import kotlinx.coroutines.*

/**
 * Content-only presentation; local dictionary work never runs on the key-dispatch thread.
 *
 * The strip lives inside the fixed top row and covers only the tool cells, so it never changes the
 * keyboard's height. Three slots: the typed word (keep it), the word that space will apply (bold),
 * and one more choice. Between words the empty strip lingers briefly so continuous typing does not
 * flash the tools in and out.
 */
internal class SpellingStrip(
    private val row: LinearLayout,
    private val choose: (String) -> Unit,
    private val keep: () -> Unit,
    private val undo: () -> Unit,
    private val covered: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var request: Job? = null
    private var generation = 0L
    private var shown: Key? = null
    private var available = false
    private var lingering = false
    private var statusVisible = false
    private var regionVisible = false
    private val assistance = row.parent as View
    private val density = row.resources.displayMetrics.density
    private val endLinger = Runnable { lingering = false; updateVisibility() }
    private data class Key(val visible: Boolean, val word: String?, val original: String?, val sentenceStart: Boolean, val keep: Boolean)

    /** Three slots and two dividers, built once and reused; a keystroke only rewrites their text. */
    private val slots = List(3) { index -> TextView(row.context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        setBackgroundResource(R.drawable.odicto_suggestion_background)
        setOnClickListener { if (slotVersions[index] == generation && available && !statusVisible) slotActions[index]?.invoke() }
    } }
    private val slotActions = arrayOfNulls<() -> Unit>(3)
    private val slotVersions = LongArray(3) { -1L }
    private val dividers = List(2) { View(row.context).apply {
        setBackgroundColor(context.getColor(R.color.odicto_divider))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    } }
    private var filled = false
    private val strong = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init {
        val divider = LinearLayout.LayoutParams((density + 0.5f).toInt().coerceAtLeast(1), (18 * density).toInt())
        listOf(slots[0], dividers[0], slots[1], dividers[1], slots[2]).forEach { view ->
            row.addView(view, if (view is TextView) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(divider))
        }
        clear()
        updateVisibility()
    }

    /**
     * [word] is the word before the cursor, [original] the word an Undo would restore, [keep] true
     * when the user chose to keep [word] as typed.
     */
    fun render(visible: Boolean, word: String?, original: String?, sentenceStart: Boolean = false, keep: Boolean = false) {
        val key = Key(visible, word, original, sentenceStart, keep)
        if (key == shown) return
        val wasPopulated = available && filled
        shown = key
        available = visible
        val version = ++generation
        request?.cancel()
        handler.removeCallbacks(endLinger)
        if (!visible) {
            lingering = false
            clear()
            updateVisibility()
            return
        }
        if (original != null) {
            lingering = false
            clear()
            // Undo takes the whole strip: the side slots and dividers leave the row.
            set(1, "Undo  “$original”", "Undo autocorrect", Style.PLAIN, version) { undo() }
            listOf(slots[0], slots[2], dividers[0], dividers[1]).forEach { it.visibility = View.GONE }
            filled = true
            updateVisibility()
            return
        }
        if (word == null) {
            clear()
            // Keep the slot through the gap between two words; drop it after a pause.
            lingering = wasPopulated || lingering
            if (lingering) handler.postDelayed(endLinger, LINGER_MS)
            updateVisibility()
            return
        }
        request = scope.launch {
            // Warm the bundled lexicon even before the first word. Typed words remain transient.
            val dictionary = withContext(Dispatchers.Default) {
                runCatching { EnglishSpelling.load(row.context.applicationContext) }.getOrNull()
            }
            val choices = withContext(Dispatchers.Default) { choicesFor(dictionary, word, sentenceStart, keep) }
            if (version != generation || !available) return@launch
            // The previous choices stay up during the lookup, so the strip does not flash empty.
            lingering = false
            dividers.forEach { it.visibility = View.VISIBLE }
            set(0, "“$word”", "Keep $word", Style.MUTED, version) { keep() }
            val (auto, center, right) = choices
            set(1, center, center?.let { "Suggestion $it" }, if (auto) Style.BOLD else Style.PLAIN, version) { center?.let(choose) }
            set(2, right, right?.let { "Suggestion $it" }, Style.PLAIN, version) { right?.let(choose) }
            filled = true
            updateVisibility()
        }
    }

    /** (true when the centre word is applied on space, centre, right). */
    private fun choicesFor(dictionary: EnglishSpelling?, word: String, sentenceStart: Boolean, keep: Boolean): Triple<Boolean, String?, String?> {
        if (dictionary == null) {
            val curated = if (keep) null else EnglishSpelling.builtinCorrection(word, sentenceStart)
            return Triple(curated != null, curated, null)
        }
        val auto = if (keep) null else dictionary.autocorrect(word, sentenceStart)
        val others = (dictionary.suggest(word) + dictionary.complete(word, 3))
            .filter { it != auto && it != word }.distinct()
        return if (auto != null) Triple(true, auto, others.firstOrNull())
        else Triple(false, others.getOrNull(0), others.getOrNull(1))
    }

    val statusShown: Boolean get() = statusVisible

    /** A message owns the shared slot while present; an entirely empty slot is removed. */
    fun setStatusVisible(visible: Boolean) {
        if (statusVisible == visible) return
        statusVisible = visible
        updateVisibility()
    }

    private fun updateVisibility() {
        val populated = available && filled
        row.visibility = when {
            !populated -> View.GONE
            !statusVisible -> View.VISIBLE
            else -> View.INVISIBLE
        }
        val visible = populated || statusVisible || (available && lingering)
        if (visible != regionVisible) {
            regionVisible = visible
            assistance.animate().cancel()
            if (visible) {
                assistance.visibility = View.VISIBLE
                // Suggestions fade in; a status line appears at once, since it reports an action.
                if (populated && !statusVisible) {
                    assistance.alpha = 0f
                    assistance.animate().alpha(1f).setDuration(FADE_MS).start()
                } else assistance.alpha = 1f
            } else {
                assistance.alpha = 1f
                assistance.visibility = View.GONE
            }
            covered(visible)
        }
        row.importantForAccessibility = if (populated && !statusVisible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private enum class Style { MUTED, PLAIN, BOLD }

    /** An empty label keeps the slot's width but leaves it silent and inert. */
    private fun set(index: Int, label: String?, description: String?, style: Style, version: Long, action: () -> Unit) {
        val view = slots[index]
        view.visibility = if (label == null) View.INVISIBLE else View.VISIBLE
        view.text = label.orEmpty()
        view.contentDescription = description
        view.typeface = if (style == Style.BOLD) strong else Typeface.DEFAULT
        view.setTextColor(view.context.getColor(when (style) {
            Style.MUTED -> R.color.odicto_text_muted
            Style.PLAIN -> R.color.odicto_toolbar_icon
            Style.BOLD -> R.color.odicto_text_strong
        }))
        slotActions[index] = if (label == null) null else action
        slotVersions[index] = version
    }

    private fun clear() {
        filled = false
        slots.forEachIndexed { index, view ->
            view.visibility = View.GONE; view.text = ""; view.contentDescription = null
            slotActions[index] = null; slotVersions[index] = -1L
        }
        dividers.forEach { it.visibility = View.GONE }
    }

    fun dispose() {
        generation++; scope.cancel(); handler.removeCallbacks(endLinger)
        clear(); available = false; lingering = false; updateVisibility()
    }

    private companion object {
        const val LINGER_MS = 1600L
        const val FADE_MS = 110L
    }
}
