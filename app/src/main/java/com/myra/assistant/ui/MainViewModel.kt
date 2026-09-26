package com.myra.assistant.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CameraVision
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ToolHandler
import com.myra.assistant.data.ChatMessage
import com.myra.assistant.service.ScreenShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Drives one Gemini Live session: mic capture -> websocket audio in,
 * 24kHz PCM audio out, text transcript, and device tool execution.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _statusText = MutableLiveData("Idle")
    val statusText: LiveData<String> = _statusText

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _isSharing = MutableLiveData(false)
    val isSharing: LiveData<Boolean> = _isSharing

    private var client: GeminiLiveClient? = null
    private var audio: AudioEngine? = null

    private var turnHasModelMessage = false

    // --- session health watchdog: if the user speaks but the model never replies,
    // the Live session is stuck -> auto-reconnect instead of staying silent ---
    private var savedApiKey: String? = null
    private var lastUserSpeechAt = 0L
    private var lastModelActivityAt = 0L
    // Updated ONLY when the model actually responds (audio/text/tool call).
    // turnComplete also fires for the USER's turn, so it must not reset this,
    // otherwise the watchdog can never detect a silent model.
    private var lastModelResponseAt = 0L
    private var pendingInputMsgIndex = -1
    private var watchdogStarted = false
    // Silence-fix state: auto-reconnect when the socket dies, and detect a
    // "half-dead" socket (mic hears the user, but no transcription ever arrives).
    private var manualStop = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var sessionGen = 0
    private var lastVoiceActivityAt = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    // Nudge timer: reminds the model to speak after a tool call if it stays silent.
    private val nudgeHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                checkSessionHealth()
            } catch (_: Exception) {
            }
            watchdogHandler.postDelayed(this, 10_000)
        }
    }

    private fun ensureWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        watchdogHandler.post(watchdogRunnable)
    }

    private fun checkSessionHealth() {
        if (_isConnected.value != true) return
        val now = System.currentTimeMillis()
        // User spoke (or typed) but the model never actually responded -> stuck
        if (lastUserSpeechAt > lastModelResponseAt && now - lastUserSpeechAt > 20_000) {
            _statusText.postValue("Reconnecting...")
            autoReconnect()
            return
        }
        // Half-dead socket: the mic clearly hears the user speaking, but no
        // transcription ever arrives and the model never reacts (server side
        // is gone without closing). Compare against the latest real progress
        // so a normal answered question never triggers a false reconnect.
        val lastProgress = maxOf(lastUserSpeechAt, lastModelResponseAt)
        if (lastVoiceActivityAt > lastProgress &&
            now - lastVoiceActivityAt > 12_000
        ) {
            _statusText.postValue("Reconnecting...")
            autoReconnect()
        }
    }

    /** Reconnect automatically after an unexpected drop (never after a manual stop). */
    private fun scheduleReconnect() {
        if (manualStop || reconnectScheduled) return
        if (_isConnected.value == true) return
        autoReconnect()
    }

    /**
     * Stop everything and start a fresh session, with backoff: if the network
     * is truly down, give up after 5 tries instead of looping forever.
     */
    private fun autoReconnect() {
        if (manualStop || reconnectScheduled) return
        val key = savedApiKey ?: return
        if (reconnectAttempts >= 5) {
            _statusText.postValue("Connection lost — net check karke dobara connect dabao, boss")
            return
        }
        reconnectAttempts++
        reconnectScheduled = true
        val gen = sessionGen
        _statusText.postValue("Reconnecting...")
        stopSessionInternal()
        watchdogHandler.postDelayed({
            reconnectScheduled = false
            // User tapped stop/start meanwhile -> respect his action, don't restart.
            if (manualStop || gen != sessionGen) return@postDelayed
            try {
                startSession(key)
            } catch (_: Exception) {
            }
        }, 2000L * reconnectAttempts)
    }

    /** 16-bit mono PCM voice-activity check: true when someone is actually speaking. */
    private fun isLoud(pcm16: ByteArray): Boolean {
        var peak = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            val s = (pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            i += 2
        }
        return peak > 1200
    }

    private fun showInputTranscription(text: String) {
        val l = _messages.value!!.toMutableList()
        if (pendingInputMsgIndex >= 0 && pendingInputMsgIndex < l.size &&
            l[pendingInputMsgIndex].role == "user"
        ) {
            val old = l[pendingInputMsgIndex]
            l[pendingInputMsgIndex] = old.copy(text = text)
        } else {
            l.add(ChatMessage("user", text))
            pendingInputMsgIndex = l.size - 1
        }
        _messages.postValue(l)
    }

    private fun addMessage(m: ChatMessage) {
        val l = _messages.value!!.toMutableList()
        l.add(m)
        _messages.postValue(l)
    }

    private fun appendModelDelta(t: String) {
        val l = _messages.value!!.toMutableList()
        if (!turnHasModelMessage || l.isEmpty() || l.last().role != "model") {
            l.add(ChatMessage("model", t))
            turnHasModelMessage = true
        } else {
            val last = l.last()
            l[l.size - 1] = last.copy(text = last.text + t)
        }
        _messages.postValue(l)
    }

    fun startSession(apiKey: String) {
        if (_isConnected.value == true) return
        savedApiKey = apiKey
        sessionGen++
        manualStop = false
        reconnectScheduled = false
        reconnectAttempts = 0
        lastUserSpeechAt = 0L
        lastVoiceActivityAt = 0L
        lastModelActivityAt = System.currentTimeMillis()
        lastModelResponseAt = System.currentTimeMillis()
        pendingInputMsgIndex = -1
        ensureWatchdog()
        _statusText.postValue("Connecting...")
        val engine = AudioEngine()
        audio = engine
        val c = GeminiLiveClient(object : GeminiLiveClient.Listener {
            override fun onStatus(msg: String) {
                _statusText.postValue(msg)
            }

            override fun onSetupComplete() {
                _isConnected.postValue(true)
                reconnectAttempts = 0
                reconnectScheduled = false
                lastModelActivityAt = System.currentTimeMillis()
                lastModelResponseAt = System.currentTimeMillis()
                _statusText.postValue("Listening...")
                try {
                    engine.startCapture { chunk ->
                        // Voice-activity tracking for the half-dead-socket watchdog:
                        // ignore mic energy right after the model spoke (echo).
                        if (isLoud(chunk) &&
                            System.currentTimeMillis() - lastModelResponseAt > 3000
                        ) {
                            lastVoiceActivityAt = System.currentTimeMillis()
                        }
                        client?.sendAudio(chunk)
                    }
                } catch (e: Exception) {
                    _statusText.postValue("Mic error: ${e.message}")
                }
            }

            override fun onAudioChunk(pcm24k: ByteArray) {
                lastModelActivityAt = System.currentTimeMillis()
                lastModelResponseAt = System.currentTimeMillis()
                try {
                    audio?.playPcm24k(pcm24k)
                } catch (_: Exception) {
                }
            }

            override fun onTextDelta(text: String) {
                lastModelActivityAt = System.currentTimeMillis()
                lastModelResponseAt = System.currentTimeMillis()
                appendModelDelta(text)
            }

            override fun onInputTranscription(text: String) {
                lastUserSpeechAt = System.currentTimeMillis()
                showInputTranscription(text)
            }

            override fun onTurnComplete() {
                lastModelActivityAt = System.currentTimeMillis()
                // NOTE: do NOT update lastModelResponseAt here — turnComplete also
                // fires for the user's own turn, which would blind the watchdog.
                turnHasModelMessage = false
                pendingInputMsgIndex = -1
            }

            override fun onToolCall(id: String, name: String, argsJson: String) {
                lastModelResponseAt = System.currentTimeMillis()
                viewModelScope.launch(Dispatchers.IO) {
                    val result = try {
                        ToolHandler.execute(name, JSONObject(argsJson), getApplication())
                    } catch (e: Exception) {
                        "ERROR: ${e.message}"
                    }
                    client?.sendToolResponse(id, name, result)
                    // Spoken-confirmation nudge: the model sometimes finishes a
                    // tool call without saying anything. If no model audio/text
                    // follows within 7 seconds, ask it to confirm briefly.
                    val sentAt = System.currentTimeMillis()
                    nudgeHandler.postDelayed({
                        if (_isConnected.value == true && lastModelResponseAt <= sentAt) {
                            client?.sendText("Mukhtasir mein batao ke tumne abhi kya kiya, boss style mein.")
                        }
                    }, 7000)
                }
            }

            override fun onInterrupted() {
                try {
                    audio?.stopPlayback()
                } catch (_: Exception) {
                }
            }

            override fun onError(msg: String) {
                _statusText.postValue("Error: $msg")
                scheduleReconnect()
            }

            override fun onClosed(reason: String) {
                _isConnected.postValue(false)
                _statusText.postValue("Disconnected: $reason")
                scheduleReconnect()
            }
        })
        client = c
        c.connect(apiKey)
        // Re-hook screen frames to the new client if sharing was already on
        if (_isSharing.value == true) {
            ScreenShareService.onFrame = { b64 -> sendVideoFrame(b64) }
        }
        // Camera vision: feed front-camera frames while the session is live,
        // only if the user enabled it ("camera on karo").
        CameraVision.sessionLive = true
        CameraVision.onFrame = { b64 -> sendVideoFrame(b64) }
        CameraVision.refresh(getApplication())
    }

    fun sendTypedText(text: String) {
        addMessage(ChatMessage("user", text))
        pendingInputMsgIndex = -1
        lastUserSpeechAt = System.currentTimeMillis()
        client?.sendText(text)
    }

    /** Called by ScreenShareService for each captured frame (base64 JPEG). */
    fun sendVideoFrame(base64Jpeg: String) {
        client?.sendVideoFrame(base64Jpeg)
    }

    fun setSharing(sharing: Boolean) {
        if (sharing) {
            ScreenShareService.onFrame = { b64 -> sendVideoFrame(b64) }
        } else {
            ScreenShareService.onFrame = null
        }
        _isSharing.postValue(sharing)
    }

    fun stopSession() {
        // User tapped disconnect himself -> never auto-reconnect afterwards.
        manualStop = true
        reconnectScheduled = false
        sessionGen++
        stopSessionInternal()
        _statusText.postValue("Idle")
    }

    /** Tear down audio + socket without marking a manual stop. */
    private fun stopSessionInternal() {
        CameraVision.sessionLive = false
        CameraVision.refresh(getApplication())
        CameraVision.onFrame = null
        try {
            audio?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            audio?.stopPlayback()
        } catch (_: Exception) {
        }
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        _isConnected.postValue(false)
    }

    override fun onCleared() {
        try {
            watchdogHandler.removeCallbacks(watchdogRunnable)
        } catch (_: Exception) {
        }
        try {
            audio?.release()
        } catch (_: Exception) {
        }
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
