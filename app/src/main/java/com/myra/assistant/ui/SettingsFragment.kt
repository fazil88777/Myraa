package com.myra.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.myra.assistant.R
import com.myra.assistant.service.FloatingOrbService
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs

/**
 * Settings tab: API key, Aura Control (orb design + wallpaper),
 * permissions, call screening role, floating orb toggle, about.
 */
class SettingsFragment : Fragment() {

    private var orbRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val act = requireActivity()

        val apiInput = view.findViewById<EditText>(R.id.settingsApiKeyInput)
        apiInput.setText(Prefs.apiKey)
        view.findViewById<Button>(R.id.settingsSaveKeyButton).setOnClickListener {
            Prefs.apiKey = apiInput.text.toString().trim()
            toast("API key saved")
        }

        // ---- AURA CONTROL: orb design (home screen orb changes) ----
        view.findViewById<Button>(R.id.auraOrbCrimson).setOnClickListener {
            Prefs.orbDesign = "crimson"
            toast("Orb: Crimson 🔴 — Home par dekho")
        }
        view.findViewById<Button>(R.id.auraOrbAzure).setOnClickListener {
            Prefs.orbDesign = "azure"
            toast("Orb: Azure 🔵 — Home par dekho")
        }
        view.findViewById<Button>(R.id.auraOrbViolet).setOnClickListener {
            Prefs.orbDesign = "violet"
            toast("Orb: Violet 🟣 — Home par dekho")
        }

        // ---- AURA CONTROL: wallpaper (home screen background) ----
        view.findViewById<Button>(R.id.auraWpMidnight).setOnClickListener {
            Prefs.wallpaper = "midnight"
            toast("Wallpaper: Midnight 🌑 — Home par dekho")
        }
        view.findViewById<Button>(R.id.auraWpCrimson).setOnClickListener {
            Prefs.wallpaper = "crimson"
            toast("Wallpaper: Crimson 🔴 — Home par dekho")
        }
        view.findViewById<Button>(R.id.auraWpAzure).setOnClickListener {
            Prefs.wallpaper = "azure"
            toast("Wallpaper: Azure 🔵 — Home par dekho")
        }

        view.findViewById<Button>(R.id.settingsPermissionsButton).setOnClickListener {
            PermissionHelper.requestCore(act, 1001)
            if (!PermissionHelper.canDrawOverlay(act)) {
                PermissionHelper.openOverlaySettings(act)
            }
            if (!PermissionHelper.isAccessibilityEnabled(act)) {
                PermissionHelper.openAccessibilitySettings(act)
            }
        }

        view.findViewById<Button>(R.id.settingsCallRoleButton).setOnClickListener {
            PermissionHelper.requestCallScreeningRole(act)
        }

        val orbBtn = view.findViewById<Button>(R.id.settingsOrbButton)
        orbRunning = Prefs.orbEnabled
        orbBtn.text = if (orbRunning) "Floating Orb: ON" else "Floating Orb: OFF"
        orbBtn.setOnClickListener {
            val i = Intent(act, FloatingOrbService::class.java)
            if (orbRunning) {
                act.stopService(i)
                orbRunning = false
            } else {
                ContextCompat.startForegroundService(act, i)
                orbRunning = true
            }
            Prefs.orbEnabled = orbRunning
            orbBtn.text = if (orbRunning) "Floating Orb: ON" else "Floating Orb: OFF"
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = PermissionHelper.isAccessibilityEnabled(requireContext())
        view?.findViewById<TextView>(R.id.settingsA11yStatus)?.apply {
            text = if (ok) "Accessibility: ON ✓" else "Accessibility: OFF ✗"
            setTextColor(if (ok) 0xFF69F0AE.toInt() else 0xFFFF8A80.toInt())
        }
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
