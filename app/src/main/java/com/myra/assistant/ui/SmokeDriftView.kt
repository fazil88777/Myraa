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
import kotlin.math.sin
import kotlin.random.Random

/**
 * Slow ambient smoke overlay for the MYRA home screen.
 *
 * NOTE: the home art is a single flat image, so hair/eyes can't move
 * independently (no video available on-device). This procedural drifting
 * smoke + the gentle "breathing" scale on the art (see HomeFragment) give
 * the screen an alive feel instead.
 */
class SmokeDriftView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Smoke tint, set per home-design theme (purple default). */
    var smokeColor: Int = 0xFF9B5CF6.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private data class Blob(
        var x: Float,
        var y: Float,
        var r: Float,
        var speed: Float,
        var phase: Float,
        var alpha: Float
    )

    private val blobs = mutableListOf<Blob>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var t = 0f

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            t += 0.05f
            for (b in blobs) {
                b.y -= b.speed
                if (b.y + b.r < 0) {
                    b.y = height + b.r
                    b.x = Random.nextFloat() * width
                }
            }
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        blobs.clear()
        if (w <= 0 || h <= 0) return
        repeat(7) {
            blobs.add(
                Blob(
                    x = Random.nextFloat() * w,
                    y = Random.nextFloat() * h,
                    r = w * (0.18f + Random.nextFloat() * 0.22f),
                    speed = 0.6f + Random.nextFloat() * 1.1f,
                    phase = Random.nextFloat() * 6.28f,
                    alpha = 0.10f + Random.nextFloat() * 0.10f
                )
            )
        }
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
        if (blobs.isEmpty()) return
        val baseR = (smokeColor shr 16) and 0xFF
        val baseG = (smokeColor shr 8) and 0xFF
        val baseB = smokeColor and 0xFF
        for (b in blobs) {
            val wobble = (0.7 + 0.3 * sin((t + b.phase).toDouble())).toFloat()
            val a = (b.alpha * wobble * 255).toInt().coerceIn(0, 255)
            val col = (a shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
            paint.shader = RadialGradient(b.x, b.y, b.r, col, 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawCircle(b.x, b.y, b.r, paint)
        }
        paint.shader = null
    }
}
