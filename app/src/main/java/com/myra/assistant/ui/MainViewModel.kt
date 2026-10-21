package com.myra.assistant.ui

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CameraVision
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ToolHandler
import com.myra.assistant.ai.VoiceCodeLock
import com.myra.assistant.data.ChatMessage
import com.myra.assistant.service.BorderOverlay
import com.myra.assistant.service.FloatingOrbService
import com.myra.assistant.service.ScreenShareService
import com.myra.assistant.util.Prefs
import com.myra.assistant.voice.ElevenLabsSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** One-shot message the UI toasts once (EL fallback notice, final
     *  give-up reason) — id increments so repeats are never swallowed. */
    data class ToastEvent(val id: Long, val message: String)

    private val _toastEvent = MutableLiveData<ToastEvent>()
    val toastEvent: LiveData<ToastEvent> = _toastEvent
    private var toastSeq = 0L

    private fun postToast(message: String) {
        _toastEvent.postValue(ToastEvent(++toastSeq, message))
    }

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

    // --- deterministic welcome voice on user-initiated connect ---
    // The model doesn't always volunteer its greeting, so the app itself
    // speaks "Welcome to Myra Assistant" exactly once per manual start
    // (never on auto-reconnects). Mic capture starts after the welcome
    // finishes so the model doesn't hear it as user speech.
    private var speakWelcomeOnConnect = false
    private var tts: TextToSpeech? = null
    // ElevenLabs voice for THIS session (null in Gemini Live audio mode).
    // Created fresh in beginNewSession when key + voice id are saved in
    // Settings; the Live session then runs in TEXT mode and this speaker
    // voices the model's reply text sentence by sentence.
    private var elSpeaker: ElevenLabsSpeaker? = null
    // Silence-fix state: auto-reconnect when the socket dies, and detect a
    // "half-dead" socket (mic hears the user, but no transcription ever arrives).
    private var manualStop = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var sessionGen = 0

    // --- honest setup-failure state (ElevenLabs-mode fix) ---
    // A connect attempt is in flight (setup sent, not yet resolved).
    private var sessionConnecting = false
    // The current attempt reached setupComplete (the session truly lived).
    private var setupCompletedThisSession = false
    // The latest death happened BEFORE setupComplete: deterministic
    // (a rejected setup fails identically every retry) -> capped retries.
    private var lastDeathPreSetup = false
    // Last death's posted reason — friendly line PLUS the server's raw
    // code/reason — shown in the parked status and toasted on give-up.
    private var lastDeathInfo: String? = null
    // Per manual start: the ONE EL TEXT -> AUDIO fallback already used.
    private var elAudioFallbackUsed = false
    // The current attempt chain runs AUDIO on the native-audio model even
    // though ElevenLabs is configured (set by the fallback; auto-reconnects
    // keep it, they never re-arm the fallback itself).
    private var attemptForceAudio = false
    // The give-up toast fires once per failure chain.
    private var giveUpToastShown = false
    // Coroutine scope bound to the CURRENT session: cancelled on every teardown
    // so a tool call from the dying session can never answer the new socket.
    private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastVoiceActivityAt = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    // Nudge timer: reminds the model to speak after a tool call if it stays silent.
    private val nudgeHandler = Handler(Looper.getMainLooper())
    // Stuck-session state: tracks whether the model has answered at least once
    // this session. The first turn needs a longer grace period (long system
    // prompt cold start) — firing the watchdog too early cancels the model's
    // in-flight answer and causes a reconnect loop with no replies.
    private var modelRespondedThisSession = false
    private var lastUserText = ""
    // Set false right after a tool response is sent, true when the model's turn
    // closes afterwards. The 12s confirmation nudge may only fire once this is
    // true — never while the model may still be generating, so the nudge's text
    // turn can't cancel a slow in-flight answer.
    private var toolTurnClosed = false
    // --- full voice shutdown ("myra off ho jao"): everything stays dead until
    // the user manually opens the app again (which clears Prefs.fullyOff). ---
    @Volatile
    private var isFullyOff = false
    private var fullyShuttingDown = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isFullyOff) checkSessionHealth()
            } catch (_: Exception) {
            }
            // Fully off: never repost — zero background work until manual app open.
            if (!isFullyOff) watchdogHandler.postDelayed(this, 3_000)
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
        // User spoke (or typed) but the model never actually responded -> stuck.
        // Give the model real time: 30s on the session's first turn (long system
        // prompt cold start) and 20s afterwards. Interrupting earlier cancels the
        // in-flight answer and traps the app in a reconnect loop with no replies.
        if (lastUserSpeechAt > lastModelResponseAt) {
            val silentFor = now - lastUserSpeechAt
            val limit = if (modelRespondedThisSession) 20_000 else 30_000
            if (silentFor > limit) {
                autoReconnect()
                return
            }
        }
        // Half-dead socket: the mic clearly hears the user speaking, but no
        // transcription ever arrives and the model never reacts (server side
        // is gone without closing). Compare against the latest real progress
        // so a normal answered question never triggers a false reconnect.
        val lastProgress = maxOf(lastUserSpeechAt, lastModelResponseAt)
        if (lastVoiceActivityAt > lastProgress &&
            now - lastVoiceActivityAt > 10_000
        ) {
            autoReconnect()
        }
    }

    /** Reconnect automatically after an unexpected drop (never after a manual stop). */
    private fun scheduleReconnect() {
        if (manualStop || reconnectScheduled || isFullyOff) return
        if (_isConnected.value == true) return
        autoReconnect()
    }

    /**
     * Stop everything and start a fresh session, with backoff: if the network
     * is truly down, give up after 5 tries instead of looping forever.
     */
    private fun autoReconnect() {
        if (manualStop || reconnectScheduled || isFullyOff) return
        val key = savedApiKey ?: return
        // Retry cap by failure kind: deaths BEFORE setupComplete are
        // deterministic (a rejected setup fails identically every retry),
        // so they stop after 2 attempts and park with the server's real
        // reason in the status (+ one toast) instead of looping silently.
        // A session that actually connected keeps the full 5 attempts.
        val cap = if (lastDeathPreSetup) 2 else 5
        if (reconnectAttempts >= cap) {
            val info = lastDeathInfo
            _statusText.postValue(
                if (info != null) "Connection lost — $info"
                else "Connection lost — net check karke dobara connect dabao"
            )
            if (info != null && !giveUpToastShown) {
                giveUpToastShown = true
                postToast(info)
            }
            return
        }
        reconnectAttempts++
        reconnectScheduled = true
        val gen = sessionGen
        // The old session died abnormally — its resumption handle is poisoned
        // (resuming it gives a zombie that never answers). Clear it so the
        // reconnect starts a CLEAN session.
        GeminiLiveClient.clearResumptionHandle()
        _statusText.postValue("Reconnecting...")
        stopSessionInternal()
        watchdogHandler.postDelayed({
            reconnectScheduled = false
            // User tapped stop/start meanwhile -> respect his action, don't restart.
            if (manualStop || gen != sessionGen) return@postDelayed
            try {
                // beginNewSession, not startSession: startSession resets the
                // attempt counter, which would defeat the 5-attempt give-up.
                beginNewSession(key)
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
        // Re-entrancy guard: ignore a start while a session is live OR
        // still connecting — an orb double-tap must not restart an
        // in-flight attempt (the old guard only checked the live state).
        if (_isConnected.value == true || sessionConnecting) return
        // Fully off: only a manual app open clears this (see MainActivity).
        if (isFullyOff || Prefs.fullyOff) {
            _statusText.postValue("MYRA off hai — app dobara kholo taake on ho")
            return
        }
        savedApiKey = apiKey
        // Fresh user-initiated start: past reconnect failures don't count.
        reconnectAttempts = 0
        // A fresh manual start also re-arms the honest-failure bookkeeping
        // and this start's ONE ElevenLabs TEXT -> AUDIO fallback.
        lastDeathPreSetup = false
        lastDeathInfo = null
        elAudioFallbackUsed = false
        attemptForceAudio = false
        giveUpToastShown = false
        // User pressed the button: announce the welcome voice on connect.
        speakWelcomeOnConnect = true
        // Emotional state: work out her mood BEFORE the new session's system
        // prompt is built — a >= 12h gap makes her start in the lonely
        // "Missing You" mood (see MoodManager); this also records the
        // interaction time. Auto-reconnects/rotations skip this on purpose:
        // they continue the same conversation, they are not a new arrival.
        try {
            com.myra.assistant.util.MoodManager.onUserSessionStart()
        } catch (_: Exception) {
        }
        beginNewSession(apiKey)
    }

    /** Called by MainActivity on a manual app open: full shutdown is over. */
    fun clearFullyOff() {
        isFullyOff = false
    }

    /**
     * Starts a brand-new session, tearing down any live one first (not a
     * manual stop). Used for both fresh starts and planned rotations.
     */
    private fun beginNewSession(apiKey: String) {
        // Defensive: never start a session while fully off.
        if (isFullyOff || Prefs.fullyOff) return
        stopSessionInternal()
        // Invalidate stale delayed callbacks (e.g. a pending autoReconnect
        // posted by the dying session's onClosed).
        sessionGen++
        // This attempt is now connecting; it only counts as "lived" once
        // setupComplete arrives (drives the honest failure caps below).
        sessionConnecting = true
        setupCompletedThisSession = false
        // Fresh coroutine scope per session: a tool call from the dying
        // session is cancelled here and can never answer the new socket.
        try {
            sessionScope.cancel()
        } catch (_: Exception) {
        }
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Generation captured for every listener callback below: stale
        // callbacks from the old socket die on this guard instead of driving
        // the new session (spurious reconnects, ghost audio, wrong answers).
        val gen = sessionGen
        manualStop = false
        reconnectScheduled = false
        lastUserSpeechAt = 0L
        lastVoiceActivityAt = 0L
        modelRespondedThisSession = false
        toolTurnClosed = false
        lastModelActivityAt = System.currentTimeMillis()
        lastModelResponseAt = System.currentTimeMillis()
        pendingInputMsgIndex = -1
        ensureWatchdog()
        _statusText.postValue("Connecting...")
        val engine = AudioEngine()
        audio = engine
        // ElevenLabs mode is decided HERE, once, at session start (the
        // client's setup reads the same flag for its TEXT modality) —
        // changing the key in Settings mid-session never switches voices.
        elSpeaker = if (Prefs.elevenLabsEnabled && !attemptForceAudio) {
            try {
                ElevenLabsSpeaker(getApplication())
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val c = GeminiLiveClient(object : GeminiLiveClient.Listener {
            override fun onStatus(msg: String) {
                if (gen != sessionGen) return
                _statusText.postValue(msg)
            }

            override fun onSetupComplete() {
                if (gen != sessionGen) return
                postConnected(true)
                sessionConnecting = false
                setupCompletedThisSession = true
                lastDeathPreSetup = false
                giveUpToastShown = false
                // A live connection counts as an interaction for the
                // Missing-You gap calculation.
                try {
                    com.myra.assistant.util.MoodManager.touchInteraction()
                } catch (_: Exception) {
                }
                reconnectScheduled = false
                lastModelActivityAt = System.currentTimeMillis()
                lastModelResponseAt = System.currentTimeMillis()
                _statusText.postValue("Listening...")
                val beginCapture: () -> Unit = {
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
                if (speakWelcomeOnConnect) {
                    speakWelcomeOnConnect = false
                    speakWelcomeThen(beginCapture)
                } else {
                    beginCapture()
                }
            }

            override fun onAudioChunk(pcm24k: ByteArray) {
                if (gen != sessionGen) return
                lastModelActivityAt = System.currentTimeMillis()
                lastModelResponseAt = System.currentTimeMillis()
                modelRespondedThisSession = true
                // The session proved itself healthy — reconnect failures stop counting.
                reconnectAttempts = 0
                try {
                    audio?.playPcm24k(pcm24k)
                } catch (_: Exception) {
                }
            }

            override fun onTextDelta(text: String) {
                if (gen != sessionGen) return
                lastModelActivityAt = System.currentTimeMillis()
                lastModelResponseAt = System.currentTimeMillis()
                modelRespondedThisSession = true
                reconnectAttempts = 0
                appendModelDelta(text)
                // ElevenLabs mode: stream the same text to the TTS speaker,
                // which cuts it into sentences and speaks them in order.
                try {
                    elSpeaker?.enqueueDelta(text)
                } catch (_: Exception) {
                }
            }

            override fun onInputTranscription(text: String) {
                if (gen != sessionGen) return
                lastUserSpeechAt = System.currentTimeMillis()
                lastUserText = text
                // Shutdown command: top priority — always honored.
                if (VoiceCodeLock.isShutdownCommand(text)) {
                    showInputTranscription(text)
                    fullVoiceShutdown()
                    return
                }
                showInputTranscription(text)
                // Barge-in: the user started speaking — cut ElevenLabs
                // playback off immediately, like native audio ducking.
                try {
                    elSpeaker?.stopAll()
                } catch (_: Exception) {
                }
            }

            override fun onTurnComplete() {
                if (gen != sessionGen) return
                lastModelActivityAt = System.currentTimeMillis()
                // NOTE: do NOT update lastModelResponseAt here — turnComplete also
                // fires for the user's own turn, which would blind the watchdog.
                toolTurnClosed = true
                turnHasModelMessage = false
                pendingInputMsgIndex = -1
                // ElevenLabs mode: speak the reply's trailing partial
                // sentence (no terminator ever arrived for it).
                try {
                    elSpeaker?.flushTurn()
                } catch (_: Exception) {
                }
            }

            override fun onToolCall(id: String, name: String, argsJson: String) {
                if (gen != sessionGen) return
                lastModelResponseAt = System.currentTimeMillis()
                modelRespondedThisSession = true
                reconnectAttempts = 0
                // Scoped to THIS session (not the whole ViewModel): if the
                // session dies mid-tool, the coroutine is cancelled and the
                // answer can never be sent to the wrong socket.
                sessionScope.launch {
                    val result = try {
                        ToolHandler.execute(name, JSONObject(argsJson), getApplication())
                    } catch (e: CancellationException) {
                        throw e // session died — don't report, don't answer
                    } catch (e: Exception) {
                        "ERROR: ${e.message}"
                    }
                    // Session rotated while the tool ran -> drop the answer.
                    if (gen != sessionGen) return@launch
                    client?.sendToolResponse(id, name, result)
                    // Spoken-confirmation nudge: the model sometimes finishes a
                    // tool call without saying anything. It fires ONLY if the
                    // model stayed silent AND its turn actually closed
                    // (toolTurnClosed set by turnComplete) — never while it may
                    // still be generating, so the nudge's text turn can't cancel
                    // a slow in-flight answer.
                    toolTurnClosed = false
                    val sentAt = System.currentTimeMillis()
                    nudgeHandler.postDelayed({
                        if (gen != sessionGen) return@postDelayed
                        if (_isConnected.value == true && toolTurnClosed &&
                            lastModelResponseAt <= sentAt
                        ) {
                            client?.sendText("Mukhtasir mein batao ke tumne abhi kya kiya.")
                        }
                    }, 12000)
                }
            }

            override fun onInterrupted() {
                if (gen != sessionGen) return
                try {
                    audio?.stopPlayback()
                } catch (_: Exception) {
                }
                try {
                    elSpeaker?.stopAll()
                } catch (_: Exception) {
                }
            }

            override fun onError(msg: String) {
                if (gen != sessionGen) return
                onSessionDied(msg, fromServerClose = false)
            }

            override fun onClosed(reason: String) {
                if (gen != sessionGen) return
                onSessionDied(reason, fromServerClose = true)
            }

            override fun onGoAway() {
                if (gen != sessionGen) return
                // Server warns the session is about to end (e.g. the ~10-15
                // min limit): rotate gracefully on the main thread instead of
                // waiting for the ugly abort. The resumption handle is KEPT for
                // this path, so the new session picks up the conversation.
                if (manualStop) return
                watchdogHandler.post {
                    if (manualStop) return@post
                    beginNewSession(savedApiKey ?: return@post)
                    _statusText.postValue("Session ka waqt khatam ho raha hai — baat yaad rakh kar dobara connect ho rahi hoon")
                }
            }
        })
        client = c
        // An EL fallback attempt must set up AUDIO on the native-audio
        // model even though ElevenLabs is configured (see sendSetup).
        c.forceAudioMode = attemptForceAudio
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

    /**
     * Single funnel for "the live session just died" (socket closed OR a
     * server/setup error): records the honest reason (the client's friendly
     * line already carries the RAW close code + server words), runs the
     * one-time ElevenLabs TEXT -> AUDIO fallback when a TEXT setup was
     * rejected, and otherwise hands over to the normal reconnect policy.
     */
    private fun onSessionDied(info: String, fromServerClose: Boolean) {
        sessionConnecting = false
        // Mark the session dead so scheduleReconnect() actually reconnects
        // instead of no-op'ing on the stale "connected" flag.
        postConnected(false)
        lastDeathInfo = info
        val diedPreSetup = !setupCompletedThisSession
        lastDeathPreSetup = diedPreSetup

        // ElevenLabs fallback: the TEXT setup was rejected BEFORE the
        // session ever came up (a server close, or an explicit setup/server
        // error — a plain transport failure like no-internet does NOT
        // qualify, switching modality cannot fix that). Retry ONCE, right
        // away, in AUDIO on the native-audio model so he still gets a
        // working session with Gemini's own voice. Auto-reconnects never
        // re-arm this; if the AUDIO attempt also dies pre-setup, the
        // normal capped give-up applies.
        val setupRejected = fromServerClose ||
            info.startsWith("Setup rejected:") || info.startsWith("Server error:")
        if (diedPreSetup && setupRejected && Prefs.elevenLabsEnabled &&
            !attemptForceAudio && !elAudioFallbackUsed
        ) {
            val key = savedApiKey
            if (key != null) {
                elAudioFallbackUsed = true
                attemptForceAudio = true
                postToast("ElevenLabs ke liye TEXT model nahi mila — Live awaaz chala di hai")
                beginNewSession(key)
                return
            }
        }
        _statusText.postValue(info)
        scheduleReconnect()
    }

    /**
     * Post the connection state and keep the floating neon border in
     * sync — this runs on socket callbacks too, so the overlay starts/
     * stops even while the app is backgrounded and MainActivity's own
     * observers are paused.
     */
    private fun postConnected(value: Boolean) {
        _isConnected.postValue(value)
        try {
            BorderOverlay.refresh(getApplication(), value)
        } catch (_: Exception) {
        }
    }

    fun sendTypedText(text: String) {
        // Shutdown command works even from typed text.
        if (VoiceCodeLock.isShutdownCommand(text)) {
            addMessage(ChatMessage("user", text))
            pendingInputMsgIndex = -1
            fullVoiceShutdown()
            return
        }
        addMessage(ChatMessage("user", text))
        pendingInputMsgIndex = -1
        lastUserSpeechAt = System.currentTimeMillis()
        lastUserText = text
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

    /** Voice-controlled screen share: MYRA posts here, MainActivity launches/stops capture. */
    private val _screenShareRequest = MutableLiveData<Boolean>()
    val screenShareRequest: LiveData<Boolean> = _screenShareRequest

    fun requestScreenShare(on: Boolean) {
        _screenShareRequest.postValue(on)
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    /**
     * Speaks "Welcome to Myra Assistant" (guaranteed to play — the AI model
     * doesn't always volunteer a greeting), then runs [after] once the
     * welcome has finished so the mic never picks it up as user speech.
     * ElevenLabs mode: the line is spoken in her ElevenLabs voice; if that
     * fails for any reason (bad key, quota, network), the phone's own TTS
     * below takes over so the greeting is never lost.
     */
    private fun speakWelcomeThen(after: () -> Unit) {
        val speaker = elSpeaker
        if (speaker == null) {
            speakWelcomeViaTts(after)
            return
        }
        val ui = Handler(Looper.getMainLooper())
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            speaker.speakOnce(
                "Welcome to Myra Assistant",
                onPlayed = {
                    ui.post {
                        if (settled.compareAndSet(false, true)) {
                            ui.postDelayed({ after() }, 300)
                        }
                    }
                },
                onFailed = {
                    ui.post {
                        if (settled.compareAndSet(false, true)) {
                            speakWelcomeViaTts(after)
                        }
                    }
                }
            )
            // Safety net: a hung ElevenLabs request must never hold the mic
            // hostage — fall back to the phone TTS after a few seconds.
            ui.postDelayed({
                if (settled.compareAndSet(false, true)) {
                    try {
                        speaker.stopAll()
                    } catch (_: Exception) {
                    }
                    speakWelcomeViaTts(after)
                }
            }, 9000)
        } catch (_: Exception) {
            speakWelcomeViaTts(after)
        }
    }

    /** The original greeting path: the phone's own voice engine. */
    private fun speakWelcomeViaTts(after: () -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        try {
            val existing = tts
            if (existing != null) {
                // Engine already warm: speak right away.
                existing.speak(
                    "Welcome to Myra Assistant",
                    TextToSpeech.QUEUE_FLUSH, null, "myra_welcome"
                )
                ui.postDelayed({ after() }, 2600)
            } else {
                tts = TextToSpeech(getApplication<Application>()) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.US
                        pickFemaleVoice()
                        tts?.speak(
                            "Welcome to Myra Assistant",
                            TextToSpeech.QUEUE_FLUSH, null, "myra_welcome"
                        )
                    }
                    ui.postDelayed({ after() }, 2600)
                }
            }
        } catch (_: Exception) {
            after()
        }
    }

    /** Prefers a female English TTS voice when the phone offers one. */
    private fun pickFemaleVoice() {
        try {
            val v = tts?.voices
                ?.firstOrNull { it.locale.language == "en" && it.name.contains("female", true) }
                ?: tts?.voices?.firstOrNull { it.locale.language == "en" }
            v?.let { tts?.voice = it }
        } catch (_: Exception) {
        }
    }

    fun stopSession() {
        // User tapped disconnect himself -> never auto-reconnect afterwards.
        // Drop the resumption handle too: the next start is a clean session.
        manualStop = true
        reconnectScheduled = false
        sessionGen++
        GeminiLiveClient.clearResumptionHandle()
        stopSessionInternal()
        _statusText.postValue("Idle")
    }

    /**
     * Full voice shutdown ("myra off ho jao"): MYRA says goodbye, then
     * EVERYTHING is torn down and stays dead — no watchdog, no reconnect,
     * no background services — until the user manually opens the app again.
     */
    private fun fullVoiceShutdown() {
        if (fullyShuttingDown) return
        fullyShuttingDown = true
        client?.sendText(
            "User ne kaha MYRA off ho jao. Ab EXACT ye kaho aur kuch nahi: " +
                    "'Theek hai " + Prefs.userName.ifBlank { "Dost" } + ", main off ho rahi hoon'."
        )
        _statusText.postValue("Off ho rahi hoon...")
        // Let the goodbye finish speaking, then kill everything.
        watchdogHandler.postDelayed({
            try {
                doFullShutdown()
            } catch (_: Exception) {
            }
        }, 7000)
    }

    private fun doFullShutdown() {
        val app = getApplication<Application>()
        // Persist FIRST: from here on, nothing may auto-restart.
        try {
            Prefs.fullyOff = true
        } catch (_: Exception) {
        }
        isFullyOff = true
        manualStop = true
        reconnectScheduled = false
        sessionGen++
        fullyShuttingDown = false
        try {
            watchdogHandler.removeCallbacks(watchdogRunnable)
        } catch (_: Exception) {
        }
        try {
            nudgeHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        try {
            sessionScope.cancel()
        } catch (_: Exception) {
        }
        stopSessionInternal()
        // Stop anything that could wake MYRA back up: the floating orb
        // and screen sharing.
        try {
            app.stopService(Intent(app, FloatingOrbService::class.java))
        } catch (_: Exception) {
        }
        try {
            app.stopService(Intent(app, ScreenShareService::class.java))
        } catch (_: Exception) {
        }
        try {
            setSharing(false)
        } catch (_: Exception) {
        }
        // Watchdog stays dead: watchdogStarted=false + the isFullyOff guard in
        // the runnable mean zero background work from here on.
        watchdogStarted = false
        _statusText.postValue("Off")
    }

    /** Tear down audio + socket without marking a manual stop. */
    private fun stopSessionInternal() {
        sessionConnecting = false
        // Session ending = the last interaction, for the Missing-You gap.
        try {
            com.myra.assistant.util.MoodManager.touchInteraction()
        } catch (_: Exception) {
        }
        CameraVision.sessionLive = false
        CameraVision.refresh(getApplication())
        CameraVision.onFrame = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            elSpeaker?.release()
        } catch (_: Exception) {
        }
        elSpeaker = null
        try {
            sessionScope.cancel()
        } catch (_: Exception) {
        }
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
        postConnected(false)
    }

    override fun onCleared() {
        try {
            watchdogHandler.removeCallbacks(watchdogRunnable)
        } catch (_: Exception) {
        }
        try {
            nudgeHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        try {
            sessionScope.cancel()
        } catch (_: Exception) {
        }
        try {
            audio?.release()
        } catch (_: Exception) {
        }
        try {
            elSpeaker?.release()
        } catch (_: Exception) {
        }
        elSpeaker = null
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
