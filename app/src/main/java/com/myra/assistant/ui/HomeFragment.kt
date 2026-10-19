package com.myra.assistant.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MYRA home tab — two themes:
 *
 * • Minimal (designs 11/12/13/14): full-screen girl art with a slow
 *   "breathing" scale + drifting smoke overlay, ONE electric circle at knee
 *   height with OFF/LISTEN beneath it. Nothing else on screen.
 *   Long-press the circle for Chat / Tools / Profile (the nav bar is hidden
 *   on this screen).
 *
 * • Dashboard (design 2): girl art, greeting card, 4 quick-action chips,
 *   mic circle. The activity bottom nav stays visible for this theme.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var boundDesign = ""
    private var isDashboard = false

    private var girlArtFull: ImageView? = null
    private var smokeView: SmokeDriftView? = null
    private var electricCircle: ElectricCircleView? = null
    private var micStateText: TextView? = null
    private var lastErrorShown: String? = null
    private var breathSet: AnimatorSet? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isDashboard = Prefs.homeDesign == "2"
        val layout =
            if (isDashboard) R.layout.fragment_home_dashboard else R.layout.fragment_home
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        boundDesign = Prefs.homeDesign
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        if (isDashboard) bindDashboard(view) else bindMinimal(view)

        vm.isConnected.observe(viewLifecycleOwner) { setListening(it == true) }
        // Surface session errors so a failed start is never silent.
        vm.statusText.observe(viewLifecycleOwner) { s ->
            if (s == null || s == lastErrorShown) return@observe
            val low = s.lowercase()
            if (low.contains("error") || low.contains("off hai") || low.contains("lost")) {
                lastErrorShown = s
                Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
            }
        }
        setListening(vm.isConnected.value == true)
    }

    override fun onResume() {
        super.onResume()
        // Design changed in Settings while we were away → rebuild home.
        if (Prefs.homeDesign != boundDesign) {
            (activity as? MainActivity)?.selectTab("home")
            return
        }
        smokeView?.start()
        if (!isDashboard && !Prefs.reducedMotion) startBreathing()
    }

    override fun onPause() {
        stopBreathing()
        smokeView?.stop()
        super.onPause()
    }

    // ---------- minimal theme ----------

    /** art drawable, circle glow tint, smoke tint per design. */
    private fun themeSpec(key: String): Triple<Int, Int, Int> = when (key) {
        "11" -> Triple(R.drawable.home_design_11, 0xFFFF4D5E.toInt(), 0xFFFF4D5E.toInt())
        "12" -> Triple(R.drawable.home_design_12, 0xFF3DFF88.toInt(), 0xFF2ECC71.toInt())
        "13" -> Triple(R.drawable.home_design_13, 0xFFFFB340.toInt(), 0xFFFF9E40.toInt())
        else -> Triple(R.drawable.home_design_14, 0xFFF04FD8.toInt(), 0xFF9B5CF6.toInt())
    }

    private fun bindMinimal(view: View) {
        val act = activity as? MainActivity
        girlArtFull = view.findViewById(R.id.girlArtFull)
        smokeView = view.findViewById(R.id.smokeView)
        electricCircle = view.findViewById(R.id.electricCircle)
        micStateText = view.findViewById(R.id.micStateText)

        val (art, glow, smoke) = themeSpec(Prefs.homeDesign)
        girlArtFull?.setImageResource(art)
        electricCircle?.glowTint = glow
        smokeView?.smokeColor = smoke

        // Pivot at center so the "breathing" scale feels natural.
        girlArtFull?.post {
            val v = girlArtFull ?: return@post
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
        }

        electricCircle?.setOnClickListener { act?.toggleVoiceSession() }
        // No nav bar on this screen — long-press the circle to reach the rest.
        electricCircle?.setOnLongClickListener { showNavMenu(); true }
    }

    private fun showNavMenu() {
        val act = activity as? MainActivity ?: return
        val labels = arrayOf("💬 Chat", "🧰 Tools", "👤 Profile")
        val tabs = arrayOf("chat", "tools", "profile")
        AlertDialog.Builder(requireContext())
            .setTitle("MYRA")
            .setItems(labels) { _, which -> act.selectTab(tabs[which]) }
            .show()
    }

    /** Slow "breathing": gentle scale 1.00→1.04 plus a slight drift, ~9s cycle. */
    private fun startBreathing() {
        val v = girlArtFull ?: return
        if (breathSet != null) return
        val sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.04f).apply {
            duration = 4500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.04f).apply {
            duration = 4500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val drift = ObjectAnimator.ofFloat(v, "translationX", 0f, 14f, 0f, -14f, 0f).apply {
            duration = 18000
            repeatCount = ValueAnimator.INFINITE
        }
        breathSet = AnimatorSet().apply { playTogether(sx, sy, drift); start() }
    }

    private fun stopBreathing() {
        breathSet?.cancel()
        breathSet = null
        girlArtFull?.let {
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
        }
    }

    // ---------- dashboard theme (#2) ----------

    private fun bindDashboard(view: View) {
        val act = activity as? MainActivity
        electricCircle = view.findViewById(R.id.electricCircleDash)
        micStateText = view.findViewById(R.id.micStateTextDash)
        electricCircle?.glowTint = 0xFF35C4FF.toInt()
        electricCircle?.setOnClickListener { act?.toggleVoiceSession() }

        val name = Prefs.userName.ifBlank { "Dost" }
        view.findViewById<TextView>(R.id.dashGreeting)?.text =
            "Hello $name, main MYRA hoon"

        view.findViewById<View>(R.id.chipCall)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (_: Exception) {
                toast("Dialer nahi khul saka")
            }
        }
        view.findViewById<View>(R.id.chipMessage)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:")))
            } catch (_: Exception) {
                toast("SMS app nahi khul saki")
            }
        }
        view.findViewById<View>(R.id.chipYouTube)?.setOnClickListener {
            val i = requireContext().packageManager
                .getLaunchIntentForPackage("com.google.android.youtube")
            if (i != null) startActivity(i) else toast("YouTube app nahi mili")
        }
        view.findViewById<View>(R.id.chipTime)?.setOnClickListener {
            val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            toast("Time: $t")
        }
    }

    // ---------- shared ----------

    private fun setListening(listening: Boolean) {
        if (listening) {
            micStateText?.text = "LISTEN"
            micStateText?.setTextColor(0xFF35C4FF.toInt())
            electricCircle?.active = true
        } else {
            micStateText?.text = "OFF"
            micStateText?.setTextColor(0xFF8A97A5.toInt())
            electricCircle?.active = false
        }
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        stopBreathing()
        smokeView?.stop()
        girlArtFull = null
        smokeView = null
        electricCircle = null
        micStateText = null
        super.onDestroyView()
    }
}
