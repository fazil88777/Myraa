package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Dark LIA-style background: near-black fill with a subtle grid.
 */
class GridBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Grid line color; themes can tint it. */
    var gridColor: Int = Color.parseColor("#0E222C")

    /** Background fill color. */
    var bgColor: Int = Color.parseColor("#05080D")

    private val bgPaint = Paint().apply { color = bgColor }
    private val linePaint = Paint().apply {
        color = gridColor
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = bgColor
        linePaint.color = gridColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val step = (56 * resources.displayMetrics.density)
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            y += step
        }
    }
}
