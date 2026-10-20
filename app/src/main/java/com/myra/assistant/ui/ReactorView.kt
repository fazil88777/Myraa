package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Glowing reactor orb — MYRA's voice button on the dashboard home, rebuilt
 * to match the approved reference: a bright cyan segmented outer ring
 * (three thick glowing arc segments with gaps), a fine tick ring, thin
 * inner rings, and a bright white-cyan plasma core with sparkles inside.
 * While a voice session is live ([active]) it spins faster and brighter.
 * Tap handling lives in HomeFragment (MainActivity.toggleVoiceSession()).
 * Written fresh for MYRA.
 *
 * Animation is a TIME-BASED Choreographer loop with phases computed from
 * SystemClock.uptimeMillis() — no ValueAnimator (freezes when the system
 * animator scale is 0) and no Reduced Motion gate, so it always moves.
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

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcRect = RectF()

    private var haloShader: RadialGradient? = null
    private var coreShader: RadialGradient? = null
    private var glowShader: RadialGradient? = null
    private var shaderKey = ""

    // Core sparkles: [angleFrac, radiusFrac, sizeUnits, twinklePhase, twinkleSpeed].
    private val sparkles: List<FloatArray> = List(16) { i ->
        floatArrayOf(
            (i * 0.6180339f) % 1f,
            0.15f + ((i * 37) % 68) / 100f,
            0.8f + ((i * 13) % 10) / 6f,
            i * 1.7f,
            1.2f + ((i * 7) % 10) / 5f
        )
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
        val r = min(cx, cy) * 0.96f
        val t = SystemClock.uptimeMillis() / 1000f
        val speedMul = if (active) 2.1f else 1f
        val rot = (t * 13f * speedMul) % 360f
        val rotSlow = (t * 5f) % 360f
        val pulse = sin((t * if (active) 2.4f else 1.5f).toDouble()).toFloat()
        val cyan = 0xFF00E5FF.toInt()
        val boost = if (active) 1.3f else 1f

        val coreR = r * 0.40f * (1f + 0.028f * pulse * if (active) 1.5f else 1f)
        val key = "${(r * 2).toInt()}:${coreR.toInt()}"
        if (key != shaderKey) {
            shaderKey = key
            haloShader = RadialGradient(
                cx, cy, r,
                intArrayOf(0x2E00E5FF.toInt(), 0x0000E5FF.toInt()),
                floatArrayOf(0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            glowShader = RadialGradient(
                cx, cy, coreR * 1.55f,
                intArrayOf(0x5900E5FF.toInt(), 0x0000E5FF.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            coreShader = RadialGradient(
                cx, cy, coreR,
                intArrayOf(
                    0xFFFFFFFF.toInt(), 0xFFD9FAFF.toInt(), 0xFF7FE9FF.toInt(),
                    0xFF18B9DC.toInt(), 0xFF0A5E78.toInt()
                ),
                floatArrayOf(0f, 0.32f, 0.6f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        // --- Ambient halo ---
        fillPaint.shader = haloShader
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // --- Faint full outer ring for definition ---
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 1.5f * density
        strokePaint.color = cyan
        strokePaint.alpha = 70
        canvas.drawCircle(cx, cy, r * 0.975f, strokePaint)

        // --- Segmented outer ring: 3 thick glowing arc segments + gaps ---
        val segR = r * 0.86f
        val bandW = r * 0.135f
        val sweeps = floatArrayOf(102f, 98f, 94f)
        val gap = 22f
        arcRect.set(cx - segR, cy - segR, cx + segR, cy + segR)

        // Soft under-glow for the whole band.
        strokePaint.strokeWidth = bandW * 1.9f
        strokePaint.color = cyan
        strokePaint.alpha = (36 * boost).toInt().coerceIn(0, 255)
        var s0 = -90f + rot
        for (sweep in sweeps) {
            canvas.drawArc(arcRect, s0, sweep, false, strokePaint)
            s0 += sweep + gap
        }

        // Main bright band with shadow glow + a thin detail arc inside it.
        strokePaint.setShadowLayer(15f, 0f, 0f, cyan)
        strokePaint.strokeWidth = bandW
        strokePaint.color = cyan
        strokePaint.alpha = (245 * boost).toInt().coerceIn(0, 255)
        val detailR = segR - bandW * 0.88f
        val detailRect = RectF(cx - detailR, cy - detailR, cx + detailR, cy + detailR)
        s0 = -90f + rot
        for (sweep in sweeps) {
            canvas.drawArc(arcRect, s0, sweep, false, strokePaint)
            s0 += sweep + gap
        }
        strokePaint.strokeWidth = bandW * 0.22f
        strokePaint.color = 0xFF9FF3FF.toInt()
        strokePaint.alpha = (190 * boost).toInt().coerceIn(0, 255)
        s0 = -90f + rot
        for (sweep in sweeps) {
            canvas.drawArc(detailRect, s0, sweep, false, strokePaint)
            s0 += sweep + gap
        }

        // Short radial ticks across the band at each segment end.
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)
        strokePaint.strokeWidth = 2f * density
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.color = 0xFFBFF3FF.toInt()
        strokePaint.alpha = 200
        s0 = -90f + rot
        for (sweep in sweeps) {
            for (aDeg in floatArrayOf(s0, s0 + sweep)) {
                val a = Math.toRadians(aDeg.toDouble())
                val cosA = cos(a).toFloat()
                val sinA = sin(a).toFloat()
                canvas.drawLine(
                    cx + (segR - bandW * 0.78f) * cosA, cy + (segR - bandW * 0.78f) * sinA,
                    cx + (segR + bandW * 0.78f) * cosA, cy + (segR + bandW * 0.78f) * sinA,
                    strokePaint
                )
            }
            s0 += sweep + gap
        }

        // --- Fine tick ring (counter-rotating, 72 ticks) ---
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.color = 0xFF6FD8EE.toInt()
        val tickR = r * 0.695f
        strokePaint.strokeWidth = maxOf(1.3f, r * 0.012f)
        for (i in 0 until 72) {
            val aDeg = -rotSlow * 0.8f + i * 5f
            val a = Math.toRadians(aDeg.toDouble())
            val longTick = i % 6 == 0
            val inner = tickR - if (longTick) r * 0.05f else r * 0.028f
            strokePaint.alpha = if (longTick) 200 else 150
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()
            canvas.drawLine(
                cx + inner * cosA, cy + inner * sinA,
                cx + tickR * cosA, cy + tickR * sinA, strokePaint
            )
        }

        // --- Thin inner rings ---
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = cyan
        strokePaint.strokeWidth = r * 0.02f
        strokePaint.alpha = 120
        canvas.drawCircle(cx, cy, r * 0.575f, strokePaint)
        strokePaint.strokeWidth = r * 0.013f
        strokePaint.alpha = 85
        canvas.drawCircle(cx, cy, r * 0.465f, strokePaint)

        // --- Plasma core: glow disc, gradient body, filaments, sparkles ---
        fillPaint.shader = glowShader
        canvas.drawCircle(cx, cy, coreR * 1.55f, fillPaint)
        fillPaint.shader = coreShader
        canvas.drawCircle(cx, cy, coreR, fillPaint)
        fillPaint.shader = null

        // Energy filaments swirling inside the core.
        strokePaint.setShadowLayer(6f, 0f, 0f, cyan)
        strokePaint.strokeWidth = 2f
        strokePaint.color = 0xFFFFFFFF.toInt()
        for (i in 0 until 6) {
            val fr = coreR * (0.30f + 0.13f * ((i * 29) % 10) / 10f)
            arcRect.set(cx - fr, cy - fr, cx + fr, cy + fr)
            strokePaint.alpha = (110 + 60 * sin((t * 2f + i * 1.3f).toDouble())).toInt().coerceIn(0, 255)
            canvas.drawArc(arcRect, -rot * 1.6f + i * 60f + t * 9f, 46f, false, strokePaint)
        }
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)

        // Sparkles twinkling inside the core.
        for (sp in sparkles) {
            val ang = (sp[0] * 360f + t * 9f).toDouble() * Math.PI / 180.0
            val rad = sp[1] * coreR * 0.92f
            val x = cx + (rad * cos(ang)).toFloat()
            val y = cy + (rad * sin(ang)).toFloat()
            val tw = 0.5f + 0.5f * sin((t * sp[4] + sp[3]).toDouble()).toFloat()
            fillPaint.color = 0xFFFFFFFF.toInt()
            fillPaint.alpha = (70 + 170 * tw * boost).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, sp[2] * density * 0.9f, fillPaint)
        }

        // Core rim.
        strokePaint.setShadowLayer(10f, 0f, 0f, cyan)
        strokePaint.strokeWidth = 1.6f * density
        strokePaint.color = 0xFFCFF7FF.toInt()
        strokePaint.alpha = (205 * boost).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, coreR, strokePaint)
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)

        strokePaint.alpha = 255
        fillPaint.alpha = 255
    }
}
