package com.myra.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF7C4DFF.toInt()
    }

    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            val radius = base * (0.3f + 0.7f * p)
            paint.alpha = ((1 - p) * 160).toInt()
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    fun setActive(active: Boolean) {
        if (active) {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    phase = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animator?.cancel()
            animator = null
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
