package app.odicto.mobile.ime

import android.content.Context
import android.view.MotionEvent

/** One immediate delete, then repeat while held. Never carries a hold across editors. */
class RepeatKeyButton(context: Context) : EditingKeyButton(context, "backspace") {
    private var held = false
    private val repeat = object : Runnable {
        override fun run() {
            if (!held || !isEnabled || !isShown) { stopRepeating(); return }
            performClick()
            if (held) postDelayed(this, REPEAT_MS)
        }
    }
    fun stopRepeating() { held = false; isPressed = false; removeCallbacks(repeat) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { stopRepeating(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopRepeating(); held = true; isPressed = true
                performClick(); postDelayed(repeat, HOLD_MS); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.x < 0 || event.x >= width || event.y < 0 || event.y >= height) stopRepeating()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                stopRepeating(); return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun onDetachedFromWindow() { stopRepeating(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { if (!hasWindowFocus) stopRepeating(); super.onWindowFocusChanged(hasWindowFocus) }
    companion object { const val HOLD_MS = 400L; const val REPEAT_MS = 65L }
}
