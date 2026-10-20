package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.myra.assistant.R
import kotlin.math.sin

/**
 * Light beams on MYRA's dashboard home, matching the approved reference:
 * STRAIGHT thin lines running from each pill's right edge and converging
 * at the reactor CENTER, each line in its own pill's dot color (MEMORY
 * blue, CHAT orange, SOUL grey, SETTINGS green), with a soft glow pass and
 * a subtle alpha breathing. Positions are resolved from the sibling views
 * at draw time, so the beams stay glued at any screen size.
 * Written fresh for MYRA.
 *
 * Animation is a TIME-BASED Choreographer loop with phases computed from
 * SystemClock.uptimeMillis() — no ValueAnimator and no Reduced Motion
 * gate, so the breathing always runs on the device.
 */
class BeamsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pillIds = intArrayOf(
        R.id.pillMemory, R.id.pillChat, R.id.pillSoul, R.id.pillSettings
    )
    private val pillColors = intArrayOf(
        0xFF3C8CFF.toInt(), 0xFFFF9632.toInt(),
        0xFFAAB4BE.toInt(), 0xFF50DC82.toInt()
    )

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

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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
        val t = SystemClock.uptimeMillis() / 1000f
        // All beams converge at the reactor CENTER.
        val rc = centerInMe(reactor)
        val targetX = rc.first
        val targetY = rc.second

        pillIds.forEachIndexed { idx, id ->
            val pill = root.findViewById<View>(id) ?: return@forEachIndexed
            if (pill.width == 0) return@forEachIndexed
            val pc = centerInMe(pill)
            // Source: the pill's right-center edge.
            val sx = pc.first + pill.width / 2f - 2f
            val sy = pc.second

            val breathe = 0.78f + 0.22f * sin((t * 1.2f + idx * 0.9f).toDouble()).toFloat()
            val color = pillColors[idx]

            // Soft glow pass, then the crisp thin line.
            glowPaint.color = color
            glowPaint.strokeWidth = 6f * density
            glowPaint.alpha = (52 * breathe).toInt().coerceIn(0, 255)
            canvas.drawLine(sx, sy, targetX, targetY, glowPaint)

            linePaint.color = color
            linePaint.strokeWidth = 2f * density
            linePaint.alpha = (195 * breathe).toInt().coerceIn(0, 255)
            canvas.drawLine(sx, sy, targetX, targetY, linePaint)
        }
        glowPaint.alpha = 255
        linePaint.alpha = 255
    }
}
