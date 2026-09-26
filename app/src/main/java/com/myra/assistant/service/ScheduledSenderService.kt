package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.myra.assistant.util.ScheduledStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that delivers one scheduled WhatsApp message:
 * wakes the screen, drives WhatsApp through the accessibility service,
 * then reports the result in a notification.
 */
class ScheduledSenderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contact = intent?.getStringExtra("contact") ?: ""
        val message = intent?.getStringExtra("message") ?: ""
        if (contact.isBlank() || message.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("scheduled", "Scheduled messages", NotificationManager.IMPORTANCE_HIGH)
        )
        val progress = NotificationCompat.Builder(this, "scheduled")
            .setContentTitle("MYRA")
            .setContentText("$contact ko scheduled message bhej rahi hai…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1001, progress)

        // Wake the screen so the accessibility driver can act.
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "myra:scheduled"
            )
            wakeLock.acquire(90_000)
        } catch (_: Exception) {
        }
        // Best effort: dismiss a non-secure (swipe) keyguard.
        try {
            @Suppress("DEPRECATION")
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.newKeyguardLock("myra").disableKeyguard()
        } catch (_: Exception) {
        }

        Thread {
            try {
                Thread.sleep(2000)
                val res = MyraAccessibilityService.sendWhatsAppMessage(contact, message)
                val ok = res.startsWith("OK")
                val stamp = SimpleDateFormat("d MMM h:mm a", Locale.US).format(Date())
                ScheduledStore.setLastResult(
                    this,
                    if (ok) "[$stamp] $contact ko bhej diya."
                    else "[$stamp] $contact ko NAHI bhej saki: $res"
                )
                nm.notify(
                    1002,
                    NotificationCompat.Builder(this, "scheduled")
                        .setContentTitle("MYRA")
                        .setContentText(
                            if (ok) "$contact ko scheduled message bhej diya."
                            else "$contact ko message nahi gaya — $res"
                        )
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .build()
                )
            } catch (e: Exception) {
                ScheduledStore.setLastResult(this, "Error: ${e.message}")
            } finally {
                try {
                    wakeLock?.release()
                } catch (_: Exception) {
                }
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
