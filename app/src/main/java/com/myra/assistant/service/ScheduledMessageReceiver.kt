package com.myra.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.myra.assistant.util.ScheduledStore

/**
 * Fires when a scheduled message's alarm goes off.
 * Removes the one-shot task from the store and starts the sender service.
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (id == -1L) return
        val task = ScheduledStore.find(context, id) ?: return
        // One-shot: never fire twice.
        ScheduledStore.remove(context, id)
        cancelAlarm(context, id)
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScheduledSenderService::class.java)
                    .putExtra(EXTRA_TASK_ID, id)
                    .putExtra("contact", task.contact)
                    .putExtra("message", task.message)
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val REQ_BASE = 9000

        private fun pending(c: Context, id: Long): PendingIntent {
            val i = Intent(c, ScheduledMessageReceiver::class.java).putExtra(EXTRA_TASK_ID, id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(c, (REQ_BASE + id).toInt(), i, flags)
        }

        /**
         * Sets the alarm. Returns "exact", "inexact" (no exact-alarm permission)
         * or "error".
         */
        fun scheduleAlarm(c: Context, task: ScheduledStore.Task): String {
            val am = c.getSystemService(AlarmManager::class.java) ?: return "error"
            val pi = pending(c, task.id)
            return try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.atMillis, pi)
                    "inexact"
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.atMillis, pi)
                    "exact"
                }
            } catch (_: SecurityException) {
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.atMillis, pi)
                    "inexact"
                } catch (_: Exception) {
                    "error"
                }
            } catch (_: Exception) {
                "error"
            }
        }

        fun cancelAlarm(c: Context, id: Long) {
            try {
                c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, id))
            } catch (_: Exception) {
            }
        }

        /** Re-arm all future alarms after a reboot. */
        fun rescheduleAll(c: Context) {
            ScheduledStore.removePast(c)
            for (t in ScheduledStore.all(c)) scheduleAlarm(c, t)
        }
    }
}
