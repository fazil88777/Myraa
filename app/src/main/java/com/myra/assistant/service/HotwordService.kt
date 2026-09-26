package com.myra.assistant.service

import android.animation.ObjectAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.util.HotwordStore
import java.util.Locale

/**
 * Background "hi MYRA" hotword listener.
 *
 * A foreground service that keeps Android's speech recognizer listening in a
 * loop (no account, no extra downloads needed). When it hears "hi MYRA"
 * (or "hey MYRA"), it shows a Siri-style spinning orb overlay on screen and
 * opens MYRA with auto_connect=true so the voice session starts by itself.
 *
 * Toggle with the "hotword on karo" / "hotword band karo" voice commands
 * (set_hotword tool) — no Settings UI needed.
 */
class HotwordService : Service() {

    companion object {
        const val ACTION_START = "com.myra.assistant.hotword.START"
        const val ACTION_STOP = "com.myra.assistant.hotword.STOP"

        /** Set by MainActivity while the voice session is live — hotword stays quiet then. */
        @Volatile
        var sessionActive: Boolean = false

        @Volatile
        var running: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var restartRunnable: Runnable? = null
    private var overlayView: ImageView? = null
    private var overlayHideRunnable: Runnable? = null
    private var audioManager: AudioManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    "hotword",
                    "MYRA Hotword",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHotword()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        running = true
        handler.post { listenOnce() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopHotword()
        super.onDestroy()
    }

    // ---------- listening loop ----------

    private fun listenOnce() {
        cancelRestart()
        if (!HotwordStore.isEnabled(this) || sessionActive) {
            scheduleRestart(4000)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            scheduleRestart(15000)
            return
        }
        try {
            destroyRecognizer()
            val rec = SpeechRecognizer.createSpeechRecognizer(this)
            rec.setRecognitionListener(recognitionListener)
            recognizer = rec
            val si = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Listen longer through silence so we restart (and beep) less often.
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 20000)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 20000)
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 4000)
            }
            muteBeep(true)
            rec.startListening(si)
            listening = true
            handler.postDelayed({ muteBeep(false) }, 900)
        } catch (_: Exception) {
            listening = false
            scheduleRestart(4000)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            // No mic permission yet, or busy — retry quietly in the background.
            scheduleRestart(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) 15000 else 1500)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches.any { isHotword(it) }) {
                onHotwordDetected()
            } else {
                scheduleRestart(700)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches.any { isHotword(it) }) {
                try {
                    recognizer?.stopListening()
                } catch (_: Exception) {
                }
                onHotwordDetected()
            }
        }
    }

    /** True for "hi MYRA" / "hey MYRA" (and close mis-hearings) — never for "hi google" etc. */
    private fun isHotword(text: String): Boolean {
        val t = " ${text.lowercase(Locale.US)} "
        val hi = t.contains(" hi ") || t.contains(" hey ") ||
                t.startsWith("hi ") || t.startsWith("hey ")
        val myra = t.contains("myra") || t.contains("maira") ||
                t.contains("mira") || t.contains("myrah") || t.contains("mayera")
        return hi && myra
    }

    private fun onHotwordDetected() {
        destroyRecognizer()
        listening = false
        cancelRestart()
        showOrbOverlay()
        // Open MYRA — MainActivity sees auto_connect and starts the voice session itself.
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                putExtra("auto_connect", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (launch != null) startActivity(launch)
        } catch (_: Exception) {
        }
        // Give the voice session the mic for a while, then resume listening.
        scheduleRestart(120000)
    }

    private fun scheduleRestart(delayMs: Long) {
        cancelRestart()
        val r = Runnable {
            restartRunnable = null
            if (running) listenOnce()
        }
        restartRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelRestart() {
        restartRunnable?.let { handler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listening = false
    }

    private fun stopHotword() {
        running = false
        cancelRestart()
        destroyRecognizer()
        hideOrbOverlay()
        muteBeep(false)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    /** Best-effort mute of the recognizer's start beep. */
    private fun muteBeep(mute: Boolean) {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.adjustStreamVolume(
                    AudioManager.STREAM_SYSTEM,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_SYSTEM, mute)
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Siri-style spinning orb overlay ----------

    private fun showOrbOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                return // no overlay permission — the app still opens
            }
            hideOrbOverlay()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = (150 * resources.displayMetrics.density).toInt()
            val view = ImageView(this).apply {
                setImageResource(R.drawable.orb_crimson)
                alpha = 0.95f
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            view.setOnClickListener {
                try {
                    val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launch != null) startActivity(launch)
                } catch (_: Exception) {
                }
                hideOrbOverlay()
            }
            wm.addView(view, params)
            overlayView = view
            val spin = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = 6000
                repeatCount = ObjectAnimator.INFINITE
            }
            spin.start()
            view.tag = spin
            val hide = Runnable { hideOrbOverlay() }
            overlayHideRunnable = hide
            handler.postDelayed(hide, 30000)
        } catch (_: Exception) {
        }
    }

    private fun hideOrbOverlay() {
        try {
            overlayHideRunnable?.let { handler.removeCallbacks(it) }
            overlayHideRunnable = null
            val view = overlayView
            overlayView = null
            if (view != null) {
                (view.tag as? ObjectAnimator)?.cancel()
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        } catch (_: Exception) {
        }
    }

    // ---------- foreground notification ----------

    private fun startForegroundWithNotification() {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, "hotword")
            .setContentTitle("MYRA sun rahi hai")
            .setContentText("'hi MYRA' bolo — main hazir ho jaungi")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                2001, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(2001, notif)
        }
    }
}
