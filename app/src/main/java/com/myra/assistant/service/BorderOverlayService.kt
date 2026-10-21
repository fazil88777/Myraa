package com.myra.assistant.service

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.R
import com.myra.assistant.ui.NeonBorderView
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs

/**
 * Coordinator for the floating neon border (Fazil requirement 3):
 * while a voice session is LIVE and MYRA is in the background, the
 * rainbow border keeps running around the screen edges over other
 * apps; when the session ends it stops. [refresh] is called from
 * MainViewModel on every connection change and from the UI whenever
 * the related prefs change, and starts/stops [BorderOverlayService].
 *
 * Foreground tracking: [lifecycleCallbacks] are registered once in
 * MyraApplication, so [startedActivities] is always accurate — the
 * service shows its overlay only while NO activity is started, so the
 * border never doubles up with the in-app one.
 */
object BorderOverlay {

    @Volatile
    var startedActivities = 0
        private set

    @Volatile
    var sessionLive = false
        private set

    /** Set by the running service; invoked whenever visibility inputs change. */
    @Volatile
    var overlayUpdater: (() -> Unit)? = null

    val appInForeground: Boolean
        get() = startedActivities > 0

    val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            overlayUpdater?.invoke()
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            overlayUpdater?.invoke()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /**
     * Re-evaluate the overlay service for the given session state.
     * The service runs only while: session live AND Neon Lighting on
     * AND Floating Border on AND the draw-over-apps permission granted.
     */
    fun refresh(context: Context, live: Boolean) {
        sessionLive = live
        val app = context.applicationContext
        val want = live && Prefs.neonLighting && Prefs.floatingBorder &&
            PermissionHelper.canDrawOverlay(app)
        val intent = Intent(app, BorderOverlayService::class.java)
        try {
            if (want) {
                ContextCompat.startForegroundService(app, intent)
            } else {
                app.stopService(intent)
            }
        } catch (_: Exception) {
            // Background FGS-start restrictions etc.: the overlay simply
            // stays off — never a crash.
        }
        overlayUpdater?.invoke()
    }
}

/**
 * Foreground service holding ONE non-touchable, non-focusable
 * [NeonBorderView] over the whole screen (TYPE_APPLICATION_OVERLAY)
 * while MYRA is live in the background. The view is added only while
 * the app itself is not in the foreground, and removed (not hidden)
 * when the app returns or the service stops.
 */
class BorderOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var borderView: NeonBorderView? = null

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
            .setContentText("MYRA live — neon border on")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notif)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        BorderOverlay.overlayUpdater = { updateOverlay() }
        updateOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        BorderOverlay.overlayUpdater = null
        removeBorder()
        super.onDestroy()
    }

    private fun updateOverlay() {
        val show = BorderOverlay.sessionLive && !BorderOverlay.appInForeground &&
            Prefs.neonLighting && Prefs.floatingBorder &&
            PermissionHelper.canDrawOverlay(this)
        if (show) addBorder() else removeBorder()
    }

    private fun addBorder() {
        if (borderView != null) return
        val wm = windowManager ?: return
        val view = NeonBorderView(this).apply { live = true }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, params)
            borderView = view
        } catch (_: Exception) {
        }
    }

    private fun removeBorder() {
        val view = borderView ?: return
        borderView = null
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
    }
}
