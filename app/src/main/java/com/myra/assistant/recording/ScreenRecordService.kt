package com.myra.assistant.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app HD screen + mic recording (Fazil's request):
 *
 * • MediaProjection + MediaRecorder capture the whole screen as MP4/H.264
 *   (long edge capped at 1080, aspect kept, 30 fps) with the MIC as the
 *   audio source — so the recording carries his voice AND MYRA's voice
 *   (she is heard through the speaker) in one stream.
 * • Runs as a mediaProjection foreground service with its own
 *   notification ("MYRA Recording") carrying a STOP action.
 * • While recording, a small floating badge (red dot + elapsed timer,
 *   top-center) is shown when the draw-over-apps permission exists;
 *   tapping the badge stops the recording. Without the permission the
 *   recording still runs — the notification STOP stays available.
 * • On stop, the MP4 is exported to the public gallery:
 *   Movies/Maira_Recordings via MediaStore (API 29+) or the legacy
 *   public dir + media scan (API 26–28), then a toast confirms.
 *
 * Mic coexistence: the recorder uses [MediaRecorder.AudioSource.MIC]
 * and never touches the live-session audio code. If the mic is busy and
 * prepare/start fails, the service toasts honestly, cleans up and stops
 * itself — it never crashes the app.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecord"
        private const val CHANNEL_ID = "myra_recording"
        private const val NOTIF_ID = 3

        const val ACTION_START = "com.myra.assistant.recording.START"
        const val ACTION_STOP = "com.myra.assistant.recording.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        /** True while a recording is actively running. */
        @Volatile
        var isRecording = false

        /** elapsedRealtime() stamp of the current recording start. */
        @Volatile
        var startedAtMs = 0L

        /**
         * Set by HomeFragment while it is visible: called (on the main
         * thread) whenever recording state or the elapsed time changes.
         */
        @Volatile
        var stateListener: ((Boolean, Long) -> Unit)? = null

        fun elapsedMs(): Long =
            if (isRecording && startedAtMs > 0) SystemClock.elapsedRealtime() - startedAtMs else 0L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var outputFile: File? = null

    private var badgeView: View? = null
    private var badgeTimer: TextView? = null
    private var badgeDot: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "myra-record-stop").apply { isDaemon = true }
    }

    @Volatile
    private var stopping = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            updateBadgeTimer()
            notifyState()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System revoked the projection (or our own stop is running):
            // finalize whatever we have through the normal stop path.
            if (!stopping && isRecording) {
                worker.execute { stopRecording() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                worker.execute { stopRecording() }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (isRecording) return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == 0 || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording(resultCode, data)
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        createChannel()
        // Foreground FIRST (mediaProjection type) — required before the
        // projection/virtual display can be used on modern Android.
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }

        var rec: MediaRecorder? = null
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj
            // Android 14: a callback MUST be registered before
            // createVirtualDisplay, or it throws.
            proj.registerCallback(projectionCallback, mainHandler)

            // Display size: real metrics, long edge capped at 1080.
            val metrics = resources.displayMetrics
            var w = metrics.widthPixels
            var h = metrics.heightPixels
            val longEdge = maxOf(w, h)
            if (longEdge > 1080) {
                val scale = 1080f / longEdge
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            // H.264 encoders want even dimensions.
            w = w / 2 * 2
            h = h / 2 * 2
            val dpi = metrics.densityDpi

            val dir = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir,
                "recordings"
            )
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "recording_tmp.mp4")
            if (out.exists()) out.delete()
            outputFile = out

            rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = rec
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            val bitRate = (w * h * 30 * 0.15).toInt().coerceIn(4_000_000, 12_000_000)
            rec.setVideoEncodingBitRate(bitRate)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioChannels(1)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()

            virtualDisplay = proj.createVirtualDisplay(
                "myra-record",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
            recorderStarted = true
        } catch (e: Exception) {
            // Most likely cause: the mic is busy (live session) or the
            // encoder rejected the config. Fail honestly, never crash.
            Log.e(TAG, "recording start failed", e)
            toastMain("Recording start nahi ho saki — mic busy hai")
            cleanupAfterFailedStart(rec)
            stopSelf()
            return
        }

        isRecording = true
        stopping = false
        startedAtMs = SystemClock.elapsedRealtime()
        showBadge()
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
        notifyState()
        toastMain("Recording shuru ho gayi")
    }

    private fun cleanupAfterFailedStart(rec: MediaRecorder?) {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            rec?.reset()
        } catch (_: Exception) {
        }
        try {
            rec?.release()
        } catch (_: Exception) {
        }
        if (recorder === rec) recorder = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        try {
            outputFile?.delete()
        } catch (_: Exception) {
        }
        outputFile = null
    }

    /** Stop in the right order, export to the gallery, then stop self. */
    private fun stopRecording() {
        if (stopping) return
        stopping = true

        val rec = recorder
        val wasStarted = recorderStarted
        recorderStarted = false
        var stopOk = false
        if (rec != null && wasStarted) {
            try {
                rec.stop()
                stopOk = true
            } catch (e: Exception) {
                // Too-short recording etc. — file is unusable.
                Log.w(TAG, "recorder stop failed", e)
            }
        }
        try {
            rec?.reset()
        } catch (_: Exception) {
        }
        try {
            rec?.release()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        mainHandler.removeCallbacks(tickRunnable)
        removeBadge()
        isRecording = false
        notifyState()

        val file = outputFile
        outputFile = null
        if (file != null && stopOk && file.exists() && file.length() > 0) {
            exportToGallery(file)
        } else if (file != null) {
            Log.w(TAG, "recording discarded (stopOk=$stopOk, exists=${file.exists()})")
            toastMain("Recording save nahi ho saki")
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        // Last-resort cleanup if the service is killed mid-recording.
        if (isRecording && !stopping) {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        mainHandler.removeCallbacks(tickRunnable)
        removeBadge()
        isRecording = false
        try {
            worker.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------- gallery

    private fun exportToGallery(src: File) {
        val name = "MYRA_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/Maira_Recordings"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert failed")
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("MediaStore stream failed")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES
                    ),
                    "Maira_Recordings"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, name)
                src.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    this, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null
                )
            }
            try {
                src.delete()
            } catch (_: Exception) {
            }
            toastMain("Video saved to Gallery successfully!")
        } catch (e: Exception) {
            // Keep the temp file — the recording itself is not lost.
            Log.e(TAG, "gallery export failed; temp kept at ${src.absolutePath}", e)
            toastMain("Recording gallery main save nahi ho saki")
        }
    }

    // -------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "MYRA Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Recording")
            .setContentText("MYRA screen record kar rahi hai")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .addAction(0, "STOP", stopPending)
            .build()
    }

    // -------------------------------------------------------------- badge

    private fun showBadge() {
        if (badgeView != null) return
        if (!Settings.canDrawOverlays(this)) {
            // No overlay permission: recording continues; the
            // notification STOP action remains the way to stop.
            Log.i(TAG, "overlay permission nahi — badge skip")
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF3B30.toInt())
            }
        }
        val timer = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(0xD91A0F1C.toInt())
                setStroke(dp(1), 0xFFFF4757.toInt())
            }
            addView(
                dot,
                LinearLayout.LayoutParams(dp(10), dp(10))
            )
            addView(
                timer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            )
            setOnClickListener {
                val i = Intent(
                    this@ScreenRecordService, ScreenRecordService::class.java
                ).apply { action = ACTION_STOP }
                startService(i)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        try {
            wm.addView(pill, params)
            badgeView = pill
            badgeTimer = timer
            badgeDot = dot
        } catch (e: Exception) {
            Log.w(TAG, "badge add failed", e)
        }
    }

    private fun updateBadgeTimer() {
        val t = badgeTimer ?: return
        val secs = (elapsedMs() / 1000).toInt()
        t.text = String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
        // Gentle blink so the badge reads as "live" without animating
        // a whole extra view.
        badgeDot?.alpha = if (secs % 2 == 0) 1f else 0.35f
    }

    private fun removeBadge() {
        val view = badgeView ?: return
        badgeView = null
        badgeTimer = null
        badgeDot = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeView(view)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- helpers

    private fun notifyState() {
        val rec = isRecording
        val el = elapsedMs()
        mainHandler.post { stateListener?.invoke(rec, el) }
    }

    private fun toastMain(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
