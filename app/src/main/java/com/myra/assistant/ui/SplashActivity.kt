package com.myra.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R

/**
 * Intro: plays the 5-second MYRA intro video (with the
 * "Welcome to Myra Assistant" voiceover), then opens the home screen.
 * Never traps the user here — any failure skips straight to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        supportActionBar?.hide()
        setContentView(R.layout.activity_splash)

        val vv = findViewById<VideoView>(R.id.splashVideo)
        vv.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.myra_intro}"))
        vv.setOnCompletionListener { goHome() }
        vv.setOnErrorListener { _, _, _ -> goHome(); true }
        try {
            vv.start()
        } catch (_: Exception) {
            goHome()
        }

        // Safety net: if the video can't play, move on after ~6s.
        Handler(Looper.getMainLooper()).postDelayed({ goHome() }, 6000)
    }

    private fun goHome() {
        if (done) return
        done = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
