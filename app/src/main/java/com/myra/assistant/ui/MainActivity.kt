package com.myra.assistant.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.service.ScreenShareService
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs
import java.util.Calendar

/**
 * MAX-style host: header (MYRA + greeting + gear), tab container,
 * bottom navigation (Home / History / Settings / Features).
 * All voice/session logic lives in the shared MainViewModel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    companion object {
        private const val REQ_CORE = 1001
    }

    // One-time system dialog: "Allow MYRA to record your screen?"
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val i = Intent(this, ScreenShareService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(this, i)
            viewModel.setSharing(true)
            toast("Screen share ON — MYRA ab screen dekh sakti hai")
        } else {
            toast("Screen share cancel")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash catcher: save the stack trace if the app crashes, show it on next launch
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Prefs.lastCrash = android.util.Log.getStackTraceString(throwable).take(1500)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        findViewById<TextView>(R.id.greetingText).text = greeting()

        findViewById<TextView>(R.id.settingsGearButton).setOnClickListener {
            selectTab("settings")
        }

        findViewById<LinearLayout>(R.id.navHome).setOnClickListener { selectTab("home") }
        findViewById<LinearLayout>(R.id.navHistory).setOnClickListener { selectTab("history") }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener { selectTab("settings") }
        findViewById<LinearLayout>(R.id.navFeatures).setOnClickListener { selectTab("features") }

        if (savedInstanceState == null) selectTab("home")

        if (!PermissionHelper.hasAllCore(this)) {
            PermissionHelper.requestCore(this, REQ_CORE)
        }

        // If the app crashed last time, show the reason
        val lastCrash = Prefs.lastCrash
        if (lastCrash.isNotEmpty()) {
            Prefs.lastCrash = ""
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Pichli baar crash ki wajah")
                .setMessage(lastCrash)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        return "$g, Jaan"
    }

    fun selectTab(tab: String) {
        val frag: Fragment = when (tab) {
            "history" -> HistoryFragment()
            "settings" -> SettingsFragment()
            "features" -> FeaturesFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, frag)
            .commit()
        paintNav(tab)
    }

    private fun paintNav(tab: String) {
        val active = 0xFFB388FF.toInt()
        val idle = 0xFF6A6A8A.toInt()
        val ids = mapOf(
            "home" to Pair(R.id.navHomeIcon, R.id.navHomeLabel),
            "history" to Pair(R.id.navHistoryIcon, R.id.navHistoryLabel),
            "settings" to Pair(R.id.navSettingsIcon, R.id.navSettingsLabel),
            "features" to Pair(R.id.navFeaturesIcon, R.id.navFeaturesLabel)
        )
        ids.forEach { (key, pair) ->
            val color = if (key == tab) active else idle
            findViewById<TextView>(pair.first).setTextColor(color)
            findViewById<TextView>(pair.second).setTextColor(color)
        }
    }

    /** Called by HomeFragment's Screen Share button. */
    fun toggleScreenShare() {
        if (viewModel.isSharing.value == true) {
            stopService(Intent(this, ScreenShareService::class.java))
            viewModel.setSharing(false)
            toast("Screen share OFF")
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
