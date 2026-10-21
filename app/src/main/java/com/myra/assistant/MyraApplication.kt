package com.myra.assistant

import android.app.Application
import com.myra.assistant.service.BorderOverlay
import com.myra.assistant.util.MyraMemory
import com.myra.assistant.util.Prefs
import com.myra.assistant.util.SecretVault

class MyraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // One-time: land installs on the Gemini TTS voice (the Edge
        // endpoint is blocked on some networks) — see Prefs.
        Prefs.migrateVoiceGeminiTts()
        // One-time, runs second and wins: back to Gemini Live native
        // audio (voice Aoede) — the TTS preview model dies on HTTP 429
        // after a few sentences on the free tier; Live does not.
        Prefs.migrateLiveDefault()
        MyraMemory.init(this)
        SecretVault.init(this)
        // Accurate app-foreground tracking for the floating neon border:
        // registered at process start so no activity transition is missed.
        registerActivityLifecycleCallbacks(BorderOverlay.lifecycleCallbacks)
    }
}
