package app.odicto.mobile.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.Button

/** Font-independent, optically centered keyboard symbols. */
open class EditingKeyButton(context: Context, private val symbol: String) : Button(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.color = if (isEnabled) 0xFFF3EFFB.toInt() else 0xFF77717F.toInt()
        val size = 24f * resources.displayMetrics.density
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        if (symbol == "backspace") {
            val outline = Path().apply {
                moveTo(8f, 5f); lineTo(22f, 5f); lineTo(22f, 19f)
                lineTo(8f, 19f); lineTo(2f, 12f); close()
            }
            canvas.drawPath(outline, ink)
            canvas.drawLine(11f, 9f, 17f, 15f, ink)
            canvas.drawLine(17f, 9f, 11f, 15f, ink)
        } else {
            val arrow = Path().apply {
                moveTo(19f, 5f); lineTo(19f, 14f); lineTo(5f, 14f)
                moveTo(10f, 9f); lineTo(5f, 14f); lineTo(10f, 19f)
            }
            canvas.drawPath(arrow, ink)
        }
        canvas.restore()
    }
}
