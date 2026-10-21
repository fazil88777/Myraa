package com.myra.assistant

import android.app.Application
import com.myra.assistant.service.BorderOverlay
import com.myra.assistant.util.MyraMemory
import com.myra.assistant.util.Prefs

class MyraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // One-time: land existing installs on the Gemini voice (the Edge
        // endpoint is blocked on some networks) — see Prefs.
        Prefs.migrateVoiceFailSafe()
        MyraMemory.init(this)
        // Accurate app-foreground tracking for the floating neon border:
        // registered at process start so no activity transition is missed.
        registerActivityLifecycleCallbacks(BorderOverlay.lifecycleCallbacks)
    }
}
