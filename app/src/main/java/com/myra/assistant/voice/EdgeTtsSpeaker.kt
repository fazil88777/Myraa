package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.myra.assistant.util.MoodManager
import com.myra.assistant.util.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge Neural TTS speaker — MYRA's FREE realistic human voice.
 *
 * No API key, no account: this speaks through the same read-aloud
 * WebSocket service the Edge browser uses (the public "edge-tts"
 * protocol). It is used when Prefs.transcriptVoiceEnabled is on (Voice
 * Engine = EDGE in Settings): the Gemini Live session then runs in AUDIO
 * mode with outputAudioTranscription, Gemini's own audio is discarded,
 * and every model reply is fed here as text: the streamed text is split
 * into sentences, each sentence is synthesized with the selected Edge
 * neural voice (default ur-PK-UzmaNeural, Pakistani female) and the MP3s
 * are played back strictly in order on a single pipeline thread — the
 * first sentence starts speaking while the rest of the reply is still
 * being generated. SSML prosody follows her mood (MoodManager): sad /
 * crying / missing / hurt -> slower and lower, happy / romantic /
 * playful -> a touch brighter and faster.
 *
 * FAIL-SAFE CHAIN (per segment, so her voice NEVER just fails):
 *  1. Edge Neural TTS over the WebSocket below (~3s budget);
 *  2. Google Translate TTS over plain HTTPS (translate_tts, the same
 *     text, chunked to what the endpoint accepts);
 *  3. the phone's own TTS engine via [DeviceTts] (offline, always there).
 * Every layer fails SILENTLY into the next (log only) — there is no
 * user-visible "voice failed" message anywhere.
 *
 * Robustness rules (a TTS failure must NEVER break the session):
 *  - every network/playback error just moves to the next layer or, at
 *    worst, drops that one segment;
 *  - stopAll() (barge-in, session stop, shutdown) cancels the in-flight
 *    socket, clears the queue and halts playback immediately via a
 *    generation counter — stale work from an older moment can never play.
 */
class EdgeTtsSpeaker(context: Context) {

    companion object {
        private const val TAG = "EdgeTts"
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val GEC_VERSION = "1-132.0.2957.140"
        private const val WS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.0"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

        /** True when the Edge transcript voice is the selected engine. */
        fun isConfigured(): Boolean = Prefs.transcriptVoiceEnabled
    }

    private class Segment(
        val text: String,
        val gen: Int,
        val onPlayed: (() -> Unit)? = null,
        val onFailed: (() -> Unit)? = null
    )

    /** Mutable result box for one WebSocket synthesis (latch-guarded). */
    private class SynthResult {
        var audio: ByteArray? = null
        var failureCode: Int? = null
    }

    private val appCtx: Context = context.applicationContext

