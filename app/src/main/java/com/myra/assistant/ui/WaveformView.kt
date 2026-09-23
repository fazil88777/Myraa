package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF03DAC5.toInt()
    }

    private val amplitudes = ArrayDeque<Float>()

    fun pushAmplitude(a: Float) {
        amplitudes.addLast(a)
        while (amplitudes.size > 40) {
            amplitudes.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty()) return
        val n = amplitudes.size
        val barWidth = width / 40f
        val centerY = height / 2f
        amplitudes.forEachIndexed { index, amp ->
            val barHeight = (amp.coerceIn(0f, 1f) * height * 0.9f)
            val left = index * barWidth + barWidth * 0.2f
            val right = (index + 1) * barWidth - barWidth * 0.2f
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}
