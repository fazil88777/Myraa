package com.myra.assistant.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import kotlin.random.Random

/**
 * MYRA home tab: blue robot-girl art, big mic button with OFF/LISTEN state,
 * animated recording bars on the sides and a rotating blue/purple glow ring
 * while the voice session is live.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var bgVideo: VideoView? = null
    private var micStateText: TextView? = null
    private var glowRing: ImageView? = null
    private var barsLeft: WaveformView? = null
    private var barsRight: WaveformView? = null

    private var ringAnimator: ObjectAnimator? = null
    private val barHandler = Handler(Looper.getMainLooper())
    private var barsRunning = false
    private val barTicker = object : Runnable {
        override fun run() {
            if (!barsRunning) return
            val a = 0.25f + Random.nextFloat() * 0.75f
            val b = 0.25f + Random.nextFloat() * 0.75f
            barsLeft?.pushAmplitude(a)
            barsRight?.pushAmplitude(b)
            barHandler.postDelayed(this, 120)
        }
    }

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

        micStateText = view.findViewById(R.id.micStateText)
        glowRing = view.findViewById(R.id.micGlowRing)
        barsLeft = view.findViewById(R.id.barsLeft)
        barsRight = view.findViewById(R.id.barsRight)
        barsLeft?.barColor = 0xFF35C4FF.toInt()
        barsRight?.barColor = 0xFF9B5CF6.toInt()

        val talk = View.OnClickListener { act?.toggleVoiceSession() }
        view.findViewById<View>(R.id.micButton).setOnClickListener(talk)
        view.findViewById<View>(R.id.inputBar).setOnClickListener(talk)
        view.findViewById<View>(R.id.inputMic).setOnClickListener(talk)

        vm.isConnected.observe(viewLifecycleOwner) { setListening(it == true) }
        setListening(vm.isConnected.value == true)

        bgVideo = view.findViewById(R.id.bgVideo)
        bgVideo?.setVideoURI(
            Uri.parse("android.resource://${requireContext().packageName}/${R.raw.myra_home_bg}")
        )
        bgVideo?.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
        }
    }

    private fun setListening(listening: Boolean) {
        if (listening) {
            micStateText?.text = "LISTEN"
            micStateText?.setTextColor(0xFF35C4FF.toInt())
            glowRing?.visibility = View.VISIBLE
            if (ringAnimator == null) {
                ringAnimator = ObjectAnimator.ofFloat(glowRing, "rotation", 0f, 360f).apply {
                    duration = 2400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
            }
            if (ringAnimator?.isRunning != true) ringAnimator?.start()
            if (!barsRunning) {
                barsRunning = true
                barHandler.post(barTicker)
            }
        } else {
            micStateText?.text = "OFF"
            micStateText?.setTextColor(0xFF6A7B8A.toInt())
            glowRing?.visibility = View.INVISIBLE
            ringAnimator?.cancel()
            barsRunning = false
            barHandler.removeCallbacks(barTicker)
        }
    }

    override fun onResume() {
        super.onResume()
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
        barsRunning = false
        barHandler.removeCallbacks(barTicker)
        ringAnimator?.cancel()
        super.onPause()
    }

    override fun onDestroyView() {
        barsRunning = false
        barHandler.removeCallbacks(barTicker)
        ringAnimator?.cancel()
        ringAnimator = null
        bgVideo = null
        micStateText = null
        glowRing = null
        barsLeft = null
        barsRight = null
        super.onDestroyView()
    }
}
