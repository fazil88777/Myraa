package com.myra.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Animated rainbow neon border around the whole dashboard screen: a thick
 * glowing tube whose hue cycles continuously through the rainbow, plus a
 * brighter light segment that visibly travels around the perimeter.
 * Purely decorative: never clickable/focusable, so it sits on top of
 * everything without ever blocking a touch. Written fresh for MYRA.
 *
 * IMPORTANT — the animation is a TIME-BASED redraw loop: a Choreographer
 * frame callback re-invalidates the view and every phase is computed from
 * SystemClock.uptimeMillis(). It deliberately does NOT use ValueAnimator
 * (which freezes when the system animator duration scale is 0, a common
 * OEM/battery-saver setting) and is NOT gated by the Reduced Motion pref,
 * so the border light always moves on the device.
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

    private val density = resources.displayMetrics.density
    private val tubeWidth = 3.6f * density
    private val glowWidth = 13f * density
    private val corner = 28f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()
    private val segPath = Path()
    private val gradMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderW = 0
    private var shaderH = 0

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        isClickable = false
        isFocusable = false
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
        if (width == 0 || height == 0) return
        val t = SystemClock.uptimeMillis() / 1000f

        val inset = glowWidth / 2f + 1f
        rect.set(inset, inset, width - inset, height - inset)
        path.reset()
        path.addRoundRect(rect, corner, corner, Path.Direction.CW)

        // Rainbow gradient, slowly rotating so the hue flows around the tube.
        if (shader == null || shaderW != width || shaderH != height) {
            shaderW = width
            shaderH = height
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                rainbow, null, Shader.TileMode.MIRROR
            )
        }
        gradMatrix.setRotate((t * 22f) % 360f, width / 2f, height / 2f)
        shader?.setLocalMatrix(gradMatrix)
        paint.shader = shader

        // Wide soft pass = neon glow; narrower bright pass = the neon tube.
        paint.strokeWidth = glowWidth
        paint.alpha = 58
        canvas.drawPath(path, paint)
        paint.strokeWidth = tubeWidth
        paint.alpha = 238
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.alpha = 255

        // Brighter light segment travelling around the perimeter.
        val measure = PathMeasure(path, true)
        val len = measure.length
        if (len > 0f) {
            val segLen = len * 0.11f
            val start = (t * 0.055f) % 1f * len
            val hue = ((start / len) * 360f + t * 26f) % 360f
            val segColor = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
            val coreColor = Color.HSVToColor(floatArrayOf(hue, 0.22f, 1f))

            // Glow pass of the segment.
            segPaint.color = segColor
            segPaint.alpha = 130
            segPaint.strokeWidth = glowWidth * 0.85f
            drawSegment(canvas, measure, start, segLen, len)
            // Bright core of the segment.
            segPaint.color = coreColor
            segPaint.alpha = 255
            segPaint.strokeWidth = tubeWidth + 1.6f * density
            drawSegment(canvas, measure, start, segLen, len)
            segPaint.alpha = 255
        }
    }

    /** Strokes the [start, start+segLen] slice of the border path, wrapping at the end. */
    private fun drawSegment(
        canvas: Canvas,
        measure: PathMeasure,
        start: Float,
        segLen: Float,
        total: Float
    ) {
        segPath.reset()
        val end = start + segLen
        if (end <= total) {
            measure.getSegment(start, end, segPath, true)
        } else {
            measure.getSegment(start, total, segPath, true)
            measure.getSegment(0f, end - total, segPath, false)
        }
        canvas.drawPath(segPath, segPaint)
    }
}
