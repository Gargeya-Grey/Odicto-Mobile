package app.odicto.mobile.ime

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

internal class PolishBusyDrawable(
    density: Float,
    private val motionEnabled: () -> Boolean,
) : Drawable(), Animatable {
    private val size = (24 * density).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC5B3FF.toInt()
        style = Paint.Style.FILL
    }
    private val stars = arrayOf(
        star(12f, 10f, 8f, 1.8f),
        star(19f, 20f, 3f, 0.7f),
        star(4f, 17f, 2f, 0.5f),
    )
    private var animator: ValueAnimator? = null
    private var phase = 0f
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        canvas.translate(bounds.exactCenterX() - 12 * scale, bounds.exactCenterY() - 12 * scale)
        canvas.scale(scale, scale)
        stars.forEachIndexed { index, path ->
            paint.alpha = (drawableAlpha * if (isRunning()) opacity(phase, index) else 1f).roundToInt()
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(saved)
    }

    override fun start() {
        if (!motionEnabled()) {
            stop()
            return
        }
        if (isRunning() || !isVisible) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!motionEnabled()) stop()
                else {
                    phase = it.animatedValue as Float
                    invalidateSelf()
                }
            }
            start()
        }
    }

    override fun stop() {
        val previous = animator
        animator = null
        previous?.removeAllUpdateListeners()
        previous?.cancel()
        phase = 0f
        invalidateSelf()
    }

    override fun isRunning() = animator?.isRunning == true

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) stop()
        return super.setVisible(visible, restart)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha() = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    companion object {
        internal fun opacity(phase: Float, index: Int): Float {
            val wave = (1 - cos(2 * PI * (phase - index * 0.14f))) / 2
            return (1 - 0.42 * wave).toFloat()
        }

        private fun star(x: Float, y: Float, radius: Float, shoulder: Float) = Path().apply {
            moveTo(x, y - radius)
            lineTo(x + shoulder, y - shoulder)
            lineTo(x + radius, y)
            lineTo(x + shoulder, y + shoulder)
            lineTo(x, y + radius)
            lineTo(x - shoulder, y + shoulder)
            lineTo(x - radius, y)
            lineTo(x - shoulder, y - shoulder)
            close()
        }
    }
}
