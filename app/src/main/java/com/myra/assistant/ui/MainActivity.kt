package com.myra.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.service.HotwordService
import com.myra.assistant.service.ScreenShareService
import com.myra.assistant.util.HotwordStore
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs
import java.util.Calendar

/**
 * Host: header (MYRA + greeting + gear), tab container,
 * bottom navigation (Home / History / Settings / Features).
 * All voice/session logic lives in the shared MainViewModel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    companion object {
        private const val REQ_CORE = 1001
        private const val REQ_CAMERA = 1002
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
        findViewById<View>(R.id.settingsGearButton).setOnClickListener {
            selectTab("settings")
        }
        findViewById<View>(R.id.navHome).setOnClickListener { selectTab("home") }
        findViewById<View>(R.id.navHistory).setOnClickListener { selectTab("history") }
        findViewById<View>(R.id.navSettings).setOnClickListener { selectTab("settings") }
        findViewById<View>(R.id.navFeatures).setOnClickListener { selectTab("features") }

        if (savedInstanceState == null) selectTab("home")

        if (!PermissionHelper.hasAllCore(this)) {
            PermissionHelper.requestCore(this, REQ_CORE)
        }

        // Camera permission for camera vision ("camera on karo")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA
            )
        }

        // ---- "hi MYRA" hotword wiring ----
        // Keep the background listener quiet while the voice session is live.
        viewModel.isConnected.observe(this) { connected ->
            HotwordService.sessionActive = connected == true
        }
        // The Siri-style circle needs "Display over other apps" — ask once, only if hotword is on.
        if (HotwordStore.isEnabled(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                toast("Hotword circle ke liye 'Display over other apps' ON kar dena")
            } catch (_: Exception) {
            }
        }
        // Resume background hotword listening (after reboot / app update).
        if (HotwordStore.isEnabled(this)) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, HotwordService::class.java)
                        .setAction(HotwordService.ACTION_START)
                )
            } catch (_: Exception) {
            }
        }
        handleAutoConnect(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoConnect(intent)
    }

    /**
     * "hi MYRA" hotword trigger: open MYRA and start the full voice session
     * by ourselves — same as opening the app and pressing Connect.
     */
    private fun handleAutoConnect(intent: Intent?) {
        if (intent?.getBooleanExtra("auto_connect", false) != true) return
        intent.removeExtra("auto_connect")
        if (Prefs.apiKey.isBlank()) {
            toast("API key nahi hai — pehle Settings mein API key dalo")
            return
        }
        viewModel.startSession(Prefs.apiKey) // idempotent: already connected ho to kuch nahi hota
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        val name = Prefs.userName.trim()
        return if (name.isNotBlank()) "$g, $name boss" else "$g, boss"
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
        val active = 0xFFFF5252.toInt() // MYRA red
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
