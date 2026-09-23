package com.myra.assistant.service

import android.content.*
import androidx.core.content.ContextCompat
import com.myra.assistant.util.Prefs

/**
 * Restarts the floating orb after device boot when the orb is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON") {
            try {
                if (Prefs.orbEnabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, FloatingOrbService::class.java)
                    )
                }
            } catch (_: Exception) {
            }
        }
    }
}
