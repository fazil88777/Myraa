package com.myra.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.ai.CameraVision
import com.myra.assistant.util.MyraMemory
import com.myra.assistant.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MYRA home — sci-fi dashboard (the design Fazil approved from the
 * reference layout, rebuilt natively for MYRA):
 *
 * • Header: "MYRA / AI Assistant" with sparks orbiting the title.
 * • Middle: left pills (MEMORY / CHAT / TOOLS / SETTINGS) with light beams
 *   running to the glowing reactor orb on the right. The reactor IS the
 *   voice button — tap starts the session ("listening"), tap again stops
 *   ("ready"). Status text under the reactor mirrors the real session.
 * • Camera button toggles MYRA's camera-vision (same state as the
 *   "camera on karo / camera band karo" voice command).
 * • Cards: INTELLIGENCE BRIEF (today + pending tasks from MyraMemory,
 *   "+ MORE" opens the Memory screen) and SYSTEM (real voice/memory state).
 * • Own dot-style bottom nav (Home / Chat / Voice / Memory / Tools /
 *   Settings) + a rainbow neon border. MainActivity hides its own header
 *   and bottom nav on this screen, so there is exactly one nav.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var reactor: ReactorView? = null
    private var statusText: TextView? = null
    private var cameraBtn: Button? = null
    private var sysVoiceState: TextView? = null
    private var sysMemoryState: TextView? = null
    private var briefDate: TextView? = null
    private var briefTasks: TextView? = null
    private var lastErrorShown: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val act = activity as? MainActivity

        reactor = view.findViewById(R.id.reactorView)
        statusText = view.findViewById(R.id.statusText)
        cameraBtn = view.findViewById(R.id.cameraBtn)
        sysVoiceState = view.findViewById(R.id.sysVoiceState)
        sysMemoryState = view.findViewById(R.id.sysMemoryState)
        briefDate = view.findViewById(R.id.briefDate)
        briefTasks = view.findViewById(R.id.briefTasks)

        // Reactor = voice button (same toggle the old electric circle used).
        reactor?.setOnClickListener { act?.toggleVoiceSession() }

        // Pills.
        view.findViewById<View>(R.id.pillMemory).setOnClickListener { act?.selectTab("memory") }
        view.findViewById<View>(R.id.pillChat).setOnClickListener { act?.selectTab("chat") }
        view.findViewById<View>(R.id.pillTools).setOnClickListener { act?.selectTab("tools") }
        view.findViewById<View>(R.id.pillSettings).setOnClickListener { act?.selectTab("profile") }

        // Dashboard bottom nav.
        view.findViewById<View>(R.id.navHome).setOnClickListener { act?.selectTab("home") }
        view.findViewById<View>(R.id.navChat).setOnClickListener { act?.selectTab("chat") }
        view.findViewById<View>(R.id.navVoice).setOnClickListener { act?.selectTab("home") }
        view.findViewById<View>(R.id.navMemory).setOnClickListener { act?.selectTab("memory") }
        view.findViewById<View>(R.id.navTools).setOnClickListener { act?.selectTab("tools") }
        view.findViewById<View>(R.id.navSettings).setOnClickListener { act?.selectTab("profile") }

        // Intelligence Brief card -> Memory screen.
        view.findViewById<View>(R.id.briefCard).setOnClickListener { act?.selectTab("memory") }
        view.findViewById<View>(R.id.briefMore).setOnClickListener { act?.selectTab("memory") }

        // Camera-vision toggle (same pref the voice tool flips).
        cameraBtn?.setOnClickListener {
            val ctx = requireContext()
            val on = !CameraVision.isEnabled(ctx)
            CameraVision.setEnabled(ctx, on)
            updateCameraLabel()
            toast(
                if (on) "Camera ON — live session mein MYRA dekh sakti hai 📷"
                else "Camera OFF"
            )
        }

        vm.isConnected.observe(viewLifecycleOwner) { updateStatus() }
        vm.statusText.observe(viewLifecycleOwner) { s ->
            updateStatus()
            // Surface session errors so a failed start is never silent.
            if (s == null || s == lastErrorShown) return@observe
            val low = s.lowercase()
            if (low.contains("error") || low.contains("off hai") || low.contains("lost")) {
                lastErrorShown = s
                Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
            }
        }

        refreshBrief()
        refreshSystem()
        updateCameraLabel()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshBrief()
        refreshSystem()
        updateCameraLabel()
    }

    /** Reactor glow + status word follow the real session state. */
    private fun updateStatus() {
        if (!::vm.isInitialized) return
        val connected = vm.isConnected.value == true
        reactor?.active = connected
        val raw = vm.statusText.value ?: ""
        statusText?.text = when {
            connected -> "listening"
            raw.startsWith("Connecting") -> "connecting…"
            else -> "ready"
        }
        if (view != null) refreshSystem()
    }

    /** INTELLIGENCE BRIEF: today's date/time + pending-task count (MyraMemory). */
    private fun refreshBrief() {
        val now = Date()
        val date = SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(now)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        briefDate?.text = "$date • $time"
        val pending = MyraMemory.pendingTasks()
        briefTasks?.text = when {
            pending.isEmpty() -> "📝 Koi pending task nahi"
            pending.size == 1 -> "📝 1 pending task: ${pending.first()}"
            else -> "📝 ${pending.size} pending tasks"
        }
    }

    /** SYSTEM card: real voice-readiness + memory state. */
    private fun refreshSystem() {
        val ctx = context ?: return
        val connected = vm.isConnected.value == true
        val micOk = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        sysVoiceState?.text = when {
            connected -> "● Listening…"
            Prefs.apiKey.isBlank() -> "● Voice: API key needed"
            !micOk -> "● Voice: mic permission off"
            else -> "● Voice ready"
        }
        sysMemoryState?.text =
            if (Prefs.memoryEnabled) "● Memory active" else "● Memory off"
    }

    private fun updateCameraLabel() {
        val ctx = context ?: return
        val on = CameraVision.isEnabled(ctx)
        cameraBtn?.text = if (on) "📷 Camera: ON" else "📷 Camera: OFF"
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        reactor = null
        statusText = null
        cameraBtn = null
        sysVoiceState = null
        sysMemoryState = null
        briefDate = null
        briefTasks = null
        super.onDestroyView()
    }
}
