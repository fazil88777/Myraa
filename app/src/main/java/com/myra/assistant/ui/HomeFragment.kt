package com.myra.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.myra.assistant.R
import com.myra.assistant.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Home tab: MYRA's face = connect/disconnect, waveform, status,
 * screen share, location, conversation and message input.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private lateinit var adapter: ChatAdapter

    // Location permission is asked at most ONCE per view lifetime.
    // (Re-asking on every denial caused an infinite request loop
    //  and a StackOverflowError crash.)
    private var locationPermissionRequested = false

    private val waveLoop = Handler(Looper.getMainLooper())
    private var waveRunning = false
    private val waveTick = object : Runnable {
        override fun run() {
            if (!waveRunning) return
            val v = view?.findViewById<WaveformView>(R.id.waveformView)
            if (v != null) {
                val connected = vm.isConnected.value == true
                v.pushAmplitude(if (connected) 0.25f + Random.nextFloat() * 0.65f else 0.06f)
            }
            waveLoop.postDelayed(this, 140)
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
        adapter = ChatAdapter()

        val recycler = view.findViewById<RecyclerView>(R.id.homeChatRecycler)
        recycler.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        recycler.adapter = adapter

        val faceBtn = view.findViewById<ShapeableImageView>(R.id.powerFaceButton)
        val statusPill = view.findViewById<TextView>(R.id.homeStatusPill)
        val shareBtn = view.findViewById<Button>(R.id.homeScreenShareButton)
        val msgInput = view.findViewById<EditText>(R.id.homeMessageInput)
        val sendBtn = view.findViewById<Button>(R.id.homeSendButton)

        // Aura orb: design from Settings (crimson / azure / violet)
        val orbRes = when (Prefs.orbDesign) {
            "azure" -> R.drawable.orb_azure
            "violet" -> R.drawable.orb_violet
            else -> R.drawable.orb_crimson
        }
        faceBtn.setImageResource(orbRes)

        // Orb keeps rotating slowly (live animation)
        val orbSpin = android.animation.ObjectAnimator.ofFloat(faceBtn, "rotation", 0f, 360f).apply {
            duration = 24000L
            repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        orbSpin.start()

        // Home wallpaper from Settings (midnight / crimson / azure)
        val homeRoot = view.findViewById<android.widget.LinearLayout>(R.id.homeRoot)
        val wpRes = when (Prefs.wallpaper) {
            "crimson" -> R.drawable.wallpaper_crimson
            "azure" -> R.drawable.wallpaper_midnight
            else -> R.drawable.bg_app
        }
        homeRoot.background = ContextCompat.getDrawable(requireContext(), wpRes)

        // Tap MYRA's face = Connect / Disconnect
        faceBtn.setOnClickListener {
            if (vm.isConnected.value == true) {
                vm.stopSession()
            } else {
                val k = Prefs.apiKey
                if (k.isBlank()) {
                    Toast.makeText(
                        requireContext(),
                        "Pehle Settings mein API key save karo",
                        Toast.LENGTH_LONG
                    ).show()
                    (activity as? MainActivity)?.selectTab("settings")
                } else {
                    vm.startSession(k)
                }
            }
        }

        shareBtn.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }

        sendBtn.setOnClickListener {
            val msg = msgInput.text.toString()
            if (msg.isNotBlank()) {
                vm.sendTypedText(msg)
                msgInput.text.clear()
            }
        }

        vm.statusText.observe(viewLifecycleOwner) {
            statusPill.text = "● $it"
        }

        vm.messages.observe(viewLifecycleOwner) {
            adapter.submit(it)
            if (adapter.itemCount > 0) {
                recycler.scrollToPosition(adapter.itemCount - 1)
            }
        }

        vm.isSharing.observe(viewLifecycleOwner) { sharing ->
            shareBtn.text = if (sharing) "Screen Share: ON" else "Screen Share: OFF"
        }

        updateLocation(view)

        waveRunning = true
        waveLoop.post(waveTick)
    }

    override fun onDestroyView() {
        waveRunning = false
        waveLoop.removeCallbacks(waveTick)
        locationPermissionRequested = false
        super.onDestroyView()
    }

    // ---- Location (MAX style) ----

    private fun updateLocation(view: View) {
        val act = activity ?: return
        val tv = view.findViewById<TextView>(R.id.locationText) ?: return
        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        if (ContextCompat.checkSelfPermission(
                act,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Ask only once: if the user denies, just show the time label
            // instead of asking again (which crashed the app in a loop).
            if (!locationPermissionRequested) {
                locationPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3001)
            }
            tv.text = "GPS • $time"
            return
        }
        try {
            val lm = act.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) {
                tv.text = "GPS • $time"
                return
            }
            Thread {
                try {
                    val g = Geocoder(act, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val list = g.getFromLocation(loc.latitude, loc.longitude, 1)
                    val first = list?.firstOrNull()
                    val label = listOf(
                        first?.locality ?: first?.subAdminArea ?: "",
                        first?.countryName ?: ""
                    ).filter { it.isNotBlank() }.joinToString(", ")
                    act.runOnUiThread {
                        tv.text = if (label.isBlank()) "GPS • $time" else "$label\nGPS • $time"
                    }
                } catch (_: Exception) {
                    act.runOnUiThread { tv.text = "GPS • $time" }
                }
            }.start()
        } catch (_: Exception) {
            tv.text = "GPS • $time"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 3001 && view != null) {
            // Refresh the location label ONLY when the user granted it.
            // On denial do nothing — never auto-ask again.
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateLocation(requireView())
            }
        }
    }
}
