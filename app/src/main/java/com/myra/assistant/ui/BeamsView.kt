package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.myra.assistant.R
import com.myra.assistant.util.Prefs
import kotlin.math.sin

/**
 * Soft light beams running from the left pills column to the reactor orb,
 * with a slow alpha "breathing" and a bright pulse travelling along each
 * beam. Positions are resolved from the sibling views at draw time, so the
 * beams stay glued to the pills and reactor at any screen size.
 * Written fresh for MYRA's dashboard home.
 */
class BeamsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pillIds = intArrayOf(
        R.id.pillMemory, R.id.pillChat, R.id.pillTools, R.id.pillSettings
    )

    private var time = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF00E5FF.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFBFF3FF.toInt()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            time += 0.03f
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    init {
        // Software layer so the beam glow (shadow) always renders.
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

    /** Center of [v] expressed in this view's coordinate space. */
    private fun centerInMe(v: View): Pair<Float, Float> {
        val mine = IntArray(2)
        getLocationOnScreen(mine)
        val theirs = IntArray(2)
        v.getLocationOnScreen(theirs)
        return Pair(
            theirs[0] - mine[0] + v.width / 2f,
            theirs[1] - mine[1] + v.height / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val root = rootView ?: return
        val reactor = root.findViewById<View>(R.id.reactorView) ?: return
        if (reactor.width == 0) return
        val rc = centerInMe(reactor)
        // Target: the reactor's left-center edge.
        val targetX = rc.first - reactor.width / 2f + 6f
        val targetY = rc.second

        val breathe = 0.5f + 0.5f * sin((time * 0.9f).toDouble()).toFloat()
        linePaint.alpha = (36 + 60 * breathe).toInt().coerceIn(0, 255)
        linePaint.setShadowLayer(6f, 0f, 0f, 0xFF00E5FF.toInt())

        pillIds.forEachIndexed { idx, id ->
            val pill = root.findViewById<View>(id) ?: return@forEachIndexed
            if (pill.width == 0) return@forEachIndexed
            val pc = centerInMe(pill)
            // Source: the pill's right-center edge.
            val sx = pc.first + pill.width / 2f - 4f
            val sy = pc.second
            canvas.drawLine(sx, sy, targetX, targetY, linePaint)

            // Bright pulse travelling pill -> reactor.
            val t = (time * 0.22f + idx * 0.27f) % 1f
            val px = sx + (targetX - sx) * t
            val py = sy + (targetY - sy) * t
            dotPaint.alpha = (210 * (1f - t * 0.5f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, 3f, dotPaint)
        }
        linePaint.setShadowLayer(0f, 0f, 0f, 0)
    }
}
