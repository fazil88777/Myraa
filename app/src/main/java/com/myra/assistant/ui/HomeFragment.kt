package com.myra.assistant.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import kotlin.random.Random

/**
 * MYRA home tab (reference-style): big blue girl art, large glowing mic orb
 * with concentric rings, OFF/LISTEN state text, animated recording bars on
 * the sides. Clean dark background — no video, no input bar.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var micStateText: TextView? = null
    private var glowRing: ImageView? = null
    private var micOrb: View? = null
    private var barsLeft: WaveformView? = null
    private var barsRight: WaveformView? = null

    private var ringAnimator: ObjectAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
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
        micOrb = view.findViewById(R.id.micButton)
        barsLeft = view.findViewById(R.id.barsLeft)
        barsRight = view.findViewById(R.id.barsRight)
        barsLeft?.barColor = 0xFF35C4FF.toInt()
        barsRight?.barColor = 0xFF9B5CF6.toInt()

        view.findViewById<View>(R.id.micButton).setOnClickListener {
            act?.toggleVoiceSession()
        }

        vm.isConnected.observe(viewLifecycleOwner) { setListening(it == true) }
        setListening(vm.isConnected.value == true)
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
            // Gentle pulse on the mic orb while listening.
            if (pulseAnimator == null) {
                pulseAnimator = ObjectAnimator.ofFloat(micOrb, "scaleX", 1f, 1.07f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                }
                // scaleY follows scaleX
                pulseAnimator?.addUpdateListener {
                    micOrb?.scaleY = micOrb?.scaleX ?: 1f
                }
            }
            if (pulseAnimator?.isRunning != true) pulseAnimator?.start()
            if (!barsRunning) {
                barsRunning = true
                barHandler.post(barTicker)
            }
        } else {
            micStateText?.text = "OFF"
            micStateText?.setTextColor(0xFF6A7B8A.toInt())
            glowRing?.visibility = View.INVISIBLE
            ringAnimator?.cancel()
            pulseAnimator?.cancel()
            micOrb?.scaleX = 1f
            micOrb?.scaleY = 1f
            barsRunning = false
            barHandler.removeCallbacks(barTicker)
        }
    }

    override fun onPause() {
        barsRunning = false
        barHandler.removeCallbacks(barTicker)
        ringAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onPause()
    }

    override fun onDestroyView() {
        barsRunning = false
        barHandler.removeCallbacks(barTicker)
        ringAnimator?.cancel()
        pulseAnimator?.cancel()
        ringAnimator = null
        pulseAnimator = null
        micStateText = null
        glowRing = null
        micOrb = null
        barsLeft = null
        barsRight = null
        super.onDestroyView()
    }
}
