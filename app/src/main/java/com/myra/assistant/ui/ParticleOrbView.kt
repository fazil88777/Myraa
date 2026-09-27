package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LIA-style dotted particle sphere: hundreds of glowing dots arranged on a
 * globe (Fibonacci lattice), slowly rotating in 3D with a soft halo.
 * Tap = talk is handled by the click listener set in HomeFragment.
 */
class ParticleOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var orbColor: Int = Color.parseColor("#2DD4A8")
        set(value) {
            field = value
            invalidate()
        }

    /** When true, draws one static frame (no animation). */
    var reducedMotion: Boolean = false

    /** When true (voice session live), the sphere spins faster and glows brighter. */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Unit-sphere point: x, y, z each in [-1, 1]. */
    private data class Dot(val x: Float, val y: Float, val z: Float)

    private val dots = mutableListOf<Dot>()
    private var angleY = 0f
    private var pulse = 0f

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val loop = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!reducedMotion) {
                angleY = (angleY + if (active) 2.6f else 1.1f) % 360f
                pulse += if (active) 0.14f else 0.06f
                invalidate()
            }
            loop.postDelayed(this, 33)
        }
    }

    init {
        seedDots()
    }

    /** Evenly spread dots over a sphere using a Fibonacci lattice. */
    private fun seedDots() {
        dots.clear()
        val n = 420
        val golden = Math.PI * (3.0 - sqrt(5.0))
        for (i in 0 until n) {
            val y = 1.0 - (i.toDouble() / (n - 1)) * 2.0
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = golden * i
            dots.add(
                Dot(
                    (cos(theta) * r).toFloat(),
                    y.toFloat(),
                    (sin(theta) * r).toFloat()
                )
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        loop.post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        loop.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.62f
        if (r <= 0f) return

        val pulseScale = 1f + 0.04f * sin(pulse)

        // Soft halo behind the sphere
        val glowR = r * 1.9f
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(colorWithAlpha(orbColor, if (active) 70 else 46), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        glowPaint.shader = null

        val radY = Math.toRadians(angleY.toDouble())
        val cosY = cos(radY).toFloat()
        val sinY = sin(radY).toFloat()
        // Slight X tilt so the spin reads as 3D.
        val tilt = 0.42f
        val cosT = cos(tilt)
        val sinT = sin(tilt)

        // Painter's order: back dots first, front dots last.
        val ordered = dots.sortedBy { d ->
            -d.x * sinY + d.z * cosY
        }
        for (d in ordered) {
            // Rotate around Y, then tilt around X.
            val x1 = d.x * cosY + d.z * sinY
            val z1 = -d.x * sinY + d.z * cosY
            val y1 = d.y * cosT - z1 * sinT
            val z2 = d.y * sinT + z1 * cosT
            val depth = ((z2 + 1f) / 2f).coerceIn(0f, 1f) // 0 = back, 1 = front
            val px = cx + x1 * r * pulseScale
            val py = cy + y1 * r * pulseScale
            val size = (1.6f + 4.2f * depth) * pulseScale
            dotPaint.color = orbColor
            dotPaint.alpha = (60 + 195 * depth).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, size, dotPaint)
        }
        dotPaint.alpha = 255
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