    // Own client (NOT the Live websocket client). Read timeout is 0 like
    // the Live client's — the per-segment ~3s latch in synthesize() is
    // the timeout that matters for a synthesis socket.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Short-timeout client for the Google Translate TTS fallback. */
    private val googleHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val queue = LinkedBlockingQueue<Segment>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "myra-edge-tts").apply { isDaemon = true }
    }

    /** Bumped by stopAll(): anything queued/playing from an older gen is dropped. */
    @Volatile private var generation = 0
    @Volatile private var released = false
    @Volatile private var currentSocket: WebSocket? = null
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var playLatch: CountDownLatch? = null

    /** Google Translate TTS wants a plain desktop-browser User-Agent. */
    private val googleUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    /** Streamed reply text not yet cut into a full sentence. */
    private val buffer = StringBuilder()

    init {
        executor.execute {
            while (!released) {
                val seg = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (seg.gen != generation) {
                    // Went stale before it even started (barge-in / stop).
                    seg.onFailed?.let { safeCallback(it) }
                    continue
                }
                playSegment(seg)
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API (called from MainViewModel's Live listener callbacks)
    // ------------------------------------------------------------------

    /** Feed one streamed text delta of the model's current reply. */
    fun enqueueDelta(delta: String) {
        if (released || delta.isEmpty()) return
        synchronized(buffer) { buffer.append(delta) }
        for (s in extractSentences(flush = false)) dispatch(s)
    }

    /** The model's turn ended: speak whatever partial sentence remains. */
    fun flushTurn() {
        if (released) return
        for (s in extractSentences(flush = true)) dispatch(s)
    }

    /**
     * Speak one standalone line (the session greeting). Exactly one of
     * [onPlayed] / [onFailed] fires, on the pipeline thread — the caller
     * arranges any fallback (e.g. Android TTS) from there.
     */
    fun speakOnce(text: String, onPlayed: () -> Unit, onFailed: () -> Unit) {
        if (released) {
            safeCallback(onFailed)
            return
        }
        val clean = cleanForSpeech(text)
        if (clean.isEmpty()) {
            safeCallback(onFailed)
            return
        }
        queue.offer(Segment(clean, generation, onPlayed, onFailed))
    }

    /** Barge-in / new turn / session stop: silence NOW, drop everything pending. */
    fun stopAll() {
        generation++
        queue.clear()
        synchronized(buffer) { buffer.setLength(0) }
        try {
            currentSocket?.cancel()
        } catch (_: Exception) {
        }
        currentSocket = null
        try {
            DeviceTts.stop()
        } catch (_: Exception) {
        }
        try {
            currentPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            currentPlayer?.release()
        } catch (_: Exception) {
        }
        currentPlayer = null
        playLatch?.countDown()
    }

    /** Session is over for good: stop everything and kill the pipeline thread. */
    fun release() {
        if (released) return
        released = true
        try {
            stopAll()
        } catch (_: Exception) {
        }
        try {
            executor.shutdownNow()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Pipeline: synthesize one segment, then play it, in order
    // ------------------------------------------------------------------

    private fun dispatch(sentence: String) {
        val clean = cleanForSpeech(sentence)
        if (clean.length < 2) return
        queue.offer(Segment(clean, generation))
    }

    /**
     * Speak one segment through the fail-safe chain — see the class doc.
     * Returns via the segment's callbacks: onPlayed when ANY layer spoke
     * it (or silence was deliberately chosen by a barge-in), onFailed
     * only when no layer could speak at all.
     */
    private fun playSegment(seg: Segment) {
        var handled = false
        try {
            // Layer 1: Edge Neural TTS (fresh WebSocket, ~3s budget).
            val edgeBytes = synthesize(seg.text)
            // Layer 2: Google Translate TTS (plain HTTPS, ~3s budget),
            // tried only when Edge produced nothing.
            val chunks: List<ByteArray>? =
                if (edgeBytes != null) listOf(edgeBytes)
                else synthesizeGoogle(seg.text, seg.gen)
            if (chunks != null && seg.gen == generation && !released) {
                when (playChunks(chunks, seg.gen)) {
                    // Fully/partly spoken, or interrupted before any audio
                    // (barge-in): either way the segment is DONE — the
                    // device voice must not repeat or fight it.
                    1, 0 -> handled = true
                    // -1: failed before a single byte played — fall through.
                }
            }
            // Layer 3: the phone's own TTS engine (DeviceTts) — offline,
            // always installed; blocks this thread so order is preserved.
            if (!handled && seg.gen == generation && !released) {
                handled = DeviceTts.speakBlocking(appCtx, seg.text) {
                    seg.gen != generation || released
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "segment dropped: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (handled) seg.onPlayed?.let { safeCallback(it) }
        else seg.onFailed?.let { safeCallback(it) }
    }

    /**
     * Play MP3 chunks back-to-back as ONE segment.
     * Returns 1 = spoken (fully, or partly before an interruption),
     * 0 = interrupted before any audio played, -1 = failed before any
     * audio played (the caller may fall through to the device voice).
     */
    private fun playChunks(chunks: List<ByteArray>, gen: Int): Int {
        var playedAny = false
        for (bytes in chunks) {
            if (gen != generation || released) return if (playedAny) 1 else 0
            var file: File? = null
            try {
                file = File.createTempFile("myra_tts_", ".mp3", appCtx.cacheDir)
                file.writeBytes(bytes)
                if (!playFile(file, gen)) return if (playedAny) 1 else -1
                playedAny = true
            } catch (e: Exception) {
                Log.d(TAG, "chunk play failed: ${e.javaClass.simpleName}: ${e.message}")
                return if (playedAny) 1 else -1
            } finally {
                try {
                    file?.delete()
                } catch (_: Exception) {
                }
            }
        }
        return 1
    }

    // ------------------------------------------------------------------
    // Layer 2: Google Translate TTS (free HTTPS MP3 stream)
    // ------------------------------------------------------------------

    /** Google `tl` code matching the selected Edge voice's language. */
    private fun googleLang(): String =
        if (Prefs.edgeVoice.startsWith("hi")) "hi" else "ur"

    /** Split into <=180-char chunks at word boundaries (endpoint limit). */
    private fun splitForGoogle(text: String): List<String> {
        if (text.length <= 180) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > 180) {
            var cut = rest.lastIndexOf(' ', 180)
            if (cut < 60) cut = 180
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Fetch this segment's MP3(s) from Google Translate TTS, in order.
     * Null on ANY failure (HTTP error, network, stale generation) —
     * silently: the device voice is the next layer.
     */
    private fun synthesizeGoogle(text: String, gen: Int): List<ByteArray>? {
        return try {
            val lang = googleLang()
            val parts = splitForGoogle(text)
            val out = ArrayList<ByteArray>(parts.size)
            for (part in parts) {
                if (gen != generation || released) return null
                val url = "https://translate.google.com/translate_tts?ie=UTF-8" +
                    "&q=" + java.net.URLEncoder.encode(part, "UTF-8") +
                    "&tl=" + lang + "&client=tw-ob"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", googleUa)
                    .build()
                val fetched = googleHttp.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "Google TTS failed: HTTP ${resp.code}")
                        null
                    } else {
                        resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                    }
                }
                if (fetched == null) return null
                out.add(fetched)
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            Log.d(TAG, "Google TTS error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Edge read-aloud WebSocket protocol (public "edge-tts" protocol)
    // ------------------------------------------------------------------

    /**
     * Sec-MS-GEC token: Windows-filetime-style ticks (seconds since the
     * 1601 epoch * 10^7), rounded DOWN to a 5-minute multiple, hashed
     * together with the trusted client token (SHA-256, uppercase hex).
     */
    private fun gecToken(): String {
        val unixSeconds = System.currentTimeMillis() / 1000
        var ticks = (unixSeconds + 11644473600L) * 10_000_000L
        ticks -= ticks % 3_000_000_000L
        val input = ticks.toString() + TRUSTED_TOKEN
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** Current UTC time in the JS-Date format the service expects. */
    private fun edgeTimestamp(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** 32 lowercase hex chars (a dashless UUID). */
    private fun newHexId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun configFrame(): String {
        val body = org.json.JSONObject()
            .put(
                "context",
                org.json.JSONObject().put(
                    "synthesis",
                    org.json.JSONObject().put(
                        "audio",
                        org.json.JSONObject()
                            .put(
                                "metadataoptions",
                                org.json.JSONObject()
                                    .put("sentenceBoundaryEnabled", "false")
                                    .put("wordBoundaryEnabled", "true")
                            )
                            .put("outputFormat", OUTPUT_FORMAT)
                    )
                )
            )
            .toString()
        return "X-Timestamp:${edgeTimestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" + body
    }

    private fun ssmlFrame(requestId: String, ssml: String): String =
        "X-Timestamp:${edgeTimestamp()}\r\n" +
            "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "Path:ssml\r\n\r\n" + ssml

    /** SSML prosody for her current mood (MoodManager), read at synthesis time. */
    private fun moodProsody(): Pair<String, String> =
        when (MoodManager.currentMood()) {
            MoodManager.Mood.SAD, MoodManager.Mood.CRYING,
            MoodManager.Mood.MISSING_USER, MoodManager.Mood.HURT ->
                "-10Hz" to "-15%"

            MoodManager.Mood.HAPPY, MoodManager.Mood.ROMANTIC,
            MoodManager.Mood.PLAYFUL ->
                "+8Hz" to "+5%"

            else -> "+0Hz" to "+0%"
        }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun buildSsml(text: String): String {
        val voice = Prefs.edgeVoice
        val locale = voice.split("-").take(2).joinToString("-").ifBlank { "ur-PK" }
        val (pitch, rate) = moodProsody()
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='$locale'><voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
            escapeXml(text) +
            "</prosody></voice></speak>"
    }

    /**
     * One Edge TTS synthesis over a fresh WebSocket: sends the
     * speech.config + ssml frames, accumulates the MP3 payload of every
     * binary "Path:audio" frame, and completes when the "Path:turn.end"
     * text frame arrives. Null on ANY error (upgrade failure, network,
     * timeout, protocol) — the caller then tries the next layer of the
     * fail-safe chain. The attempt is capped at ~3 seconds: on networks
     * where this endpoint is blocked, failing FAST is what lets the
     * fallback voices answer instead of silence.
     */
    private fun synthesize(text: String): ByteArray? {
        val result = SynthResult()
        val latch = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val requestId = newHexId()
        val url = WS_BASE +
            "?TrustedClientToken=" + TRUSTED_TOKEN +
            "&Sec-MS-GEC=" + gecToken() +
            "&Sec-MS-GEC-Version=" + GEC_VERSION +
            "&ConnectionId=" + newHexId()
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Origin", ORIGIN)
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(configFrame())
                    webSocket.send(ssmlFrame(requestId, buildSsml(text)))
                } catch (e: Exception) {
                    if (result.failureCode == null) result.failureCode = -1
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, message: String) {
                if (message.contains("Path:turn.end")) {
                    result.audio = audio.toByteArray()
                    latch.countDown()
                    try {
                        webSocket.close(1000, null)
                    } catch (_: Exception) {
                    }
                }
                // turn.start / response / audio.metadata frames: ignored.
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    if (bytes.size < 2) return
                    val headerLen =
                        ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size < 2 + headerLen) return
                    val header = bytes.substring(2, 2 + headerLen)
                        .string(Charsets.UTF_8)
                    if (header.contains("Path:audio")) {
                        audio.write(bytes.substring(2 + headerLen).toByteArray())
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A failed upgrade carries the HTTP status code; a network
                // failure has no response -> -1.
                if (result.failureCode == null) result.failureCode = response?.code ?: -1
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Closed before turn.end (and not by our own close after a
                // successful turn.end — that path already set the audio).
                if (result.audio == null && result.failureCode == null) {
                    result.failureCode = -1
                }
                latch.countDown()
            }
        })
        currentSocket = ws
        val finished = try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (currentSocket == ws) currentSocket = null
        if (!finished) {
            try {
                ws.cancel()
            } catch (_: Exception) {
            }
            Log.d(TAG, "TTS synthesis timed out (3s) — next layer")
            return null
        }
        val bytes = result.audio
        if (bytes == null || bytes.isEmpty()) {
            // Also the stopAll()-cancelled case (barge-in / session stop):
            // same handling — no audio, next layer checks the generation
            // first and stays silent.
            Log.d(TAG, "TTS synthesis failed: code=${result.failureCode}")
            return null
        }
        return bytes
    }

    /** Play one MP3 file to completion. False when stopped or on error. */
    private fun playFile(file: File, gen: Int): Boolean {
        val latch = CountDownLatch(1)
        playLatch = latch
        // Written by the completion listener before countDown(), read after
        // await() returns — the latch itself provides the memory barrier.
        var completed = false
        val mp = MediaPlayer()
        currentPlayer = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                try {
                    if (gen == generation && !released) p.start() else latch.countDown()
                } catch (_: Exception) {
                    latch.countDown()
                }
            }
            mp.setOnCompletionListener {
                completed = true
                latch.countDown()
            }
            mp.setOnErrorListener { _, _, _ ->
                latch.countDown()
                true
            }
            mp.prepareAsync()
            // Wait for completion, but bail out promptly when stopAll()
            // bumps the generation (stopAll also counts the latch down).
            while (true) {
                if (latch.await(150, TimeUnit.MILLISECONDS)) break
                if (gen != generation || released) break
            }
            return completed && gen == generation
        } catch (e: Exception) {
            Log.d(TAG, "playback error: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            try {
                mp.release()
            } catch (_: Exception) {
            }
            if (currentPlayer == mp) currentPlayer = null
            if (playLatch == latch) playLatch = null
        }
    }

    private fun safeCallback(cb: () -> Unit) {
        try {
            cb()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Sentence cutting + speech cleanup
    // ------------------------------------------------------------------

    /**
     * Cut every COMPLETE sentence out of the streaming buffer and return
     * them in order, keeping the trailing partial sentence buffered. With
     * flush = true the partial remainder is returned too (turn end).
     * Terminators: . ! ? … — with "..." treated as ONE terminator and
     * decimal numbers (3.5) never split. A fragment that grows past ~240
     * chars without any terminator is cut at a word gap so the first
     * words start speaking quickly instead of waiting forever.
     */
    private fun extractSentences(flush: Boolean): List<String> {
        val out = mutableListOf<String>()
        synchronized(buffer) {
            val s = buffer.toString()
            var start = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    if (c == '.' && i > 0 && i + 1 < s.length &&
                        s[i - 1].isDigit() && s[i + 1].isDigit()
                    ) {
                        i++
                        continue
                    }
                    var j = i
                    while (j < s.length && (s[j] == '.' || s[j] == '!' || s[j] == '?' || s[j] == '…')) j++
                    val seg = s.substring(start, j).trim()
                    if (seg.length >= 2) out.add(seg)
                    start = j
                    i = j
                } else {
                    i++
                }
            }
            var rest = s.substring(start)
            if (!flush && rest.length > 240) {
                val cut = rest.lastIndexOf(' ', 240)
                if (cut > 40) {
                    val seg = rest.substring(0, cut).trim()
                    if (seg.length >= 2) out.add(seg)
                    rest = rest.substring(cut + 1)
                }
            }
            if (flush) {
                val seg = rest.trim()
                if (seg.isNotEmpty()) out.add(seg)
                rest = ""
            }
            buffer.setLength(0)
            buffer.append(rest)
        }
        return out
    }

    /**
     * Strip anything that must not be read aloud: *stage directions* and
     * stray asterisks (the prompt forbids them in transcript-voice mode,
     * but if one slips through the TTS would read it literally), markdown
     * marks.
     */
    private fun cleanForSpeech(raw: String): String {
        var t = raw.replace(Regex("\\*[^*\\n]{0,80}\\*"), " ")
        t = t.replace("*", "")
        t = t.replace(Regex("[#`>]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
