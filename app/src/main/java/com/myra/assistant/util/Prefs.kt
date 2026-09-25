package com.myra.assistant.util

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed app settings, stored in the "myra" prefs file.
 */
object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("myra", Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = if (::sp.isInitialized) sp.getString("api_key", "") ?: "" else ""
        set(value) {
            if (::sp.isInitialized) sp.edit().putString("api_key", value).apply()
        }

    var orbEnabled: Boolean
        get() = if (::sp.isInitialized) sp.getBoolean("orb_enabled", true) else true
        set(value) {
            if (::sp.isInitialized) sp.edit().putBoolean("orb_enabled", value).apply()
        }

    var autoRejectUnknown: Boolean
        get() = if (::sp.isInitialized) sp.getBoolean("auto_reject_unknown", false) else false
        set(value) {
            if (::sp.isInitialized) sp.edit().putBoolean("auto_reject_unknown", value).apply()
        }

    var lastCaller: String
        get() = if (::sp.isInitialized) sp.getString("last_caller", "") ?: "" else ""
        set(value) {
            if (::sp.isInitialized) sp.edit().putString("last_caller", value).apply()
        }

    var lastCrash: String
        get() = if (::sp.isInitialized) sp.getString("last_crash", "") ?: "" else ""
        set(value) {
            if (::sp.isInitialized) sp.edit().putString("last_crash", value).apply()
        }

    /** Aura orb design: "crimson", "azure" or "violet". */
    var orbDesign: String
        get() = if (::sp.isInitialized) sp.getString("orb_design", "crimson") ?: "crimson" else "crimson"
        set(value) {
            if (::sp.isInitialized) sp.edit().putString("orb_design", value).apply()
        }

    /** Home wallpaper: "midnight", "crimson" or "azure". */
    var wallpaper: String
        get() = if (::sp.isInitialized) sp.getString("wallpaper", "midnight") ?: "midnight" else "midnight"
        set(value) {
            if (::sp.isInitialized) sp.edit().putString("wallpaper", value).apply()
        }

    /** User's name (asked by MYRA, editable in Settings). */
    var userName: String
        get() = if (::sp.isInitialized) sp.getString("user_name", "") ?: "" else ""
        set(value) {
            if (::sp.isInitialized) sp.edit().putString("user_name", value).apply()
        }
}
