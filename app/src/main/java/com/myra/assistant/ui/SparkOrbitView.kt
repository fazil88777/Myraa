package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Short dash segments orbiting the MYRA header, matching the approved
 * reference: small rounded line pieces (cyan / blue / white) at varying
 * radii and angles, drifting slowly around the title on elliptical paths,
 * each tangent to its orbit and twinkling gently.
 * Written fresh for MYRA's dashboard home.
 *
 * Animation is a TIME-BASED Choreographer loop with phases computed from
 * SystemClock.uptimeMillis() — no ValueAnimator and no Reduced Motion
 * gate, so the dashes always move on the device.
 */
class SparkOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Dash(
        val speedDeg: Float,
        val phase: Float,
        val radiusF: Float,
        val lenDp: Float,
        val widthDp: Float,
        val color: Int,
        val twinkleSpeed: Float,
        val twinklePhase: Float
    )

    private val dashColors = intArrayOf(
        0xFF00E5FF.toInt(), 0xFF7DEFFC.toInt(), 0xFFFFFFFF.toInt(),
        0xFF2E9BFF.toInt(), 0xFF3C8CFF.toInt()
    )

    private val dashes: List<Dash> = List(11) { i ->
        Dash(
            speedDeg = 5f + (i % 4) * 1.8f,
            phase = i * 2.39996f, // golden-angle spread
            radiusF = 0.52f + (i % 4) * 0.16f,
            lenDp = 9f + (i % 3) * 4f,
            widthDp = 2.4f + (i % 2) * 0.5f,
            color = dashColors[i % dashColors.size],
            twinkleSpeed = 1.1f + (i % 4) * 0.45f,
            twinklePhase = i * 1.13f
        )
    }

    private val density = resources.displayMetrics.density
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        // Software layer so the dash glow (shadow) always renders.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!running) {
            running = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0f || cy <= 0f) return
        val t = SystemClock.uptimeMillis() / 1000f
        val rx = width * 0.465f
        val ry = height * 0.46f
        for (d in dashes) {
            val a = (d.phase + t * d.speedDeg * (Math.PI.toFloat() / 180f)).toDouble()
            val ex = rx * d.radiusF
            val ey = ry * d.radiusF
            val x = cx + (ex * cos(a)).toFloat()
            val y = cy + (ey * sin(a)).toFloat()
            // Tangent direction of the ellipse at angle a.
            var tx = (-ex * sin(a)).toFloat()
            var ty = (ey * cos(a)).toFloat()
            val norm = sqrt(tx * tx + ty * ty)
            if (norm <= 0f) continue
            tx /= norm
            ty /= norm
            val half = d.lenDp * density / 2f
            val tw = 0.5f + 0.5f * sin((t * d.twinkleSpeed + d.twinklePhase).toDouble()).toFloat()
            paint.color = d.color
            paint.alpha = (130 + 120 * tw).toInt().coerceIn(0, 255)
            paint.strokeWidth = d.widthDp * density
            paint.setShadowLayer(7f, 0f, 0f, d.color)
            canvas.drawLine(x - tx * half, y - ty * half, x + tx * half, y + ty * half, paint)
        }
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.alpha = 255
    }
}
