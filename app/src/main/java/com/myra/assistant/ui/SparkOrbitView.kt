package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.myra.assistant.util.Prefs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny sparks orbiting the MYRA header on slow elliptical paths, each with
 * its own twinkle. Written fresh for MYRA's dashboard home (structure
 * inspired by the reference layout Fazil approved).
 */
class SparkOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Spark(
        val speed: Float,
        val phase: Float,
        val radiusF: Float,
        val size: Float,
        val color: Int,
        val twinkle: Float
    )

    private val sparkColors = intArrayOf(
        0xFF00E5FF.toInt(), 0xFF7DEFFC.toInt(),
        0xFFFFFFFF.toInt(), 0xFF2E9BFF.toInt()
    )

    private val sparks: List<Spark> = List(16) { i ->
        Spark(
            speed = 0.10f + (i % 5) * 0.03f,
            phase = i * 2.39996f, // golden-angle spread
            radiusF = 0.55f + (i % 4) * 0.13f,
            size = 1.6f + (i % 3) * 0.9f,
            color = sparkColors[i % sparkColors.size],
            twinkle = 1.5f + (i % 4) * 0.7f
        )
    }

    private var time = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            time += 0.05f
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    init {
        // Software layer so the spark glow (shadow) always renders.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!Prefs.reducedMotion && !running) {
            running = true
            handler.post(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0f || cy <= 0f) return
        val rx = width * 0.46f
        val ry = height * 0.42f
        for (s in sparks) {
            val a = (s.phase + time * s.speed).toDouble()
            val x = cx + (rx * s.radiusF * cos(a)).toFloat()
            val y = cy + (ry * s.radiusF * sin(a)).toFloat()
            val tw = 0.45f + 0.55f * (0.5f + 0.5f * sin((time * s.twinkle + s.phase).toDouble())).toFloat()
            paint.color = s.color
            paint.alpha = (255 * tw).toInt().coerceIn(0, 255)
            paint.setShadowLayer(8f, 0f, 0f, s.color)
            canvas.drawCircle(x, y, s.size, paint)
        }
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.alpha = 255
    }
}
