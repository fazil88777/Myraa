package com.myra.assistant.ui

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.service.FloatingOrbService
import com.myra.assistant.service.PcLink
import com.myra.assistant.util.LiaStyle
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings, re-skinned to the reference video's structure: APPEARANCE /
 * AI & VOICE / MEMORY / PROFILE / AI PROVIDER / KEYS & DATA / BACKUP /
 * MORE — dark cards, white labels, cyan values, teal switches. Every
 * pre-existing setting keeps working (API-key save flow, names, voice
 * picker + live-restart behavior included); the video's Voice ID
 * section is intentionally absent (MYRA has no voice enrollment).
 */
class SettingsFragment : Fragment() {

    private var updating = false
    private lateinit var vm: MainViewModel

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreFrom(uri) }

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

        // ---- APPEARANCE ----
        val swNeon = view.findViewById<SwitchCompat>(R.id.switchNeonLighting)
        swNeon.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.neonLighting = on
            (activity as? MainActivity)?.refreshBorderState()
        }
        view.findViewById<View>(R.id.rowTheme).setOnClickListener {
            val themes = LiaStyle.THEMES
            val idx = themes.indexOfFirst { it.key == Prefs.theme }.coerceAtLeast(0)
            val next = themes[(idx + 1) % themes.size]
            Prefs.theme = next.key
            refreshAll()
            toast("Theme: ${next.label}")
        }

        // ---- AI & VOICE ----
        val swLive = view.findViewById<SwitchCompat>(R.id.switchLiveVoice)
        swLive.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.liveVoice = on
            toast(if (on) "Live Voice ON" else "Live Voice off hai")
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
                applyVoiceSettingNow("${items[which].label} select ho gayi")
            }
        }
        val swBorder = view.findViewById<SwitchCompat>(R.id.switchFloatingBorder)
        swBorder.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.floatingBorder = on
            // Turning it ON without the draw-over-apps permission: ask now.
            if (on && !PermissionHelper.canDrawOverlays(act)) {
                PermissionHelper.openOverlaySettings(act)
            }
            (activity as? MainActivity)?.refreshBorderState()
        }

        // ---- MEMORY ----
        val swMem = view.findViewById<SwitchCompat>(R.id.switchMemory)
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

        // ---- PROFILE ----
        view.findViewById<View>(R.id.rowUserName).setOnClickListener {
            textDialog("Tumhara naam", Prefs.userName, "Apna naam likho") {
                Prefs.userName = it
                refreshAll()
                toast("Name saved!")
            }
        }
        view.findViewById<View>(R.id.rowAssistantName).setOnClickListener {
            textDialog("Assistant Name", Prefs.assistantName.ifBlank { "MYRA" }, "MYRA") {
                Prefs.assistantName = it.ifBlank { "MYRA" }
                refreshAll()
                toast("Assistant ka naam: ${Prefs.assistantName}")
            }
        }

        // ---- AI PROVIDER: connection status (live) ----
        vm.isConnected.observe(viewLifecycleOwner) { refreshConnStatus() }
        vm.statusText.observe(viewLifecycleOwner) { refreshConnStatus() }

        // ---- KEYS & DATA ----
        view.findViewById<View>(R.id.rowApiKey).setOnClickListener {
            textDialog("Gemini API Key", Prefs.apiKey, "AI Studio se key paste karo") {
                Prefs.apiKey = it
                refreshAll()
                toast("API key saved")
            }
        }
        view.findViewById<View>(R.id.btnApiTest).setOnClickListener { runApiTest() }

        // ---- BACKUP ----
        view.findViewById<View>(R.id.btnBackup).setOnClickListener { backupSettings() }
        view.findViewById<View>(R.id.btnRestore).setOnClickListener {
            restoreLauncher.launch(arrayOf("*/*"))
        }

        // ---- MORE ----
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
                applyVoiceSettingNow("Speed ${LiaStyle.speedLabel(items[which])} select ho gayi")
            }
        }
        val swMotion = view.findViewById<SwitchCompat>(R.id.switchReducedMotion)
        swMotion.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.reducedMotion = on
        }
        val swOrb = view.findViewById<SwitchCompat>(R.id.switchFloatingOrb)
        swOrb.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            val i = Intent(act, FloatingOrbService::class.java)
            if (on) ContextCompat.startForegroundService(act, i) else act.stopService(i)
            Prefs.orbEnabled = on
        }
        view.findViewById<View>(R.id.rowQuickActions).setOnClickListener {
            (activity as? MainActivity)?.selectTab("tools")
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
        val swPc = view.findViewById<SwitchCompat>(R.id.switchPc)
        swPc.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.pcEnabled = on
            if (on) PcLink.start(requireContext()) else PcLink.stop()
            refreshAll()
            toast(if (on) "PC Link ON 🖥" else "PC Link OFF")
        }
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
            v.findViewById<SwitchCompat>(R.id.switchNeonLighting).isChecked = Prefs.neonLighting
            v.findViewById<TextView>(R.id.rowThemeValue).text =
                LiaStyle.theme(Prefs.theme).label
            v.findViewById<SwitchCompat>(R.id.switchLiveVoice).isChecked = Prefs.liveVoice
            v.findViewById<TextView>(R.id.rowVoiceValue).text =
                LiaStyle.voiceLabel(Prefs.voiceName)
            v.findViewById<SwitchCompat>(R.id.switchFloatingBorder).isChecked =
                Prefs.floatingBorder
            v.findViewById<SwitchCompat>(R.id.switchMemory).isChecked = Prefs.memoryEnabled
            v.findViewById<TextView>(R.id.rowUserNameValue).text =
                Prefs.userName.ifBlank { "Dost" }
            v.findViewById<TextView>(R.id.rowAssistantNameValue).text =
                Prefs.assistantName.ifBlank { "MYRA" }
            v.findViewById<TextView>(R.id.rowApiKeyValue).text =
                if (Prefs.apiKey.isBlank()) "Not set" else "(set ✓)"
            v.findViewById<TextView>(R.id.rowLanguageValue).text =
                LiaStyle.langLabel(Prefs.language)
            v.findViewById<TextView>(R.id.rowSpeechSpeedValue).text =
                LiaStyle.speedLabel(Prefs.speechSpeed)
            v.findViewById<SwitchCompat>(R.id.switchReducedMotion).isChecked =
                Prefs.reducedMotion
            v.findViewById<SwitchCompat>(R.id.switchFloatingOrb).isChecked = Prefs.orbEnabled
            refreshConnStatus()
            refreshA11y(v)
            v.findViewById<SwitchCompat>(R.id.switchPc).isChecked = Prefs.pcEnabled
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

    /** LIVE API TEST: one minimal Gemini REST call with the saved key. */
    private fun runApiTest() {
        val key = Prefs.apiKey
        if (key.isBlank()) {
            toast("Pehle API key save karo")
            return
        }
        toast("API test ho raha hai…")
        Thread {
            var ok = false
            var code = -1
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", "Hi"))
                            )
                        )
                    )
                }.toString()
                val request = Request.Builder()
                    .url(
                        "https://generativelanguage.googleapis.com/v1beta/models/" +
                            "gemini-2.0-flash:generateContent"
                    )
                    .addHeader("X-goog-api-key", key)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    code = resp.code
                    ok = resp.isSuccessful
                }
            } catch (_: Exception) {
            }
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                toast(
                    if (ok) "API key kaam kar rahi hai ✓"
                    else "API test fail (code $code) — key ya net check karo"
                )
            }
        }.start()
    }

    /** BACKUP: write all settings as JSON into Downloads. */
    private fun backupSettings() {
        val ctx = requireContext()
        val json = JSONObject().apply {
            put("apiKey", Prefs.apiKey)
            put("userName", Prefs.userName)
            put("assistantName", Prefs.assistantName)
            put("voiceName", Prefs.voiceName)
            put("language", Prefs.language)
            put("theme", Prefs.theme)
            put("speechSpeed", Prefs.speechSpeed.toDouble())
            put("memoryEnabled", Prefs.memoryEnabled)
            put("neonLighting", Prefs.neonLighting)
            put("liveVoice", Prefs.liveVoice)
            put("floatingBorder", Prefs.floatingBorder)
            put("orbEnabled", Prefs.orbEnabled)
            put("reducedMotion", Prefs.reducedMotion)
            put("pcEnabled", Prefs.pcEnabled)
            put("autoRejectUnknown", Prefs.autoRejectUnknown)
        }
        val fileName = "myra-settings-backup.json"
        val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
        Thread {
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                    val uri = ctx.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        true
                    } else {
                        false
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    dir.mkdirs()
                    File(dir, fileName).writeBytes(bytes)
                    true
                }
            } catch (_: Exception) {
                false
            }
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                toast(
                    if (ok) "Backup Downloads mein save ho gaya 💾"
                    else "Backup fail ho gaya"
                )
            }
        }.start()
    }

    /** RESTORE: read a backup JSON picked by the user and apply it to Prefs. */
    private fun restoreFrom(uri: Uri) {
        val ctx = requireContext()
        Thread {
            var ok = false
            try {
                val text = ctx.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()
                if (!text.isNullOrBlank()) {
                    val j = JSONObject(text)
                    if (j.has("apiKey")) Prefs.apiKey = j.optString("apiKey")
                    if (j.has("userName")) Prefs.userName = j.optString("userName")
                    if (j.has("assistantName")) {
                        Prefs.assistantName = j.optString("assistantName")
                    }
                    if (j.has("voiceName")) Prefs.voiceName = j.optString("voiceName")
                    if (j.has("language")) Prefs.language = j.optString("language")
                    if (j.has("theme")) Prefs.theme = j.optString("theme")
                    if (j.has("speechSpeed")) {
                        Prefs.speechSpeed = j.optDouble("speechSpeed", 1.0).toFloat()
                    }
                    if (j.has("memoryEnabled")) {
                        Prefs.memoryEnabled = j.optBoolean("memoryEnabled", true)
                    }
                    if (j.has("neonLighting")) {
                        Prefs.neonLighting = j.optBoolean("neonLighting", true)
                    }
                    if (j.has("liveVoice")) {
                        Prefs.liveVoice = j.optBoolean("liveVoice", true)
                    }
                    if (j.has("floatingBorder")) {
                        Prefs.floatingBorder = j.optBoolean("floatingBorder", true)
                    }
                    if (j.has("orbEnabled")) {
                        Prefs.orbEnabled = j.optBoolean("orbEnabled", true)
                    }
                    if (j.has("reducedMotion")) {
                        Prefs.reducedMotion = j.optBoolean("reducedMotion", false)
                    }
                    if (j.has("pcEnabled")) {
                        Prefs.pcEnabled = j.optBoolean("pcEnabled", false)
                    }
                    if (j.has("autoRejectUnknown")) {
                        Prefs.autoRejectUnknown = j.optBoolean("autoRejectUnknown", false)
                    }
                    ok = true
                }
            } catch (_: Exception) {
            }
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                if (ok) {
                    refreshAll()
                    (activity as? MainActivity)?.refreshBorderState()
                    toast("Settings restore ho gayi 📥")
                } else {
                    toast("Backup file parhi nahi gayi")
                }
            }
        }.start()
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

    /**
     * After a voice/speed change: if a voice session is live, restart it
     * so the new setting applies at once; otherwise it applies on the next session.
     */
    private fun applyVoiceSettingNow(msg: String) {
        val act = activity as? MainActivity
        if (act != null && act.isVoiceLive()) {
            toast("$msg — session restart ho raha hai ✨")
            act.restartVoiceSession()
        } else {
            toast("$msg ✨")
        }
    }
}
