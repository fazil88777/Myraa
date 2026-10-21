package com.myra.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.util.MyraMemory
import com.myra.assistant.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MYRA home — sci-fi dashboard (the design Fazil approved from the
 * reference layout, rebuilt natively for MYRA):
 *
 * • Header: "MYRA / AI Assistant" with dash sparks orbiting the title and
 *   a small cyan underline dash under the subtitle.
 * • Middle: left pills (MEMORY / CHAT / SOUL / SETTINGS — SOUL opens her
 *   Tools/Features, where her abilities live) with straight light beams
 *   in each pill's color running to the glowing reactor orb on the right.
 *   The reactor IS the voice button — tap starts the session
 *   (status "live"), tap again stops ("ready"). Status text under the
 *   reactor mirrors the real session: ready → connecting live… → live.
 * • Under the status: a wide SCREEN SHARE bar wired to MYRA's real
 *   screen-share (same toggle as the voice control).
 * • Cards: INTELLIGENCE BRIEF (today + pending tasks from MyraMemory,
 *   "+ MORE" opens the Memory screen) and SYSTEM (real voice/memory state).
 * • Own dot-style bottom nav (Home / Chat / Voice / Memory / History /
 *   Settings — History opens Tools/Features). The neon border is drawn
 *   once at the activity level, framing every screen.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var reactor: ReactorView? = null
    private var statusText: TextView? = null
    private var screenShareBtn: TextView? = null
    private var sysVoiceState: TextView? = null
    private var sysMemoryState: TextView? = null
    private var briefDate: TextView? = null
    private var briefTasks: TextView? = null
    private var lastErrorShown: String? = null
    private var wasConnected = false

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
        screenShareBtn = view.findViewById(R.id.screenShareBtn)
        sysVoiceState = view.findViewById(R.id.sysVoiceState)
        sysMemoryState = view.findViewById(R.id.sysMemoryState)
        briefDate = view.findViewById(R.id.briefDate)
        briefTasks = view.findViewById(R.id.briefTasks)

        // Reactor = voice button (same toggle the old electric circle used).
        reactor?.setOnClickListener { act?.toggleVoiceSession() }

        // Pills. (SOUL -> Tools/Features tab: that's where her abilities live.)
        view.findViewById<View>(R.id.pillMemory).setOnClickListener { act?.selectTab("memory") }
        view.findViewById<View>(R.id.pillChat).setOnClickListener { act?.selectTab("chat") }
        view.findViewById<View>(R.id.pillSoul).setOnClickListener { act?.selectTab("tools") }
        view.findViewById<View>(R.id.pillSettings).setOnClickListener { act?.selectTab("profile") }

        // Pills span ~45% of the screen width, like the reference design.
        view.post {
            val w = (view.width * 0.45f).toInt()
            if (w > 0) {
                listOf(R.id.pillMemory, R.id.pillChat, R.id.pillSoul, R.id.pillSettings)
                    .forEach { id ->
                        val pill = view.findViewById<View>(id)
                        val lp = pill.layoutParams
                        lp.width = w
                        pill.layoutParams = lp
                    }
            }
        }

        // Dashboard bottom nav. (History -> Tools/Features tab, the same
        // mapping MainActivity's own navHistory uses.)
        view.findViewById<View>(R.id.navHome).setOnClickListener { act?.selectTab("home") }
        view.findViewById<View>(R.id.navChat).setOnClickListener { act?.selectTab("chat") }
        view.findViewById<View>(R.id.navVoice).setOnClickListener { act?.selectTab("home") }
        view.findViewById<View>(R.id.navMemory).setOnClickListener { act?.selectTab("memory") }
        view.findViewById<View>(R.id.navHistory).setOnClickListener { act?.selectTab("tools") }
        view.findViewById<View>(R.id.navSettings).setOnClickListener { act?.selectTab("profile") }

        // Intelligence Brief card -> Memory screen.
        view.findViewById<View>(R.id.briefCard).setOnClickListener { act?.selectTab("memory") }
        view.findViewById<View>(R.id.briefMore).setOnClickListener { act?.selectTab("memory") }

        // Screen Share bar: MYRA's real screen share (MediaProjection),
        // the same toggle the voice control drives.
        screenShareBtn?.setOnClickListener { act?.toggleScreenShare() }

        vm.isConnected.observe(viewLifecycleOwner) { connected ->
            // Announce a fresh successful connect, like the reference app.
            if (connected == true && !wasConnected) {
                Toast.makeText(requireContext(), "Conversation ON", Toast.LENGTH_SHORT).show()
            }
            wasConnected = connected == true
            updateStatus()
        }
        vm.isSharing.observe(viewLifecycleOwner) { updateShareLabel() }
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
        updateShareLabel()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshBrief()
        refreshSystem()
        updateShareLabel()
    }

    /** Reactor glow + status word follow the real session state. */
    private fun updateStatus() {
        if (!::vm.isInitialized) return
        val connected = vm.isConnected.value == true
        reactor?.active = connected
        val raw = vm.statusText.value ?: ""
        statusText?.text = when {
            connected -> "live"
            raw.startsWith("Connecting") || raw.startsWith("Reconnecting") -> "connecting live…"
            else -> "ready"
        }
        if (view != null) refreshSystem()
    }

    /** SCREEN SHARE bar label follows the real sharing state. */
    private fun updateShareLabel() {
        if (!::vm.isInitialized) return
        val on = vm.isSharing.value == true
        screenShareBtn?.text = if (on) "🖥 SCREEN SHARE: ON" else "🖥 SCREEN SHARE: OFF"
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
            connected -> "● Voice active"
            Prefs.apiKey.isBlank() -> "● Voice: API key needed"
            !micOk -> "● Voice: mic permission off"
            else -> "● Voice ready"
        }
        sysMemoryState?.text =
            if (Prefs.memoryEnabled) "● Memory active" else "● Memory off"
    }

    override fun onDestroyView() {
        reactor = null
        statusText = null
        screenShareBtn = null
        sysVoiceState = null
        sysMemoryState = null
        briefDate = null
        briefTasks = null
        super.onDestroyView()
    }
}
