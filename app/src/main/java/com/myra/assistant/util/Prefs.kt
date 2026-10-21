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

    private fun long(key: String, def: Long): Long =
        if (::sp.isInitialized) sp.getLong(key, def) else def

    private fun putLong(key: String, value: Long) {
        if (::sp.isInitialized) sp.edit().putLong(key, value).apply()
    }

    var apiKey: String
        get() = str("api_key", "")
        set(value) = putStr("api_key", value)

    /** Voice engine: "GEMINI_TTS" (Gemini TTS model speaks the Live
     *  session's transcript with the app's own Gemini key — the default),
     *  "EDGE" (Microsoft Edge Neural TTS — free, no key, with Google
     *  Translate TTS as its silent per-segment fallback) or "GEMINI"
     *  (Gemini Live's own native voice). Settings > KEYS & DATA. */
    var voiceEngine: String
        get() = str("voice_engine", "GEMINI_TTS")
        set(value) = putStr("voice_engine", value)

    /** One-time Gemini-TTS migration ran (see migrateVoiceGeminiTts). */
    var voiceGeminiTtsMigrated: Boolean
        get() = bool("voice_gemini_tts_migrated", false)
        set(value) = putBool("voice_gemini_tts_migrated", value)

    /**
     * One-time migration for the Gemini-TTS build: on the FIRST start
     * with this build, force the engine to GEMINI_TTS exactly once —
     * earlier defaults (Edge) are blocked outright on some networks, and
     * the Gemini TTS engine rides the Gemini key/connection that already
     * works. A later manual engine switch in Settings is respected (the
     * flag stays set). Call once at app start, after init.
     */
    fun migrateVoiceGeminiTts() {
        if (!::sp.isInitialized || voiceGeminiTtsMigrated) return
        voiceEngine = "GEMINI_TTS"
        voiceGeminiTtsMigrated = true
    }

    /** Edge Neural voice that speaks her replies in EDGE engine mode. */
    var edgeVoice: String
        get() = str("edge_voice", "ur-PK-UzmaNeural")
        set(value) = putStr("edge_voice", value)

    /**
     * True when a transcript voice engine is selected (GEMINI_TTS or
     * EDGE): the Live session then asks for output transcription,
     * Gemini's own audio is discarded unheard, and the reply's
     * transcript text is spoken by the selected TTS speaker instead.
     * Decided once at session start — never switched mid-session.
     * False = Gemini Live's native voice speaks (engine GEMINI).
     */
    val transcriptVoiceEnabled: Boolean
        get() = voiceEngine == "EDGE" || voiceEngine == "GEMINI_TTS"

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

    /** Dark theme: teal, midnight, crimson, violet, rose, black. */
    var theme: String
        get() = str("lia_theme", "teal")
        set(value) = putStr("lia_theme", value)

    /** Reduce ambient animations. */
    var reducedMotion: Boolean
        get() = bool("reduced_motion", false)
        set(value) = putBool("reduced_motion", value)

    /** Remember conversation recap across sessions. */
    var memoryEnabled: Boolean
        get() = bool("memory_enabled", true)
        set(value) = putBool("memory_enabled", value)

    /** Neon border around the app screens (and the floating border). */
    var neonLighting: Boolean
        get() = bool("neon_lighting", true)
        set(value) = putBool("neon_lighting", value)

    /** Live Voice: real-time voice chat allowed from the orb/reactor. */
    var liveVoice: Boolean
        get() = bool("live_voice", true)
        set(value) = putBool("live_voice", value)

    /** Floating border over other apps while a session is live. */
    var floatingBorder: Boolean
        get() = bool("floating_border", true)
        set(value) = putBool("floating_border", value)

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

    // ---------- Emotional state (MoodManager) ----------

    /** Epoch ms of the last real interaction (session start / connect / end). 0 = never. */
    var lastInteractionMs: Long
        get() = long("last_interaction_ms", 0L)
        set(value) = putLong("last_interaction_ms", value)

    /** Current mood name (MoodManager.Mood enum name). */
    var moodName: String
        get() = str("mood_name", "CALM")
        set(value) = putStr("mood_name", value)

    /** Epoch ms when the current mood was set. */
    var moodUpdatedMs: Long
        get() = long("mood_updated_ms", 0L)
        set(value) = putLong("mood_updated_ms", value)

    /** Gap in hours that triggered a MISSING_USER mood (0 = not gap-triggered). */
    var moodGapHours: Long
        get() = long("mood_gap_hours", 0L)
        set(value) = putLong("mood_gap_hours", value)
}
