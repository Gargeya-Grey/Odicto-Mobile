package app.odicto.mobile.ime

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import app.odicto.mobile.storage.ClipboardState

class ClipboardPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E0E10.toInt()
        window.navigationBarColor = 0xFF0E0E10.toInt()
        var state = ClipboardState(
            pins = listOf("Design review\nThursday at 10:30", "Thanks for the thoughtful feedback. I will share the updated draft tomorrow."),
            history = listOf("A small, useful detail can make the whole experience feel considered.", "Shopping list\nCoffee beans\nOat milk\nFresh lemons", "https://example.com/project/notes", "  Exact spacing stays intact.\n" + "A longer synthetic clipboard entry. ".repeat(18)),
            automaticCapture = true,
        )
        lateinit var panel: ClipboardPanelBinding
        panel = ClipboardPanelBinding(this, OdictoKeyboardView.flexTypeface(this), ClipboardPanelActions(
            pin = { text, pinned ->
                state = if (pinned) state.copy(pins = listOf(text) + state.pins, history = state.history - text)
                    else state.copy(pins = state.pins - text, history = listOf(text) + state.history)
                panel.submit(state)
            },
            delete = { selected -> state = state.copy(history = state.history.filter { it !in selected }); panel.submit(state) },
            clear = { state = state.copy(history = emptyList()); panel.submit(state) },
            automaticCapture = { enabled -> state = state.copy(automaticCapture = enabled); panel.submit(state) },
        ))
        panel.submit(state)
        val density = resources.displayMetrics.density
        val width = intent.getIntExtra("widthDp", 360).coerceIn(240, 411)
        val height = intent.getIntExtra("heightDp", 360).coerceIn(160, 720)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF18181E.toInt())
            addView(panel.root, FrameLayout.LayoutParams(
                (width * density).toInt().coerceAtMost(resources.displayMetrics.widthPixels),
                (height * density).toInt().coerceAtMost(resources.displayMetrics.heightPixels - (64 * density).toInt()),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ))
        })
    }
}
