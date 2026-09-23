package com.myra.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ToolHandler
import com.myra.assistant.data.ChatMessage
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

    private var client: GeminiLiveClient? = null
    private var audio: AudioEngine? = null

    private var turnHasModelMessage = false

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
        _statusText.postValue("Connecting...")
        val engine = AudioEngine()
        audio = engine
        val c = GeminiLiveClient(object : GeminiLiveClient.Listener {
            override fun onStatus(msg: String) {
                _statusText.postValue(msg)
            }

            override fun onSetupComplete() {
                _isConnected.postValue(true)
                _statusText.postValue("Listening...")
                try {
                    engine.startCapture { chunk ->
                        client?.sendAudio(chunk)
                    }
                } catch (e: Exception) {
                    _statusText.postValue("Mic error: ${e.message}")
                }
            }

            override fun onAudioChunk(pcm24k: ByteArray) {
                try {
                    audio?.playPcm24k(pcm24k)
                } catch (_: Exception) {
                }
            }

            override fun onTextDelta(text: String) {
                appendModelDelta(text)
            }

            override fun onTurnComplete() {
                turnHasModelMessage = false
            }

            override fun onToolCall(id: String, name: String, argsJson: String) {
                viewModelScope.launch(Dispatchers.IO) {
                    val result = try {
                        ToolHandler.execute(name, JSONObject(argsJson), getApplication())
                    } catch (e: Exception) {
                        "ERROR: ${e.message}"
                    }
                    client?.sendToolResponse(id, name, result)
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
            }

            override fun onClosed() {
                _isConnected.postValue(false)
                _statusText.postValue("Disconnected")
            }
        })
        client = c
        c.connect(apiKey)
    }

    fun sendTypedText(text: String) {
        addMessage(ChatMessage("user", text))
        client?.sendText(text)
    }

    fun stopSession() {
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
        _statusText.postValue("Idle")
    }

    override fun onCleared() {
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
