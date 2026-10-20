package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.myra.assistant.util.Prefs

/**
 * Animated rainbow neon border around the whole dashboard screen.
 * Purely decorative: never clickable/focusable, so it sits on top of
 * everything without ever blocking a touch. Written fresh for MYRA.
 */
class NeonBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rainbow = intArrayOf(
        0xFFFF004C.toInt(), 0xFFFF8A00.toInt(), 0xFFFFE600.toInt(),
        0xFF00E676.toInt(), 0xFF00E5FF.toInt(), 0xFF2979FF.toInt(),
        0xFFAA00FF.toInt(), 0xFFFF004C.toInt()
    )

    private var angle = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val gradMatrix = Matrix()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            angle = (angle + 0.8f) % 360f
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    init {
        isClickable = false
        isFocusable = false
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
        if (width == 0 || height == 0) return
        val inset = 5f
        rect.set(inset, inset, width - inset, height - inset)

        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            rainbow, null, Shader.TileMode.MIRROR
        )
        gradMatrix.setRotate(angle, width / 2f, height / 2f)
        shader.setLocalMatrix(gradMatrix)
        paint.shader = shader

        // Wide faint pass = neon glow; thin bright pass = the neon tube.
        paint.strokeWidth = 9f
        paint.alpha = 46
        canvas.drawRoundRect(rect, 26f, 26f, paint)
        paint.strokeWidth = 3f
        paint.alpha = 235
        canvas.drawRoundRect(rect, 26f, 26f, paint)

        paint.shader = null
        paint.alpha = 255
    }
}
