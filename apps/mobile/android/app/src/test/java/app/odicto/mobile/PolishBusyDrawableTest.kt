package app.odicto.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import app.odicto.mobile.ime.OdictoImeService
import app.odicto.mobile.ime.PolishBusyDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolishBusyDrawableTest {
    @Test fun disabledMotionDrawsThreeSolidLilacStars() {
        val drawable = PolishBusyDrawable(1f) { false }
        drawable.setBounds(0, 0, 240, 240)
        drawable.start()
        assertFalse(drawable.isRunning())
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        for ((x, y) in listOf(120 to 100, 190 to 200, 40 to 170)) {
            assertEquals(0xFFC5B3FF.toInt(), bitmap.getPixel(x, y))
        }
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(20, 20))
        assertEquals(24, drawable.intrinsicWidth)
        assertEquals(24, drawable.intrinsicHeight)
    }

    @Test fun allStarsBreatheContinuouslyWithoutDisappearing() {
        for (star in 0..2) {
            val values = (0..1000).map { PolishBusyDrawable.opacity(it / 1000f, star) }
            assertTrue(values.min() >= 0.579f)
            assertTrue(values.min() < 0.59f)
            assertTrue(values.max() > 0.99f)
            assertEquals(values.first(), values.last(), 0.00001f)
            assertTrue(values.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) < 0.002f })
        }
        assertNotEquals(PolishBusyDrawable.opacity(0f, 0), PolishBusyDrawable.opacity(0f, 1))
        assertNotEquals(PolishBusyDrawable.opacity(0f, 1), PolishBusyDrawable.opacity(0f, 2))
    }

    @Test fun stopVisibilityAndDisabledMotionCancelAnimator() {
        var enabled = true
        val drawable = PolishBusyDrawable(1f) { enabled }
        drawable.start()
        assertTrue(drawable.isRunning())
        drawable.start()
        drawable.stop()
        assertFalse(drawable.isRunning())
        drawable.start()
        drawable.setVisible(false, false)
        assertFalse(drawable.isRunning())
        drawable.start()
        assertFalse(drawable.isRunning())
        drawable.setVisible(true, false)
        drawable.start()
        assertTrue(drawable.isRunning())
        enabled = false
        drawable.start()
        assertFalse(drawable.isRunning())
    }

    @Test fun serviceRestoresIdleOnResultCancelAndDestroyWithoutAnimatingButton() {
        val owner = Robolectric.buildService(OdictoImeService::class.java).create()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val service = owner.get()
            val root = service.onCreateInputView()
            activity.get().setContentView(root)
            val button = root.findViewById<ImageButton>(R.id.ime_polish)
            val background = button.background
            val status = OdictoImeService::class.java.getDeclaredMethod("showPolishStatus", String::class.java, Boolean::class.javaPrimitiveType)
            status.isAccessible = true
            status.invoke(service, "Polishing text", true)
            val busy = button.drawable as PolishBusyDrawable
            assertEquals("Cancel text polish", button.contentDescription)
            assertSame(background, button.background)
            assertFalse(button.isSelected)
            assertNull(button.animation)
            status.invoke(service, "Text polished", false)
            assertFalse(busy.isRunning())
            assertFalse(button.drawable is PolishBusyDrawable)
            status.invoke(service, "Polishing text", true)
            service.cancelFieldPolish()
            assertFalse(busy.isRunning())
            assertFalse(button.drawable is PolishBusyDrawable)
            status.invoke(service, "Polishing text", true)
            activity.get().setContentView(View(activity.get()))
            assertFalse(busy.isRunning())
            activity.get().setContentView(root)
            service.onWindowHidden()
            assertFalse(busy.isRunning())
            status.invoke(service, "Polishing text", true)
        } finally {
            val busy = owner.get().javaClass.getDeclaredField("polishBusyDrawable").apply { isAccessible = true }.get(owner.get()) as? PolishBusyDrawable
            owner.destroy()
            assertFalse(busy?.isRunning() == true)
            activity.pause().stop().destroy()
        }
    }
}
