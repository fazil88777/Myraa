package com.myra.assistant.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.myra.assistant.R
import com.myra.assistant.service.FloatingOrbService
import com.myra.assistant.service.HotwordService
import com.myra.assistant.service.PcLink
import com.myra.assistant.util.AuthManager
import com.myra.assistant.util.HotwordStore
import com.myra.assistant.util.LiaStyle
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs

/**
 * LIA-style settings: profile, AI & Voice, AI Provider, Appearance,
 * Conversation & Memory, Automation & Permissions, PC Connection,
 * Privacy & Security, About & Diagnostics.
 */
class SettingsFragment : Fragment() {

    private var updating = false
    private lateinit var vm: MainViewModel

    private val googleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                toast("Login cancelled")
                return@registerForActivityResult
            }
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (Prefs.userName.isBlank() && !account.displayName.isNullOrBlank()) {
                    Prefs.userName = account.displayName!!.trim()
                }
                refreshProfile()
                toast("Login ho gaya! ✅")
            } catch (e: ApiException) {
                toast("Login failed (${e.statusCode})")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val act = requireActivity()

        refreshProfile()
        view.findViewById<View>(R.id.profileEditBtn).setOnClickListener {
            textDialog("Tumhara naam", Prefs.userName, "Apna naam likho") {
                Prefs.userName = it
                refreshProfile()
                toast("Name saved!")
            }
        }
        // Tapping the profile card = Gmail login / logout
        view.findViewById<View>(R.id.profileAvatar).setOnClickListener { googleAccountTap() }

        // ---- AI & VOICE ----
        view.findViewById<View>(R.id.rowApiKey).setOnClickListener {
            textDialog("Gemini API Key", Prefs.apiKey, "AI Studio se key paste karo") {
                Prefs.apiKey = it
                refreshAll()
                toast("API key saved")
            }
        }
        view.findViewById<View>(R.id.rowAssistantName).setOnClickListener {
            textDialog("Assistant Name", Prefs.assistantName.ifBlank { "MYRA" }, "MYRA") {
                Prefs.assistantName = it.ifBlank { "MYRA" }
                refreshAll()
                toast("Assistant ka naam: ${Prefs.assistantName}")
            }
        }
        view.findViewById<View>(R.id.rowPersonality).setOnClickListener {
            val items = LiaStyle.PERSONALITIES
            choiceDialog(
                "Personality",
                items.map { it.label },
                items.indexOfFirst { it.key == Prefs.personality }.coerceAtLeast(0)
            ) { which ->
                Prefs.personality = items[which].key
                refreshAll()
                toast("${items[which].label} — naye session se lagega ✨")
            }
        }
        view.findViewById<View>(R.id.rowVoice).setOnClickListener {
            val items = LiaStyle.VOICES
            choiceDialog(
                "Voice",
                items.map { it.label },
                items.indexOfFirst { it.key == Prefs.voiceName }.coerceAtLeast(0)
            ) { which ->
                Prefs.voiceName = items[which].key
                refreshAll()
                toast("${items[which].label} — naye session se lagegi 🎙")
            }
        }
        view.findViewById<View>(R.id.rowLanguage).setOnClickListener {
            val items = LiaStyle.LANGS
            choiceDialog(
                "Language",
                items.map { it.label },
                items.indexOfFirst { it.key == Prefs.language }.coerceAtLeast(0)
            ) { which ->
                Prefs.language = items[which].key
                refreshAll()
            }
        }
        view.findViewById<View>(R.id.rowSpeechSpeed).setOnClickListener {
            val items = LiaStyle.SPEEDS
            choiceDialog(
                "Speech Speed",
                items.map { LiaStyle.speedLabel(it) },
                items.indexOfFirst { it == Prefs.speechSpeed }.coerceAtLeast(1)
            ) { which ->
                Prefs.speechSpeed = items[which]
                refreshAll()
                toast("${LiaStyle.speedLabel(items[which])} — naye session se")
            }
        }

        val swWake = view.findViewById<Switch>(R.id.switchWakeWord)
        swWake.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            setHotword(on)
        }
        val swAuto = view.findViewById<Switch>(R.id.switchAutoListening)
        swAuto.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.autoListening = on
            toast(if (on) "App khulte hi sunna shuru 👂" else "Auto Listening OFF")
        }

        // ---- AI PROVIDER: connection status (live) ----
        vm.isConnected.observe(viewLifecycleOwner) { refreshConnStatus() }
        vm.statusText.observe(viewLifecycleOwner) { refreshConnStatus() }

        // ---- APPEARANCE ----
        view.findViewById<View>(R.id.rowTheme).setOnClickListener {
            val items = LiaStyle.THEMES
            choiceDialog(
                "Theme",
                items.map { it.label },
                items.indexOfFirst { it.key == Prefs.theme }.coerceAtLeast(0)
            ) { which ->
                Prefs.theme = items[which].key
                refreshAll()
                toast("${items[which].label} — dobara kholo to poora lagega 🎨")
            }
        }
        view.findViewById<View>(R.id.rowOrbStyle).setOnClickListener {
            val items = LiaStyle.ORB_COLORS
            choiceDialog(
                "Orb Style",
                items.map { it.label },
                items.indexOfFirst { it.key == Prefs.orbColor }.coerceAtLeast(0)
            ) { which ->
                Prefs.orbColor = items[which].key
                refreshAll()
                toast("Orb: ${items[which].label} — Home par dekho ✨")
            }
        }
        val swMotion = view.findViewById<Switch>(R.id.switchReducedMotion)
        swMotion.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.reducedMotion = on
        }

        // ---- CONVERSATION & MEMORY ----
        val swMem = view.findViewById<Switch>(R.id.switchMemory)
        swMem.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.memoryEnabled = on
        }
        vm.messages.observe(viewLifecycleOwner) { list ->
            view.findViewById<TextView>(R.id.rowConvHistoryValue).text =
                "${list.size} messages"
        }
        view.findViewById<View>(R.id.rowClearHistory).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear history?")
                .setMessage("Saari conversation delete ho jayegi.")
                .setPositiveButton("Clear") { _, _ ->
                    vm.clearMessages()
                    toast("History cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---- AUTOMATION & PERMISSIONS ----
        view.findViewById<View>(R.id.rowQuickActions).setOnClickListener {
            (activity as? MainActivity)?.selectTab("features")
        }
        val swOrb = view.findViewById<Switch>(R.id.switchFloatingOrb)
        swOrb.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            val i = Intent(act, FloatingOrbService::class.java)
            if (on) ContextCompat.startForegroundService(act, i) else act.stopService(i)
            Prefs.orbEnabled = on
        }
        view.findViewById<View>(R.id.rowCallRole).setOnClickListener {
            PermissionHelper.requestCallScreeningRole(act)
        }
        view.findViewById<View>(R.id.rowAppPermissions).setOnClickListener {
            PermissionHelper.requestCore(act, 1001)
            if (!PermissionHelper.canDrawOverlay(act)) PermissionHelper.openOverlaySettings(act)
        }
        view.findViewById<View>(R.id.rowA11yStatus).setOnClickListener {
            // LIA jaisa: app ki setting se seedha phone ki Accessibility settings khulti hai
            PermissionHelper.openAccessibilitySettings(act)
        }

        // ---- PC CONNECTION ----
        val swPc = view.findViewById<Switch>(R.id.switchPc)
        swPc.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.pcEnabled = on
            if (on) PcLink.start(requireContext()) else PcLink.stop()
            refreshAll()
            toast(if (on) "PC Link ON 🖥" else "PC Link OFF")
        }

        // ---- PRIVACY & ABOUT ----
        view.findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Privacy & Data Controls")
                .setMessage(
                    "• Tumhari API key sirf tumhare phone mein save hoti hai.\n" +
                            "• Voice chat Google Gemini Live se hoti hai.\n" +
                            "• MYRA tumhari baatein kahin upload nahi karti."
                )
                .setPositiveButton("OK", null)
                .show()
        }
        view.findViewById<View>(R.id.rowAbout).setOnClickListener {
            val ver = try {
                act.packageManager.getPackageInfo(act.packageName, 0).versionName
            } catch (_: Exception) { "?" }
            AlertDialog.Builder(requireContext())
                .setTitle("About ${Prefs.assistantName.ifBlank { "MYRA" }}")
                .setMessage(
                    "Version $ver\nAI voice assistant — Google Gemini Live.\nBanane wala: Fazil 💪"
                )
                .setPositiveButton("OK", null)
                .show()
        }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun setHotword(on: Boolean) {
        val ctx = requireContext()
        HotwordStore.setEnabled(ctx, on)
        try {
            val i = Intent(ctx, HotwordService::class.java)
            i.action = if (on) HotwordService.ACTION_START else HotwordService.ACTION_STOP
            ContextCompat.startForegroundService(ctx, i)
        } catch (_: Exception) {
        }
        toast(if (on) "Hotword ON — 'hi MYRA' bolo 👂" else "Hotword OFF")
    }

    private fun googleAccountTap() {
        val account = AuthManager.signedInAccount(requireContext())
        if (account != null) {
            AlertDialog.Builder(requireContext())
                .setTitle("Logout?")
                .setMessage("Gmail se logout karna hai?")
                .setPositiveButton("Logout") { _, _ ->
                    AuthManager.signOut(requireContext()) { refreshProfile() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            googleLauncher.launch(AuthManager.client(requireContext()).signInIntent)
        }
    }

    private fun refreshProfile() {
        val v = view ?: return
        val account = AuthManager.signedInAccount(requireContext())
        v.findViewById<TextView>(R.id.profileName).text =
            Prefs.userName.ifBlank { account?.displayName ?: "Dost" }
        v.findViewById<TextView>(R.id.profileEmail).text =
            account?.email ?: "Tap avatar for Gmail login"
        val first = (Prefs.userName.ifBlank { account?.displayName ?: "M" }).firstOrNull()
        v.findViewById<TextView>(R.id.profileAvatar).text =
            first?.uppercaseChar()?.toString() ?: "M"
    }

    private fun refreshConnStatus() {
        val v = view ?: return
        val tv = v.findViewById<TextView>(R.id.rowConnStatusValue)
        val connected = vm.isConnected.value == true
        tv.text = if (connected) "Connected ●" else (vm.statusText.value ?: "Idle")
        tv.setTextColor(if (connected) 0xFF69F0AE.toInt() else 0xFFFFB74D.toInt())
    }

    private fun refreshAll() {
        val v = view ?: return
        updating = true
        try {
            refreshProfile()
            v.findViewById<TextView>(R.id.rowApiKeyValue).text =
                if (Prefs.apiKey.isBlank()) "Not set" else "•••••• saved"
            v.findViewById<TextView>(R.id.rowAssistantNameValue).text =
                Prefs.assistantName.ifBlank { "MYRA" }
            v.findViewById<TextView>(R.id.rowPersonalityValue).text =
                LiaStyle.personalityLabel(Prefs.personality)
            v.findViewById<TextView>(R.id.rowVoiceValue).text =
                LiaStyle.voiceLabel(Prefs.voiceName)
            v.findViewById<TextView>(R.id.rowLanguageValue).text =
                LiaStyle.langLabel(Prefs.language)
            v.findViewById<TextView>(R.id.rowSpeechSpeedValue).text =
                LiaStyle.speedLabel(Prefs.speechSpeed)
            v.findViewById<Switch>(R.id.switchWakeWord).isChecked =
                HotwordStore.isEnabled(requireContext())
            v.findViewById<Switch>(R.id.switchAutoListening).isChecked = Prefs.autoListening
            v.findViewById<TextView>(R.id.rowThemeValue).text =
                LiaStyle.theme(Prefs.theme).label
            v.findViewById<TextView>(R.id.rowOrbStyleValue).text =
                LiaStyle.orbColor(Prefs.orbColor).label
            v.findViewById<Switch>(R.id.switchReducedMotion).isChecked = Prefs.reducedMotion
            v.findViewById<Switch>(R.id.switchMemory).isChecked = Prefs.memoryEnabled
            v.findViewById<Switch>(R.id.switchFloatingOrb).isChecked = Prefs.orbEnabled
            refreshConnStatus()
            refreshA11y(v)
            v.findViewById<Switch>(R.id.switchPc).isChecked = Prefs.pcEnabled
            v.findViewById<TextView>(R.id.rowPcUrlValue).text =
                if (PcLink.isRunning()) PcLink.url(requireContext()) else "Off"
        } finally {
            updating = false
        }
    }

    private fun refreshA11y(v: View) {
        val ok = PermissionHelper.isAccessibilityEnabled(requireContext())
        v.findViewById<TextView>(R.id.rowA11yStatusValue).apply {
            text = if (ok) "ON ✓" else "OFF ✗"
            setTextColor(if (ok) 0xFF69F0AE.toInt() else 0xFFFF8A80.toInt())
        }
    }

    private fun choiceDialog(
        title: String,
        labels: List<String>,
        checked: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                onPick(which)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun textDialog(title: String, current: String, hint: String, onSave: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(current)
            setHint(hint)
        }
        val wrap = FrameLayout(requireContext()).apply {
            setPadding(48, 24, 48, 8)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
