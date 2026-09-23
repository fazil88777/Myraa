package com.myra.assistant

import android.app.Application
import com.myra.assistant.util.Prefs

class MyraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
