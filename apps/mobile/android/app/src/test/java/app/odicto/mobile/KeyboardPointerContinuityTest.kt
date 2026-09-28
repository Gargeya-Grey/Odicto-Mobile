package app.odicto.mobile

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.odicto.mobile.ime.KeyLatencyProbe
import app.odicto.mobile.ime.OdictoKeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardPointerContinuityTest {
    private class Trial {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = OdictoKeyboardView(context)
        val output = mutableListOf<OdictoKeyboardView.KeySpec>()
        private var downTime = 0L
        init {
            view.measure(View.MeasureSpec.makeMeasureSpec((400 * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            view.setOnKeyListener { output += it }
        }
        fun key(name: String): View {
            fun leaves(v: View): List<View> = if (v is ViewGroup) (0 until v.childCount).flatMap { leaves(v.getChildAt(it)) } else listOf(v)
            return leaves(view).first { it.contentDescription == name }
        }
        fun send(action: Int, vararg fingers: Pair<Int, String>) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val properties = fingers.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = fingers.map { (_, name) ->
                val cap = key(name)
                val row = cap.parent as View
                MotionEvent.PointerCoords().apply { x = row.x + cap.x + cap.width / 2f; y = row.y + cap.y + cap.height / 2f; pressure = 1f; size = 1f }
            }.toTypedArray()
            val event = MotionEvent.obtain(downTime, now, action, fingers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun ordinals() = output.filter { it.key == OdictoKeyboardView.Key.BACKSPACE }.map { it.repeatOrdinal }
    }

    @Test fun blockedGateRetainsHoldAndOrdinalUntilEvidenceReturns() {
        val t = Trial()
        var ready = false
        var attempts = 0
        t.view.setBackspaceRepeatGate { attempts++; ready }
        t.send(MotionEvent.ACTION_DOWN, 7 to "Backspace")
        KeyLatencyProbe.snapshot(clear = true)
        t.advance(260)
        assertFalse(KeyLatencyProbe.snapshot().any { it.metric == KeyLatencyProbe.Metric.VIBRATION_API })
        assertEquals(listOf(0), t.ordinals())
        assertEquals(1, attempts)
        ready = true
        t.advance(42)
        assertEquals(listOf(0, 1), t.ordinals())
        t.send(MotionEvent.ACTION_UP, 7 to "Backspace")
        t.advance(500)
        assertEquals(listOf(0, 1), t.ordinals())
    }

    @Test fun secondaryReleaseClearsItsVisualWithoutClearingPrimary() {
        val t = Trial()
        t.send(MotionEvent.ACTION_DOWN, 7 to "q")
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to "q", 12 to "w")
        assertTrue(t.key("q").isPressed)
        assertTrue(t.key("w").isPressed)
        t.send(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 7 to "q", 12 to "w")
        assertFalse(t.key("w").isPressed)
        assertTrue(t.key("q").isPressed)
        t.send(MotionEvent.ACTION_UP, 7 to "q")
        assertFalse(t.key("q").isPressed)
        assertEquals(listOf("q", "w"), t.output.map { it.output })
    }

    @Test fun primaryReleaseDoesNotTransferHoldAndNewContactCanOwnNextHold() {
        val t = Trial()
        t.send(MotionEvent.ACTION_DOWN, 7 to "Backspace")
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to "Backspace", 12 to "q")
        t.send(MotionEvent.ACTION_POINTER_UP, 7 to "Backspace", 12 to "q")
        t.send(MotionEvent.ACTION_MOVE, 12 to "q")
        t.advance(500)
        assertEquals(listOf(0), t.ordinals())
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 12 to "q", 20 to "Backspace")
        t.advance(260)
        assertEquals(listOf(0, 0, 1), t.ordinals())
        t.send(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 12 to "q", 20 to "Backspace")
        t.send(MotionEvent.ACTION_UP, 12 to "q")
        t.advance(500)
        assertEquals(listOf(0, 0, 1), t.ordinals())
        assertFalse(t.key("q").isPressed)
    }

    @Test fun liftingOwnerNeverTransfersRepeatToAnotherHeldBackspaceContact() {
        val t = Trial()
        t.send(MotionEvent.ACTION_DOWN, 7 to "Backspace")
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to "Backspace", 12 to "Backspace")
        t.send(MotionEvent.ACTION_POINTER_UP, 7 to "Backspace", 12 to "Backspace")
        t.advance(500)
        assertEquals(listOf(0, 0), t.ordinals())
        assertTrue(t.key("Backspace").isPressed)
        t.send(MotionEvent.ACTION_UP, 12 to "Backspace")
        assertFalse(t.key("Backspace").isPressed)
    }

    @Test fun secondaryBackspaceOwnsRepeatAcrossPrimaryLift() {
        val t = Trial()
        t.send(MotionEvent.ACTION_DOWN, 7 to "q")
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to "q", 12 to "Backspace")
        t.send(MotionEvent.ACTION_POINTER_UP, 7 to "q", 12 to "Backspace")
        t.advance(260)
        assertEquals(listOf(0, 1), t.ordinals())
        t.send(MotionEvent.ACTION_UP, 12 to "Backspace")
        t.advance(500)
        assertEquals(listOf(0, 1), t.ordinals())
    }

    @Test fun gateCancellationCannotDispatchOrReschedule() {
        val t = Trial()
        var attempts = 0
        t.view.setBackspaceRepeatGate { attempts++; t.view.stopRepeating(); true }
        t.send(MotionEvent.ACTION_DOWN, 7 to "Backspace")
        t.advance(1000)
        assertEquals(listOf(0), t.ordinals())
        assertEquals(1, attempts)
        t.send(MotionEvent.ACTION_CANCEL, 7 to "Backspace")
    }

    @Test fun slowDispatchSkipsDeadlinesWithoutAddingCallbackDurationToCadence() {
        val t = Trial()
        val times = mutableListOf<Long>()
        t.view.setOnKeyListener {
            t.output += it
            if (it.repeatOrdinal > 0) {
                times += SystemClock.uptimeMillis()
                if (it.repeatOrdinal == 1) org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(100))
            }
        }
        val start = SystemClock.uptimeMillis()
        t.send(MotionEvent.ACTION_DOWN, 7 to "Backspace")
        t.advance(260)
        assertEquals(listOf(start + 260), times)
        t.advance(25)
        assertEquals(listOf(start + 260), times)
        t.advance(1)
        assertEquals(listOf(start + 260, start + 386), times)
        t.send(MotionEvent.ACTION_UP, 7 to "Backspace")
    }

    @Test fun initialDispatchCancellationCannotBeRearmedAfterCallback() {
        val t = Trial()
        t.view.setOnKeyListener { t.output += it; t.view.stopRepeating() }
        t.send(MotionEvent.ACTION_DOWN, 7 to "Backspace")
        t.advance(1000)
        assertEquals(listOf(0), t.ordinals())
        t.send(MotionEvent.ACTION_UP, 7 to "Backspace")
    }

    @Test fun bothThumbsOnSameKeyRetainVisualUntilLastRelease() {
        val t = Trial()
        t.send(MotionEvent.ACTION_DOWN, 7 to "q")
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to "q", 12 to "q")
        t.send(MotionEvent.ACTION_POINTER_UP, 7 to "q", 12 to "q")
        assertTrue(t.key("q").isPressed)
        t.send(MotionEvent.ACTION_UP, 12 to "q")
        assertFalse(t.key("q").isPressed)
        assertEquals(listOf("q", "q"), t.output.map { it.output })
    }

    @Test fun otherThumbDoesNotCancelSpaceOwner() {
        val t = Trial()
        t.send(MotionEvent.ACTION_DOWN, 7 to "Space")
        t.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to "Space", 12 to "q")
        t.send(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 7 to "Space", 12 to "q")
        t.send(MotionEvent.ACTION_UP, 7 to "Space")
        assertEquals(listOf("q", " "), t.output.map { it.output })
    }
}
