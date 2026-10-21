package com.myra.assistant.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.MainActivity
import com.myra.assistant.util.CheckInStore

/**
 * Fires when one of MYRA's proactive check-ins (see
 * [com.myra.assistant.util.CheckInStore]) comes due.
 *
 * A check-in is how she "reaches out first" — Android does not let her
 * start speaking unprompted, so the caring message she composed during
 * the session arrives as a high-importance notification instead.
 * One-shot: the entry is removed before the notification is posted, so
 * it can never fire twice. Tapping the notification opens the app.
 */
class CheckInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id == -1L) return
        val entry = CheckInStore.find(context, id) ?: return
        // One-shot: remove + disarm first, then show.
        CheckInStore.remove(context, id)
        cancelAlarm(context, id)
        postNotification(context, entry)
    }

    private fun postNotification(context: Context, entry: CheckInStore.Entry) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MYRA check-ins",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val tapIntent = PendingIntent.getActivity(
                context,
                (REQ_BASE + entry.id).toInt(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MYRA")
                .setContentText(entry.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(entry.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .build()
            nm.notify((REQ_BASE + entry.id).toInt(), notif)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EXTRA_ID = "checkin_id"
        private const val CHANNEL_ID = "myra_checkins"
        private const val REQ_BASE = 7000

        private fun pending(c: Context, id: Long): PendingIntent {
            val i = Intent(c, CheckInReceiver::class.java).putExtra(EXTRA_ID, id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(c, (REQ_BASE + id).toInt(), i, flags)
        }

        /**
         * Arms the alarm for one check-in. Deliberately INEXACT
         * (setAndAllowWhileIdle): a caring "how did the meeting go"
         * does not need to land on the exact minute, and inexact
         * alarms need no special permission and survive Doze.
         */
        fun scheduleAlarm(c: Context, entry: CheckInStore.Entry) {
            try {
                val am = c.getSystemService(AlarmManager::class.java) ?: return
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    entry.triggerAtMs,
                    pending(c, entry.id)
                )
            } catch (_: Exception) {
            }
        }

        fun cancelAlarm(c: Context, id: Long) {
            try {
                c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, id))
            } catch (_: Exception) {
            }
        }

        /** Re-arm every stored future check-in (e.g. after a reboot). */
        fun rescheduleAll(c: Context) {
            CheckInStore.removePast(c)
            for (e in CheckInStore.all(c)) scheduleAlarm(c, e)
        }
    }
}
