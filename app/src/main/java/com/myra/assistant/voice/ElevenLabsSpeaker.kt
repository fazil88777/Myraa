package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.myra.assistant.util.Prefs
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs text-to-speech speaker — MYRA's realistic human voice.
 *
 * Used only when the user has saved BOTH an ElevenLabs API key and a
 * Voice ID in Settings (see Prefs.elevenLabsEnabled). The Gemini Live
 * session then runs in TEXT mode and every model reply is fed here:
 * the streamed text is split into sentences, each sentence is synthesized
 * with eleven_multilingual_v2 and the MP3s are played back strictly in
 * order on a single pipeline thread — the first sentence starts speaking
 * while the rest of the reply is still being generated.
 *
 * Robustness rules (a TTS failure must NEVER break the session):
 *  - every network/playback error just drops that one segment;
 *  - stopAll() (barge-in, session stop, shutdown) cancels the in-flight
 *    request, clears the queue and halts playback immediately via a
 *    generation counter — stale work from an older moment can never play.
 */
class ElevenLabsSpeaker(context: Context) {

    companion object {
        private const val TAG = "ElevenLabs"
        private const val API_BASE = "https://api.elevenlabs.io/v1/text-to-speech/"
        private const val MODEL_ID = "eleven_multilingual_v2"

        /** True when the user configured key + voice id in Settings. */
        fun isConfigured(): Boolean = Prefs.elevenLabsEnabled
    }

    private class Segment(
        val text: String,
        val gen: Int,
        val onPlayed: (() -> Unit)? = null,
        val onFailed: (() -> Unit)? = null
    )

    private val appCtx: Context = context.applicationContext

    // Own client with real timeouts (NOT the Live websocket client, whose
    // read timeout is infinite on purpose).
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val queue = LinkedBlockingQueue<Segment>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "myra-elevenlabs").apply { isDaemon = true }
    }

    /** Bumped by stopAll(): anything queued/playing from an older gen is dropped. */
    @Volatile private var generation = 0
    @Volatile private var released = false
    @Volatile private var currentCall: Call? = null
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var playLatch: CountDownLatch? = null

    /**
     * Synthesis failure reporter, wired by MainViewModel: invoked from the
     * pipeline thread on EVERY failed synthesis with the HTTP status code
     * (401 bad key/scope, 404 unknown Voice ID, 429 quota, ...) or -1 for a
     * network/other failure. The ViewModel turns the FIRST failure of a
     * session into a user-visible toast — a failed request must never again
     * be only a silently dropped segment. NOT invoked when the request was
     * merely cancelled by stopAll() (barge-in / session stop is no failure).
     */
    @Volatile
    var onError: ((Int) -> Unit)? = null

    private fun notifyError(code: Int) {
        try {
            onError?.invoke(code)
        } catch (_: Exception) {
        }
    }

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
            currentCall?.cancel()
        } catch (_: Exception) {
        }
        currentCall = null
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

    private fun playSegment(seg: Segment) {
        var ok = false
        var file: File? = null
        try {
            val bytes = synthesize(seg.text)
            if (bytes != null && seg.gen == generation && !released) {
                file = File.createTempFile("myra_el_", ".mp3", appCtx.cacheDir)
                file.writeBytes(bytes)
                ok = playFile(file, seg.gen)
            }
        } catch (e: Exception) {
            Log.d(TAG, "segment dropped: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }
        if (ok) seg.onPlayed?.let { safeCallback(it) }
        else seg.onFailed?.let { safeCallback(it) }
    }

    /** One ElevenLabs TTS request. Null on ANY error (bad key, quota, network). */
    private fun synthesize(text: String): ByteArray? {
        val key = Prefs.elevenLabsKey
        val voiceId = Prefs.elevenLabsVoiceId
        if (key.isBlank() || voiceId.isBlank()) {
            notifyError(-1)
            return null
        }
        var call: Call? = null
        return try {
            val body = JSONObject()
                .put("text", text)
                .put("model_id", MODEL_ID)
                .put(
                    "voice_settings",
                    JSONObject()
                        .put("stability", 0.35)
                        .put("similarity_boost", 0.80)
                )
                .toString()
            val request = Request.Builder()
                .url(API_BASE + voiceId)
                .addHeader("xi-api-key", key)
                .addHeader("Accept", "audio/mpeg")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val c = http.newCall(request)
            call = c
            currentCall = c
            c.execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Log the code only — never the key, never the body.
                    Log.d(TAG, "TTS request failed: HTTP ${resp.code}")
                    notifyError(resp.code)
                    null
                } else {
                    resp.body?.bytes()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "TTS request error: ${e.javaClass.simpleName}: ${e.message}")
            // A call cancelled by stopAll() (barge-in / session stop) is
            // NOT a failure — never surface it as one.
            if (call?.isCanceled() != true) notifyError(-1)
            null
        } finally {
            currentCall = null
        }
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
     * stray asterisks (the prompt forbids them in ElevenLabs mode, but if
     * one slips through the TTS would read it literally), markdown marks.
     */
    private fun cleanForSpeech(raw: String): String {
        var t = raw.replace(Regex("\\*[^*\\n]{0,80}\\*"), " ")
        t = t.replace("*", "")
        t = t.replace(Regex("[#`>]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
