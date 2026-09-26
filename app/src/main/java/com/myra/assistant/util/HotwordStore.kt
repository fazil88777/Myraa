package com.myra.assistant.util

import android.content.Context

/**
 * Separate prefs file for the "hi MYRA" hotword listener, so it never
 * depends on the main Prefs object. No init() needed.
 */
object HotwordStore {

    private const val FILE = "myra_hotword"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        sp(context).getBoolean("hotword_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean("hotword_enabled", enabled).apply()
    }
}
