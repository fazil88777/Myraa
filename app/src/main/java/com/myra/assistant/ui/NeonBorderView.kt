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
 * Animated rainbow neon border: a thick glowing tube whose hue cycles
 * continuously through the rainbow, plus a brighter light segment that
 * visibly travels around the perimeter. Purely decorative: never
 * clickable/focusable, so it sits on top of everything without ever
 * blocking a touch. Written fresh for MYRA.
 *
 * Two visual states, driven by [live] (Fazil's spec):
 *  - OFF (session idle): dim (alpha ~0.45), thinner tube + glow, slow
 *    hue cycle (~30s per revolution), travelling segment slow + subtle.
 *  - LIVE (session connected): full brightness, tube + glow ~1.4x
 *    thicker, fast hue cycle (~8s per revolution), travelling segment
 *    clearly faster and brighter.
 * The transition between states is smoothed over ~half a second.
 *
 * IMPORTANT — the animation is a TIME-BASED redraw loop: a Choreographer
 * frame callback re-invalidates the view and every phase is accumulated
 * from SystemClock.uptimeMillis() deltas. It deliberately does NOT use
 * ValueAnimator (which freezes when the system animator duration scale
 * is 0, a common OEM/battery-saver setting) and is NOT gated by the
 * Reduced Motion pref, so the border light always moves on the device.
 */
class NeonBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** True while a voice session is live: bright, thick, fast border. */
    var live: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val rainbow = intArrayOf(
        0xFFFF004C.toInt(), 0xFFFF8A00.toInt(), 0xFFFFE600.toInt(),
        0xFF00E676.toInt(), 0xFF00E5FF.toInt(), 0xFF2979FF.toInt(),
        0xFFAA00FF.toInt(), 0xFFFF004C.toInt()
    )

    private val density = resources.displayMetrics.density
    private val baseTubeWidth = 3.6f * density
    private val baseGlowWidth = 13f * density
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

    // Animation phases, accumulated per frame so the speeds can change
    // smoothly when the live state flips.
    private var liveMix = 0f           // 0 = dim/off, 1 = live
    private var huePhaseDeg = 0f       // gradient rotation, degrees
    private var segmentPhase = 0f      // travelling segment, 0..1 of perimeter
    private var lastFrameMs = 0L

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
        lastFrameMs = 0L
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

    private fun lerp(a: Float, b: Float, m: Float): Float = a + (b - a) * m

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // --- advance phases by the real frame delta ---
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0.016f
        else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        val target = if (live) 1f else 0f
        liveMix += (target - liveMix) * (dt * 4f).coerceIn(0f, 1f)
        if (kotlin.math.abs(target - liveMix) < 0.01f) liveMix = target
        val m = liveMix

        val huePeriodSec = lerp(30f, 8f, m)          // slow off, fast live
        huePhaseDeg = (huePhaseDeg + dt * 360f / huePeriodSec) % 360f
        val segmentPeriodSec = lerp(30f, 6f, m)      // segment loop time
        segmentPhase = (segmentPhase + dt / segmentPeriodSec) % 1f

        val thickness = lerp(0.72f, 1.4f, m)
        val tubeWidth = baseTubeWidth * thickness
        val glowWidth = baseGlowWidth * thickness
        val glowAlpha = lerp(26f, 58f, m).toInt()
        val tubeAlpha = lerp(107f, 238f, m).toInt()

        val inset = glowWidth / 2f + 1f
        rect.set(inset, inset, width - inset, height - inset)
        path.reset()
        path.addRoundRect(rect, corner, corner, Path.Direction.CW)

        // Rainbow gradient, rotating so the hue flows around the tube.
        if (shader == null || shaderW != width || shaderH != height) {
            shaderW = width
            shaderH = height
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                rainbow, null, Shader.TileMode.MIRROR
            )
        }
        gradMatrix.setRotate(huePhaseDeg, width / 2f, height / 2f)
        shader?.setLocalMatrix(gradMatrix)
        paint.shader = shader

        // Wide soft pass = neon glow; narrower bright pass = the neon tube.
        paint.strokeWidth = glowWidth
        paint.alpha = glowAlpha
        canvas.drawPath(path, paint)
        paint.strokeWidth = tubeWidth
        paint.alpha = tubeAlpha
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.alpha = 255

        // Brighter light segment travelling around the perimeter.
        val measure = PathMeasure(path, true)
        val len = measure.length
        if (len > 0f) {
            val segLen = len * 0.11f
            val start = segmentPhase * len
            val t = now / 1000f
            val hue = ((start / len) * 360f + t * 26f) % 360f
            val segColor = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
            val coreColor = Color.HSVToColor(floatArrayOf(hue, 0.22f, 1f))

            // Glow pass of the segment.
            segPaint.color = segColor
            segPaint.alpha = lerp(58f, 130f, m).toInt()
            segPaint.strokeWidth = glowWidth * 0.85f
            drawSegment(canvas, measure, start, segLen, len)
            // Bright core of the segment.
            segPaint.color = coreColor
            segPaint.alpha = lerp(115f, 255f, m).toInt()
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
