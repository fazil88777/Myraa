package com.myra.assistant.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import kotlin.random.Random

/**
 * MYRA home tab: big blue girl art, small rotating electric circle
 * (tap toggles the voice session), OFF/LISTEN state text below it,
 * animated recording bars on the sides. Clean dark background.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var micStateText: TextView? = null
    private var electricCircle: ElectricCircleView? = null
    private var barsLeft: WaveformView? = null
    private var barsRight: WaveformView? = null
    private var lastErrorShown: String? = null

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
        electricCircle = view.findViewById(R.id.electricCircle)
        barsLeft = view.findViewById(R.id.barsLeft)
        barsRight = view.findViewById(R.id.barsRight)
        barsLeft?.barColor = 0xFF35C4FF.toInt()
        barsRight?.barColor = 0xFF9B5CF6.toInt()

        electricCircle?.setOnClickListener {
            act?.toggleVoiceSession()
        }

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

    private fun setListening(listening: Boolean) {
        if (listening) {
            micStateText?.text = "LISTEN"
            micStateText?.setTextColor(0xFF35C4FF.toInt())
            electricCircle?.active = true
            if (!barsRunning) {
                barsRunning = true
                barHandler.post(barTicker)
            }
        } else {
            micStateText?.text = "OFF"
            micStateText?.setTextColor(0xFF6A7B8A.toInt())
            electricCircle?.active = false
            barsRunning = false
            barHandler.removeCallbacks(barTicker)
        }
    }

    override fun onPause() {
        barsRunning = false
        barHandler.removeCallbacks(barTicker)
        super.onPause()
    }

    override fun onDestroyView() {
        barsRunning = false
        barHandler.removeCallbacks(barTicker)
        micStateText = null
        electricCircle = null
        barsLeft = null
        barsRight = null
        super.onDestroyView()
    }
}
