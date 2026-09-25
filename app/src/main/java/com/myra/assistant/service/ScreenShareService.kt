package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import java.io.ByteArrayOutputStream

/**
 * Captures the phone screen (~1 frame/sec) and hands JPEG frames to
 * [onFrame], which forwards them to Gemini so MYRA can see the screen.
 * Started only after the user grants the one-time system screen-capture
 * permission from MainActivity.
 */
class ScreenShareService : Service() {

    companion object {
        /** Set by MainViewModel: receives base64 JPEG frames. */
        var onFrame: ((String) -> Unit)? = null

        /** True while the service is actively capturing the screen. */
        @Volatile
        var isSharing = false
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastSentAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "myra_share"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Screen Share",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MYRA screen share")
            .setContentText("MYRA tumhari screen dekh rahi hai")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            isSharing = true
            startCapture()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        thread = HandlerThread("myra-share").apply { start() }
        val handler = Handler(thread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "myra-share",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = SystemClock.uptimeMillis()
            // Throttle to 1 frame every 2 seconds: heavy HD frames every 800ms
            // were choking the WebSocket (socket died after a few minutes of
            // screen share -> MYRA went silent). 0.5 fps is plenty for seeing
            // the screen and keeps the connection alive.
            if (now - lastSentAt < 2000) {
                // Drop extra frames, don't queue them
                try {
                    reader.acquireLatestImage()?.close()
                } catch (_: Exception) {
                }
                return@setOnImageAvailableListener
            }
            lastSentAt = now
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                var bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                bmp = Bitmap.createBitmap(bmp, 0, 0, width, height)
                // Lighter frames: 768px wide, quality 60 — still clear enough for
                // MYRA to read the screen, but ~3x smaller than 1024px/q75 so the
                // WebSocket never chokes during screen share.
                val sw = 768
                val sh = (height * (768f / width)).toInt()
                val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                bmp.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
                scaled.recycle()
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                try {
                    onFrame?.invoke(b64)
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {
                }
            }
        }, handler)
    }

    override fun onDestroy() {
        isSharing = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        thread = null
        super.onDestroy()
    }
}
