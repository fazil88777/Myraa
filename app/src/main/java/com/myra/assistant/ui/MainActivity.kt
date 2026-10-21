package com.myra.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.service.BorderOverlay
import com.myra.assistant.service.ScreenShareService
import com.myra.assistant.util.MyraBridge
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs

/**
 * Host activity: a full-screen tab container framed by ONE activity-level
 * NeonBorderView (the border now frames every screen, like the reference
 * design). The old LIA header bar (sync | title | mic) and the old
 * Home/Chat/AI/Tools/Profile bottom nav are GONE — the dashboard home is
 * the hub: its own dot-nav + pills switch tabs, and system Back from any
 * other tab returns HOME instead of exiting. All voice/session logic
 * lives in the shared MainViewModel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private var neonBorder: NeonBorderView? = null
    private var currentTab: String = "home"
    private var wasConnected = false

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

        neonBorder = findViewById(R.id.neonBorderMain)

        // Border live/dim state + floating-overlay state follow the session.
        viewModel.isConnected.observe(this) { connected ->
            val live = connected == true
            neonBorder?.live = live
            BorderOverlay.refresh(this, live)
            // Session just started: the floating border needs the
            // draw-over-apps permission. Ask once per session start.
            if (live && !wasConnected && Prefs.floatingBorder &&
                !PermissionHelper.canDrawOverlay(this)
            ) {
                toast("Background border ke liye \"dusri apps ke upar dikhao\" permission chahiye")
                PermissionHelper.openOverlaySettings(this)
            }
            wasConnected = live
        }

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

        refreshBorderState()

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

    override fun onResume() {
        super.onResume()
        refreshBorderState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clearFullyOffIfManualOpen(intent)
    }

    /**
     * Apply the Neon Lighting pref: hides/shows the border everywhere
     * (activity frame + floating overlay). Called on create/resume and
     * by Settings right after the toggles change.
     */
    fun refreshBorderState() {
        neonBorder?.visibility = if (Prefs.neonLighting) View.VISIBLE else View.GONE
        if (::viewModel.isInitialized) {
            BorderOverlay.refresh(this, viewModel.isConnected.value == true)
        }
    }

    /** System Back from any tab returns HOME; Back on home exits as usual. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab != "home") {
            selectTab("home")
        } else {
            super.onBackPressed()
        }
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
        // Live Voice off in Settings: the orb must not start a session.
        if (!Prefs.liveVoice) {
            toast("Live Voice off hai")
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

    fun selectTab(tab: String) {
        currentTab = tab
        val frag: Fragment = when (tab) {
            "chat" -> ChatFragment()
            "tools" -> FeaturesFragment()
            "memory" -> MemoryFragment()
            "profile" -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, frag)
            .commit()
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

    /** Called by FeaturesFragment's Screen Share quick action. */
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
