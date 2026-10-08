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

    private fun str(key: String, def: String): String =
        if (::sp.isInitialized) sp.getString(key, def) ?: def else def

    private fun putStr(key: String, value: String) {
        if (::sp.isInitialized) sp.edit().putString(key, value).apply()
    }

    private fun bool(key: String, def: Boolean): Boolean =
        if (::sp.isInitialized) sp.getBoolean(key, def) else def

    private fun putBool(key: String, value: Boolean) {
        if (::sp.isInitialized) sp.edit().putBoolean(key, value).apply()
    }

    private fun float(key: String, def: Float): Float =
        if (::sp.isInitialized) sp.getFloat(key, def) else def

    private fun putFloat(key: String, value: Float) {
        if (::sp.isInitialized) sp.edit().putFloat(key, value).apply()
    }

    var apiKey: String
        get() = str("api_key", "")
        set(value) = putStr("api_key", value)

    var orbEnabled: Boolean
        get() = bool("orb_enabled", true)
        set(value) = putBool("orb_enabled", value)

    var autoRejectUnknown: Boolean
        get() = bool("auto_reject_unknown", false)
        set(value) = putBool("auto_reject_unknown", value)

    var lastCaller: String
        get() = str("last_caller", "")
        set(value) = putStr("last_caller", value)

    var lastCrash: String
        get() = str("last_crash", "")
        set(value) = putStr("last_crash", value)

    /** Aura orb design: "crimson", "azure" or "violet". (legacy, kept) */
    var orbDesign: String
        get() = str("orb_design", "crimson")
        set(value) = putStr("orb_design", value)

    /** Home wallpaper: "midnight", "crimson" or "azure". (legacy, kept) */
    var wallpaper: String
        get() = str("wallpaper", "midnight")
        set(value) = putStr("wallpaper", value)

    /** User's name (asked by MYRA, editable in Settings). */
    var userName: String
        get() = str("user_name", "")
        set(value) = putStr("user_name", value)

    // ---------- LIA-style settings ----------

    /** Assistant's display name. */
    var assistantName: String
        get() = str("assistant_name", "MYRA")
        set(value) = putStr("assistant_name", value)

    /** Personality: friendly, gf, boss, funny, calm. */
    var personality: String
        get() = str("personality", "friendly")
        set(value) = putStr("personality", value)

    /** Gemini Live voice: Sulafat, Aoede, Kore, Puck. */
    var voiceName: String
        get() = str("voice_name", "Sulafat")
        set(value) = putStr("voice_name", value)

    /** Language: auto, urdu, english, hindi. */
    var language: String
        get() = str("language", "auto")
        set(value) = putStr("language", value)

    /** Speech speed multiplier for voice style. */
    var speechSpeed: Float
        get() = float("speech_speed", 1.0f)
        set(value) = putFloat("speech_speed", value)

    /** Start listening automatically when the app opens. */
    var autoListening: Boolean
        get() = bool("auto_listening", false)
        set(value) = putBool("auto_listening", value)

    /** Dark theme: teal, midnight, crimson, violet, rose, black. */
    var theme: String
        get() = str("lia_theme", "teal")
        set(value) = putStr("lia_theme", value)

    /** Orb color: teal, red, black, pink, green, blue, purple, gold. */
    var orbColor: String
        get() = str("orb_color", "teal")
        set(value) = putStr("orb_color", value)

    /** Reduce ambient animations. */
    var reducedMotion: Boolean
        get() = bool("reduced_motion", false)
        set(value) = putBool("reduced_motion", value)

    /** Remember conversation recap across sessions. */
    var memoryEnabled: Boolean
        get() = bool("memory_enabled", true)
        set(value) = putBool("memory_enabled", value)

    /** PC link server enabled. */
    var pcEnabled: Boolean
        get() = bool("pc_enabled", false)
        set(value) = putBool("pc_enabled", value)

    /**
     * Full voice shutdown: MYRA stays completely dead (no session, no
     * watchdog, no auto-reconnect, no background restarts) until the user
     * manually opens the app again, which clears this flag.
     */
    var fullyOff: Boolean
        get() = bool("fully_off", false)
        set(value) = putBool("fully_off", value)
}
