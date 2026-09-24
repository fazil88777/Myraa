package com.myra.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.service.FloatingOrbService
import com.myra.assistant.util.PermissionHelper
import com.myra.assistant.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: ChatAdapter
    private var orbRunning = false

    companion object {
        private const val REQ_CORE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash catcher: save the stack trace if the app crashes, show it on next launch
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Prefs.lastCrash = android.util.Log.getStackTraceString(throwable).take(1500)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        adapter = ChatAdapter()

        val recycler = findViewById<RecyclerView>(R.id.chatRecycler)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveKeyButton = findViewById<Button>(R.id.saveKeyButton)
        val connectButton = findViewById<Button>(R.id.connectButton)
        val messageInput = findViewById<EditText>(R.id.messageInput)
        val sendButton = findViewById<Button>(R.id.sendButton)
        val permissionsButton = findViewById<Button>(R.id.permissionsButton)
        val orbToggleButton = findViewById<Button>(R.id.orbToggleButton)
        val callRoleButton = findViewById<Button>(R.id.callRoleButton)

        apiKeyInput.setText(Prefs.apiKey)

        saveKeyButton.setOnClickListener {
            Prefs.apiKey = apiKeyInput.text.toString().trim()
            toast("API key saved")
        }

        connectButton.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.stopSession()
            } else {
                val k = Prefs.apiKey
                if (k.isBlank()) {
                    toast("Pehle API key save karo")
                } else {
                    viewModel.startSession(k)
                }
            }
        }

        sendButton.setOnClickListener {
            val msg = messageInput.text.toString()
            if (msg.isNotBlank()) {
                viewModel.sendTypedText(msg)
                messageInput.text.clear()
            }
        }

        permissionsButton.setOnClickListener {
            PermissionHelper.requestCore(this, REQ_CORE)
            if (!PermissionHelper.canDrawOverlay(this)) {
                PermissionHelper.openOverlaySettings(this)
            }
            if (!PermissionHelper.isAccessibilityEnabled(this)) {
                PermissionHelper.openAccessibilitySettings(this)
            }
        }

        orbToggleButton.setOnClickListener {
            val i = Intent(this, FloatingOrbService::class.java)
            if (orbRunning) {
                stopService(i)
                orbRunning = false
            } else {
                ContextCompat.startForegroundService(this, i)
                orbRunning = true
            }
            Prefs.orbEnabled = orbRunning
        }

        callRoleButton.setOnClickListener {
            PermissionHelper.requestCallScreeningRole(this)
        }

        viewModel.messages.observe(this) {
            adapter.submit(it)
            if (adapter.itemCount > 0) {
                recycler.scrollToPosition(adapter.itemCount - 1)
            }
        }

        viewModel.statusText.observe(this) {
            findViewById<TextView>(R.id.statusText).text = it
        }

        viewModel.isConnected.observe(this) { c ->
            connectButton.text = if (c) {
                getString(R.string.label_disconnect)
            } else {
                getString(R.string.label_connect)
            }
            findViewById<OrbAnimationView>(R.id.orbView).setActive(c)
        }

        if (!PermissionHelper.hasAllCore(this)) {
            PermissionHelper.requestCore(this, REQ_CORE)
        }

        // If the app crashed last time, show the reason
        val lastCrash = Prefs.lastCrash
        if (lastCrash.isNotEmpty()) {
            Prefs.lastCrash = ""
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Pichli baar crash ki wajah")
                .setMessage(lastCrash)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
