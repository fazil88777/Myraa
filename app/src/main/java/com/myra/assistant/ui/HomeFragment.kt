package com.myra.assistant.ui

import android.content.Intent
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
import com.myra.assistant.service.HotwordService
import com.myra.assistant.util.HotwordStore
import com.myra.assistant.util.LiaStyle
import com.myra.assistant.util.Prefs

/**
 * LIA-style home tab: left pill menu, VIP particle orb (tap = talk),
 * status pill, quick actions grid.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel

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

        val orb = view.findViewById<ParticleOrbView>(R.id.particleOrb)
        orb.orbColor = LiaStyle.orbColor(Prefs.orbColor).color
        orb.reducedMotion = Prefs.reducedMotion
        orb.setOnClickListener { act?.toggleVoiceSession() }

        view.findViewById<TextView>(R.id.homeTitleText).text =
            "${Prefs.assistantName.ifBlank { "MYRA" }} VOICE ASSISTANT"

        val statusPill = view.findViewById<TextView>(R.id.homeStatusPill)
        vm.statusText.observe(viewLifecycleOwner) { statusPill.text = "● $it" }
        vm.isConnected.observe(viewLifecycleOwner) { orb.active = it == true }
        // ---- Quick actions ----
        view.findViewById<View>(R.id.qaAsk).setOnClickListener { act?.toggleVoiceSession() }

        view.findViewById<View>(R.id.qaShare).setOnClickListener { act?.toggleScreenShare() }

        view.findViewById<View>(R.id.qaReminder).setOnClickListener {
            toast("MYRA se kaho: 'Mujad ko subah 9 baje ye bhej dena' ⏰")
        }

        val hwSub = view.findViewById<TextView>(R.id.qaHotwordSub)
        fun refreshHw() {
            val on = HotwordStore.isEnabled(requireContext())
            hwSub.text = if (on) "hi ${Prefs.assistantName.ifBlank { "MYRA" }}: ON" else "hi MYRA: OFF"
        }
        refreshHw()
        view.findViewById<View>(R.id.qaHotword).setOnClickListener {
            val ctx = requireContext()
            val nowOn = !HotwordStore.isEnabled(ctx)
            HotwordStore.setEnabled(ctx, nowOn)
            try {
                val i = Intent(ctx, HotwordService::class.java)
                if (nowOn) {
                    i.action = HotwordService.ACTION_START
                    ContextCompat.startForegroundService(ctx, i)
                } else {
                    i.action = HotwordService.ACTION_STOP
                    ContextCompat.startForegroundService(ctx, i)
                }
            } catch (_: Exception) {
            }
            refreshHw()
            toast(if (nowOn) "Hotword ON — 'hi MYRA' bolo 👂" else "Hotword OFF")
        }

        view.findViewById<View>(R.id.qaYoutube).setOnClickListener {
            toast("Voice par kaho: 'full analyze batao' ▶")
        }

        view.findViewById<View>(R.id.qaMore).setOnClickListener { act?.selectTab("features") }

        // ---- Left pill menu ----
        view.findViewById<View>(R.id.pillMemory).setOnClickListener {
            val mem = if (Prefs.memoryEnabled) "ON" else "OFF"
            val name = Prefs.userName.ifBlank { "dost" }
            toast("Memory $mem 🧠 — tum: $name, style: ${LiaStyle.personalityLabel(Prefs.personality)}")
        }
        view.findViewById<View>(R.id.pillChat).setOnClickListener { act?.selectTab("chat") }
        view.findViewById<View>(R.id.pillSoul).setOnClickListener {
            // Cycle personality
            val keys = LiaStyle.PERSONALITIES.map { it.key }
            val next = keys[(keys.indexOf(Prefs.personality) + 1).coerceAtLeast(0) % keys.size]
            Prefs.personality = next
            if (act != null && act.isVoiceLive()) {
                toast("Soul: ${LiaStyle.personalityLabel(next)} ✨ (session restart ho raha hai)")
                act.restartVoiceSession()
            } else {
                toast("Soul: ${LiaStyle.personalityLabel(next)} ✨")
            }
        }
        view.findViewById<View>(R.id.pillSettings).setOnClickListener { act?.selectTab("settings") }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply in case orb color / reduced-motion changed in Settings.
        view?.findViewById<ParticleOrbView>(R.id.particleOrb)?.let { orb ->
            orb.orbColor = LiaStyle.orbColor(Prefs.orbColor).color
            orb.reducedMotion = Prefs.reducedMotion
        }
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
}
