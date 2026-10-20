package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.myra.assistant.util.Prefs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Glowing reactor orb — MYRA's voice button on the dashboard home.
 * Concentric rings rotate slowly around a pulsing core; while a voice
 * session is live ([active]) it spins faster and glows brighter.
 * Tap handling lives in HomeFragment (same toggle the old electric
 * circle used). Written fresh for MYRA.
 */
class ReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** True while a voice session is live: faster spin, brighter core. */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var angle = 0f
    private var pulse = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var coreShader: RadialGradient? = null
    private var shaderKey = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            angle = (angle + if (active) 3.2f else 1.1f) % 360f
            pulse += 0.06f
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    init {
        // Software layer so the neon glow (shadow) always renders.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        contentDescription = "Voice"
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
        val r = min(cx, cy) * 0.94f
        val cyan = 0xFF00E5FF.toInt()

        // --- Outer halo: two wide, faint rings ---
        ringPaint.setShadowLayer(0f, 0f, 0f, 0)
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.strokeWidth = r * 0.15f
        ringPaint.color = (0x12 shl 24) or (cyan and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, r * 0.85f, ringPaint)
        ringPaint.strokeWidth = r * 0.06f
        ringPaint.color = (0x20 shl 24) or (cyan and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, r * 0.88f, ringPaint)

        // --- Pulsing core (radial gradient disc) ---
        val pulseScale = 1f + 0.045f * sin(pulse.toDouble()).toFloat() * if (active) 1.6f else 1f
        val coreR = (r * 0.52f * pulseScale).coerceAtLeast(1f)
        val key = "${coreR.toInt()}:$active"
        if (key != shaderKey) {
            shaderKey = key
            val centerColor = if (active) 0xFF9FF3FF.toInt() else 0xFF57C8DE.toInt()
            coreShader = RadialGradient(
                cx, cy, coreR,
                intArrayOf(centerColor, 0xFF12819E.toInt(), 0xFF0A2A3A.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        corePaint.shader = coreShader
        canvas.drawCircle(cx, cy, coreR, corePaint)
        corePaint.shader = null

        // Core rim.
        ringPaint.strokeWidth = 2f
        ringPaint.color = if (active) 0xFFBFF3FF.toInt() else 0xFF35B6CC.toInt()
        ringPaint.setShadowLayer(10f, 0f, 0f, cyan)
        canvas.drawCircle(cx, cy, coreR, ringPaint)

        // --- Rotating segmented ring ---
        ringPaint.setShadowLayer(12f, 0f, 0f, cyan)
        ringPaint.strokeWidth = if (active) 5f else 4f
        val segR = r * 0.74f
        val segs = intArrayOf(70, 40, 110, 55)
        val segColors = intArrayOf(
            0xFF00E5FF.toInt(), 0xFF7DEFFC.toInt(),
            0xFF2E9BFF.toInt(), 0xFF00E5FF.toInt()
        )
        var start = angle
        for (i in segs.indices) {
            ringPaint.color = segColors[i]
            canvas.drawArc(cx - segR, cy - segR, cx + segR, cy + segR, start, segs[i].toFloat(), false, ringPaint)
            start += segs[i] + 22f
        }

        // --- Counter-rotating tick ring ---
        ringPaint.setShadowLayer(0f, 0f, 0f, 0)
        ringPaint.strokeWidth = 2.5f
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.color = 0xFF3C8CFF.toInt()
        val tickR = r * 0.97f
        for (i in 0 until 24) {
            val a = Math.toRadians((-angle * 0.6f + i * 15f).toDouble())
            val longTick = i % 6 == 0
            val inner = tickR - if (longTick) 12f else 6f
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()
            canvas.drawLine(
                cx + inner * cosA, cy + inner * sinA,
                cx + tickR * cosA, cy + tickR * sinA, ringPaint
            )
        }

        // --- One bright spark riding the segmented ring ---
        val sa = Math.toRadians((angle + 58f).toDouble())
        dotPaint.color = 0xFFFFFFFF.toInt()
        dotPaint.setShadowLayer(8f, 0f, 0f, cyan)
        canvas.drawCircle(
            cx + segR * cos(sa).toFloat(),
            cy + segR * sin(sa).toFloat(),
            if (active) 4f else 3.2f,
            dotPaint
        )
        dotPaint.setShadowLayer(0f, 0f, 0f, 0)
    }
}
