package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Small electric circle: glowing blue/cyan arc segments with spark dots that
 * rotate continuously like crackling electricity. Spins slowly when idle,
 * faster and brighter while listening. Tapping it toggles the voice session
 * (the click listener is set by HomeFragment).
 */
class ElectricCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** True while a voice session is live: faster spin, brighter arcs. */
    var active: Boolean = false

    private var angle = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            angle = (angle + if (active) 17f else 6f) % 360f
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    init {
        // Software layer so the electric glow (shadow) always renders.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
    }

    fun start() {
        if (running) return
        running = true
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0f || cy <= 0f) return
        val r = min(cx, cy) * 0.70f

        // Faint base ring.
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.strokeWidth = 2.5f
        paint.color = 0x3535C4FF
        canvas.drawCircle(cx, cy, r, paint)

        // Electric glow for the arcs.
        val glowColor = if (active) 0xFF35C4FF.toInt() else 0xFF2E9BD6.toInt()
        paint.setShadowLayer(14f, 0f, 0f, glowColor)

        // Three crackling arc segments, slightly offset radii for a live feel.
        val arcColors = intArrayOf(
            0xFF9FDCFF.toInt(),
            0xFF35C4FF.toInt(),
            0xFFE6C2FF.toInt()
        )
        for (i in 0..2) {
            val rr = r + (if (i % 2 == 0) 3f else -3f)
            paint.strokeWidth = if (active) 5f else 4f
            paint.color = arcColors[i]
            val start = angle + i * 120f
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, start, 58f, false, paint)
        }

        // Bright spark dots riding at each arc's head.
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        for (i in 0..2) {
            val rr = r + (if (i % 2 == 0) 3f else -3f)
            val a = Math.toRadians((angle + i * 120f + 58f).toDouble())
            val x = cx + rr * cos(a).toFloat()
            val y = cy + rr * sin(a).toFloat()
            canvas.drawCircle(x, y, if (active) 4f else 3.2f, paint)
        }
        paint.style = Paint.Style.STROKE
    }
}
