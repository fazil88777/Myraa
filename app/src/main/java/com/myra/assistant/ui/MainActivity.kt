package com.myra.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
import com.myra.assistant.service.ScreenShareService
import com.myra.assistant.util.LiaStyle
import com.myra.assistant.util.MyraBridge
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs
import java.util.Calendar

/**
 * LIA-style host: grid background, header (sync | title | mic),
 * tab container, bottom navigation (Home / Chat / AI / Tools / Profile).
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
        MyraBridge.viewModel = viewModel

        // Manual app open clears the full voice-shutdown flag.
        clearFullyOffIfManualOpen(intent)

        // Theme: grid tint
        findViewById<GridBackgroundView>(R.id.gridBg).gridColor =
            LiaStyle.theme(Prefs.theme).gridColor

        findViewById<TextView>(R.id.appNameText).text = Prefs.assistantName.ifBlank { "MYRA" }
        findViewById<TextView>(R.id.appSubtitleText).text = greeting()

        findViewById<View>(R.id.syncButton).setOnClickListener {
            findViewById<TextView>(R.id.appSubtitleText).text = greeting()
            toast("Synced ✓")
        }
        findViewById<View>(R.id.micButton).setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.stopSession()
            } else {
                ensureSession()
            }
        }

        findViewById<View>(R.id.navHome).setOnClickListener { selectTab("home") }
        findViewById<View>(R.id.navChat).setOnClickListener { selectTab("chat") }
        findViewById<View>(R.id.navVoice).setOnClickListener {
            ensureSession()
            selectTab("home")
        }
        findViewById<View>(R.id.navHistory).setOnClickListener { selectTab("tools") }
        findViewById<View>(R.id.navSettings).setOnClickListener { selectTab("profile") }

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

        // Voice-controlled screen share: MYRA posts a request, we launch/stop capture.
        viewModel.screenShareRequest.observe(this) { on ->
            if (on != null) setScreenShare(on)
        }

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
        clearFullyOffIfManualOpen(intent)
    }

    /** A manual app open clears the full-shutdown flag. */
    private fun clearFullyOffIfManualOpen(intent: Intent?) {
        try {
            if (Prefs.fullyOff) {
                Prefs.fullyOff = false
                viewModel.clearFullyOff()
                toast("MYRA on ho gayi")
            }
        } catch (_: Exception) {
        }
    }

    /** Start a voice session if the API key is saved, else guide to Settings. */
    private fun ensureSession() {
        if (viewModel.isConnected.value == true) return
        // Fully off: only a manual app open revives MYRA (handled above).
        if (Prefs.fullyOff) {
            toast("MYRA off hai — app dobara kholo taake on ho")
            return
        }
        val k = Prefs.apiKey
        if (k.isBlank()) {
            toast("Pehle Settings mein API key save karo")
            selectTab("profile")
        } else {
            viewModel.startSession(k)
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
        val name = Prefs.userName.trim()
        return if (name.isNotBlank()) "$g, $name" else "$g, dost"
    }

    fun selectTab(tab: String) {
        val frag: Fragment = when (tab) {
            "chat" -> ChatFragment()
            "tools" -> FeaturesFragment()
            "profile" -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, frag)
            .commit()
        // Minimal home themes (11/12/13/14) are mockup-exact: no header, no
        // bottom nav. Dashboard theme (#2) and every other tab keep them.
        // (On minimal home, long-press the electric circle for Chat/Tools/Profile.)
        val minimalHome = tab == "home" && Prefs.homeDesign != "2"
        findViewById<View>(R.id.headerBar)?.visibility =
            if (minimalHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomNav)?.visibility =
            if (minimalHome) View.GONE else View.VISIBLE
        paintNav(tab)
    }

    private fun paintNav(tab: String) {
        val active = LiaStyle.theme(Prefs.theme).accent
        val idle = 0xFF6A7B8A.toInt()
        val ids = mapOf(
            "home" to Pair(R.id.navHomeIcon, R.id.navHomeLabel),
            "chat" to Pair(R.id.navChatIcon, R.id.navChatLabel),
            "ai" to Pair(R.id.navVoiceIcon, R.id.navVoiceLabel),
            "tools" to Pair(R.id.navHistoryIcon, R.id.navHistoryLabel),
            "profile" to Pair(R.id.navSettingsIcon, R.id.navSettingsLabel)
        )
        ids.forEach { (key, pair) ->
            val color = if (key == tab) active else idle
            findViewById<TextView>(pair.first).setTextColor(color)
            findViewById<TextView>(pair.second).setTextColor(color)
        }
    }

    /** Start the voice session, or stop it if already live. Called by fragments. */
    fun toggleVoiceSession() {
        if (viewModel.isConnected.value == true) {
            viewModel.stopSession()
        } else {
            ensureSession()
        }
    }

    fun isVoiceLive(): Boolean = viewModel.isConnected.value == true

    /** Restart the live session so a changed voice/speed takes effect at once. */
    fun restartVoiceSession() {
        try {
            viewModel.stopSession()
            findViewById<View>(R.id.fragmentContainer).postDelayed({ ensureSession() }, 900)
        } catch (_: Exception) {
        }
    }

    /** Called by HomeFragment's Screen Share quick action. */
    fun toggleScreenShare() {
        setScreenShare(!(viewModel.isSharing.value == true))
    }

    /** Voice-controlled screen share (MYRA) and the quick-action button share this path. */
    fun setScreenShare(on: Boolean) {
        if (on) {
            if (viewModel.isSharing.value == true) return
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            if (viewModel.isSharing.value != true) return
            stopService(Intent(this, ScreenShareService::class.java))
            viewModel.setSharing(false)
            toast("Screen share OFF")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
