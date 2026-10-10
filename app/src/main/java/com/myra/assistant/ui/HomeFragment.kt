package com.myra.assistant.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.util.Prefs

/**
 * Nova-style home tab: looping background video, big rotating glow orb
 * (tap = talk), assistant name, bottom input bar (tap = talk).
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var bgVideo: VideoView? = null

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
        orb.orbColor = 0xFF35C4FF.toInt()
        orb.reducedMotion = Prefs.reducedMotion
        orb.setOnClickListener { act?.toggleVoiceSession() }
        vm.isConnected.observe(viewLifecycleOwner) { orb.active = it == true }

        bgVideo = view.findViewById(R.id.bgVideo)
        bgVideo?.setVideoURI(
            Uri.parse("android.resource://${requireContext().packageName}/${R.raw.myra_home_bg}")
        )
        bgVideo?.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
        }

        val talk = View.OnClickListener { act?.toggleVoiceSession() }
        view.findViewById<View>(R.id.inputBar).setOnClickListener(talk)
        view.findViewById<View>(R.id.inputMic).setOnClickListener(talk)
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<ParticleOrbView>(R.id.particleOrb)?.let {
            it.orbColor = 0xFF35C4FF.toInt()
            it.reducedMotion = Prefs.reducedMotion
        }
        try {
            bgVideo?.start()
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        try {
            bgVideo?.pause()
        } catch (_: Exception) {
        }
        super.onPause()
    }
}
