package com.myra.assistant.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.MainActivity

/**
 * Foreground service showing a floating orb overlay. Tap opens MainActivity,
 * long-press stops the service. Runs with microphone foreground-service type
 * so capture survives while the app is in the background.
 */
class FloatingOrbService : Service() {

    private var windowManager: WindowManager? = null
    private var orbView: View? = null

    override fun onCreate() {
        super.onCreate()

        val channelId = "myra"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 300

        orbView = LayoutInflater.from(this).inflate(R.layout.overlay_orb, null)
        orbView?.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        orbView?.setOnLongClickListener {
            stopSelf()
            true
        }
        try {
            windowManager?.addView(orbView, params)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            orbView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
