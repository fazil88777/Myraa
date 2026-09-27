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
import kotlin.random.Random

/**
 * LIA-style VIP orb: a glowing core with rotating dashed rings,
 * and small particles that continuously rush inward and touch the core.
 */
class ParticleOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var orbColor: Int = Color.parseColor("#2DD4A8")
        set(value) {
            field = value
            corePaint.color = value
            invalidate()
        }

    /** When true, draws one static frame (no animation). */
    var reducedMotion: Boolean = false

    /** When true (voice session live), the orb pulses faster and brighter. */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private data class Particle(
        var angle: Float,
        var radius: Float,
        var speed: Float,
        var tangential: Float,
        var size: Float
    )

    private val particles = mutableListOf<Particle>()
    private var ringAngle = 0f
    private var ringAngle2 = 0f
    private var pulse = 0f

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orbColor }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = orbColor
        style = Paint.Style.STROKE
        strokeWidth = 7f
        alpha = 200
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val loop = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!reducedMotion) {
                ringAngle = (ringAngle + 2.2f) % 360f
                ringAngle2 = (ringAngle2 - 1.4f) % 360f
                pulse += if (active) 0.16f else 0.07f
                updateParticles()
                invalidate()
            }
            loop.postDelayed(this, 33)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        seedParticles()
    }

    private fun baseR(): Float = (minOf(width, height) / 2f) * 0.40f

    private fun seedParticles() {
        particles.clear()
        val r = baseR()
        if (r <= 0f) return
        val count = 46
        repeat(count) {
            particles.add(
                Particle(
                    angle = Random.nextFloat() * 360f,
                    radius = r * (1.5f + Random.nextFloat() * 0.9f),
                    speed = r * (0.012f + Random.nextFloat() * 0.022f),
                    tangential = (Random.nextFloat() - 0.5f) * 3.2f,
                    size = 3f + Random.nextFloat() * 5f
                )
            )
        }
    }

    private fun updateParticles() {
        val r = baseR()
        if (r <= 0f) return
        for (p in particles) {
            p.radius -= p.speed * (if (active) 1.6f else 1f)
            p.angle = (p.angle + p.tangential) % 360f
            if (p.radius < r * 0.95f) {
                // Reached the core: respawn at the outer edge.
                p.radius = r * (1.7f + Random.nextFloat() * 0.7f)
                p.angle = Random.nextFloat() * 360f
                p.speed = r * (0.012f + Random.nextFloat() * 0.022f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = baseR()
        if (r <= 0f) return

        val pulseScale = 1f + 0.07f * sin(pulse)

        // Outer halo
        val haloR = r * 2.1f
        particlePaint.shader = RadialGradient(
            cx, cy, haloR,
            intArrayOf(colorWithAlpha(orbColor, 46), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, haloR, particlePaint)
        particlePaint.shader = null

        // Glowing core
        val coreR = r * pulseScale
        corePaint.shader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(Color.WHITE, orbColor, colorWithAlpha(orbColor, 60)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, corePaint)
        corePaint.shader = null

        // Rotating dashed rings
        ringPaint.color = orbColor
        val ringR = r * 1.28f
        canvas.save()
        canvas.rotate(ringAngle, cx, cy)
        canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, 0f, 80f, false, ringPaint)
        canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, 180f, 55f, false, ringPaint)
        canvas.restore()
        canvas.save()
        canvas.rotate(ringAngle2, cx, cy)
        ringPaint.alpha = 120
        val ringR2 = r * 1.52f
        canvas.drawArc(cx - ringR2, cy - ringR2, cx + ringR2, cy + ringR2, 40f, 120f, false, ringPaint)
        ringPaint.alpha = 200
        canvas.restore()

        // Particles rushing into the core
        for (p in particles) {
            val rad = Math.toRadians(p.angle.toDouble())
            val x = cx + cos(rad).toFloat() * p.radius
            val y = cy + sin(rad).toFloat() * p.radius
            // Fade + shrink as they near the core, brighten just before touching it.
            val t = ((p.radius - r * 0.95f) / (r * 1.5f)).coerceIn(0f, 1f)
            val alpha = (90 + 165 * (1f - t)).toInt().coerceIn(0, 255)
            val size = p.size * (0.5f + 0.5f * t)
            particlePaint.color = orbColor
            particlePaint.alpha = alpha
            canvas.drawCircle(x, y, size, particlePaint)
        }
        particlePaint.alpha = 255
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
