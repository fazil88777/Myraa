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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ElevenLabs text-to-speech speaker — MYRA's realistic human voice
 * (restored: this engine shipped in v11–v15, was removed in v16, and
 * returns now as one selectable Voice Engine among the others).
 *
 * Used only when the Voice Engine is ELEVENLABS in Settings AND the
 * user has saved BOTH an ElevenLabs API key and a Voice ID there (see
 * Prefs.elevenLabsEnabled). The Gemini Live session then runs — like
 * the other transcript engines — in AUDIO mode WITH output
 * transcription: Gemini's own audio is discarded unheard and every
 * model reply's transcript text is fed here: the streamed text is
 * split into sentences, each sentence is synthesized with
 * eleven_multilingual_v2 and the MP3s are played back strictly in
 * order on a single pipeline thread — the first sentence starts
 * speaking while the rest of the reply is still being generated.
 *
 * Robustness rules (a TTS failure must NEVER break the session):
 *  - every network/playback error just drops that one segment;
 *  - stopAll() (barge-in, session stop, shutdown) cancels the in-flight
 *    request, clears the queue and halts playback immediately via a
 *    generation counter — stale work from an older moment can never play.
 */
class ElevenLabsSpeaker(context: Context) : VoiceSpeaker {

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
     * Segments offered to the queue but not yet finished (spoken,
     * failed, or dropped as stale). Drives [isSpeaking] — the
     * mute-while-speaking mic guard in MainViewModel. Incremented
     * exactly once per queue offer, decremented exactly once when the
     * pipeline retires that segment; stopAll() zeroes it together with
     * the queue clear, so it can never stick true.
     */
    private val pendingSegments = AtomicInteger(0)

    override val isSpeaking: Boolean
        get() = pendingSegments.get() > 0

    private fun segmentFinished() {
        // Floored at zero: stopAll() may already have zeroed the count
        // on behalf of the segment that was playing while it cleared.
        pendingSegments.updateAndGet { n -> if (n > 0) n - 1 else 0 }
    }

    /**
     * Synthesis failure reporter, wired by MainViewModel: invoked from the
     * pipeline thread on EVERY failed synthesis with the HTTP status code
     * (401 bad key/scope, 402 library voice on the free tier, 404 unknown
     * Voice ID, 429 quota, ...) or -1 for a network/other failure, PLUS
     * the server's own human-readable message parsed from the error body
     * when ElevenLabs sent one (null when it didn't). The ViewModel turns
     * the FIRST failure of a session into a user-visible toast carrying
     * code + message — a failed request must never again be only a
     * silently dropped segment. NOT invoked when the request was merely
     * cancelled by stopAll() (barge-in / session stop is no failure).
     */
    @Volatile
    var onError: ((Int, String?) -> Unit)? = null

    private fun notifyError(code: Int, serverMessage: String?) {
        try {
            onError?.invoke(code, serverMessage)
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
                    segmentFinished()
                    continue
                }
                try {
                    playSegment(seg)
                } finally {
                    segmentFinished()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API (called from MainViewModel's Live listener callbacks)
    // ------------------------------------------------------------------

    /** Feed one streamed text delta of the model's current reply. */
    override fun enqueueDelta(delta: String) {
        if (released || delta.isEmpty()) return
        synchronized(buffer) { buffer.append(delta) }
        for (s in mergeShortSentences(extractSentences(flush = false))) dispatch(s)
    }

    /** The model's turn ended: speak whatever partial sentence remains. */
    override fun flushTurn() {
        if (released) return
        for (s in mergeShortSentences(extractSentences(flush = true))) dispatch(s)
    }

    /**
     * Speak one standalone line (the session greeting). Exactly one of
     * [onPlayed] / [onFailed] fires, on the pipeline thread — the caller
     * simply moves on without speech from there (there is no fallback
     * voice anywhere in the app).
     */
    override fun speakOnce(text: String, onPlayed: () -> Unit, onFailed: () -> Unit) {
        if (released) {
            safeCallback(onFailed)
            return
        }
        val clean = cleanForSpeech(text)
        if (clean.isEmpty()) {
            safeCallback(onFailed)
            return
        }
        pendingSegments.incrementAndGet()
        queue.offer(Segment(clean, generation, onPlayed, onFailed))
    }

    /** Barge-in / new turn / session stop: silence NOW, drop everything pending. */
    override fun stopAll() {
        generation++
        queue.clear()
        pendingSegments.set(0)
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
    override fun release() {
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
        pendingSegments.incrementAndGet()
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

    /**
     * One ElevenLabs TTS request. Null on ANY error (bad key, quota, network).
     * A transient HTTP failure (429 rate-limit / heavy traffic, or 5xx) is
     * retried ONCE after a short pause — dropping a whole sentence for a
     * momentary 429 was losing chunks of her replies. Only the final failed
     * attempt surfaces an error to the user.
     */
    private fun synthesize(text: String): ByteArray? {
        val key = Prefs.elevenLabsKey
        val voiceId = Prefs.elevenLabsVoiceId
        if (key.isBlank() || voiceId.isBlank()) {
            notifyError(-1, "API key / Voice ID set nahi hai")
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
                        .put("style", 0.45)
                        .put("use_speaker_boost", true)
                )
                .toString()
            val request = Request.Builder()
                .url(API_BASE + voiceId)
                .addHeader("xi-api-key", key)
                .addHeader("Accept", "audio/mpeg")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            var attempt = 0
            var result: ByteArray? = null
            var finished = false
            while (!finished) {
                attempt++
                // A Call can be executed only once — every attempt builds a
                // fresh call from the same request.
                val c = http.newCall(request)
                call = c
                currentCall = c
                val retry = c.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Log the code only — never the key. The error BODY is
                        // peeked (bounded, not consumed) solely to surface
                        // ElevenLabs' own explanation to the user via onError.
                        Log.d(TAG, "TTS request failed: HTTP ${resp.code}")
                        if ((resp.code == 429 || resp.code in 500..599) && attempt == 1) {
                            // Transient failure — retry once (below) instead
                            // of dropping the segment, and do NOT notify yet:
                            // only the final failed attempt surfaces an error.
                            true
                        } else {
                            val serverMsg = try {
                                parseErrorMessage(resp.peekBody(8192).string())
                            } catch (_: Exception) {
                                null
                            }
                            notifyError(resp.code, serverMsg)
                            false
                        }
                    } else {
                        result = resp.body?.bytes()
                        false
                    }
                }
                if (retry) {
                    // Short backoff before the single retry. stopAll() can
                    // still cancel the retried call via currentCall, and a
                    // cancelled execute lands in the catch below, where it
                    // is treated as a stop — not an error — exactly as
                    // before. (This runs on the speaker pipeline thread,
                    // never the UI thread.)
                    try {
                        Thread.sleep(1200)
                    } catch (_: InterruptedException) {
                    }
                    if (released) finished = true
                } else {
                    finished = true
                }
            }
            result
        } catch (e: Exception) {
            Log.d(TAG, "TTS request error: ${e.javaClass.simpleName}: ${e.message}")
            // A call cancelled by stopAll() (barge-in / session stop) is
            // NOT a failure — never surface it as one.
            if (call?.isCanceled() != true) notifyError(-1, null)
            null
        } finally {
            currentCall = null
        }
    }

    /**
     * Pull a human-readable message out of an ElevenLabs error body.
     * Shapes seen in the wild: {"detail":{"status":..,"message":..}},
     * {"detail":"plain string"}, {"detail":[{"msg":..}]} (validation),
     * or a top-level "message". Whitespace-collapsed and capped at 140
     * chars so it fits a long toast. Null when nothing usable is found.
     */
    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JSONObject(body)
            val raw: String? = when (val detail = root.opt("detail")) {
                is JSONObject ->
                    detail.optString("message").ifBlank { detail.optString("status") }
                        .ifBlank { null }
                is String -> detail
                is JSONArray -> {
                    val first = detail.optJSONObject(0)
                    val m = first?.optString("msg")?.ifBlank { null }
                        ?: first?.optString("message")
                    m?.ifBlank { null }
                }
                else -> root.optString("message").ifBlank { null }
            }
            raw?.replace(Regex("\\s+"), " ")?.trim()?.take(140)?.ifBlank { null }
        } catch (_: Exception) {
            null
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

    /** Sentence terminators, incl. the Urdu full stop (۔, U+06D4) and
     *  Urdu question mark (؟, U+061F) — Urdu transcriptions punctuate
     *  with these, and without them such sentences never cut. */
    private fun isTerminator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…' || c == '۔' || c == '؟'

    /**
     * Cut every COMPLETE sentence out of the streaming buffer and return
     * them in order, keeping the trailing partial sentence buffered. With
     * flush = true the partial remainder is returned too (turn end).
     * Terminators: . ! ? … ۔ ؟ — with runs of terminators ("...", "?!")
     * treated as ONE terminator and decimal numbers (3.5) never split.
     * A fragment that grows past ~240 chars without any terminator is
     * cut at a word gap so the first words start speaking quickly
     * instead of waiting forever.
     */
    private fun extractSentences(flush: Boolean): List<String> {
        val out = mutableListOf<String>()
        synchronized(buffer) {
            val s = buffer.toString()
            var start = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (isTerminator(c)) {
                    if (c == '.' && i > 0 && i + 1 < s.length &&
                        s[i - 1].isDigit() && s[i + 1].isDigit()
                    ) {
                        i++
                        continue
                    }
                    var j = i
                    while (j < s.length && isTerminator(s[j])) j++
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
     * Merge run-on short fragments into fuller segments before they
     * are dispatched: ElevenLabs voices one complete, longer sentence
     * far more fluidly than a 10-char scrap — every merge also means
     * one less synthesis request and one less playback gap. A leading
     * segment shorter than ~40 chars absorbs the following sentences
     * (single-space join) until it reaches that size; longer sentences
     * pass through untouched, in order.
     */
    private fun mergeShortSentences(sentences: List<String>): List<String> {
        if (sentences.size < 2) return sentences
        val out = mutableListOf<String>()
        var current = sentences[0]
        for (k in 1 until sentences.size) {
            if (current.length < 40) {
                current = current + " " + sentences[k]
            } else {
                out.add(current)
                current = sentences[k]
            }
        }
        out.add(current)
        return out
    }

    /**
     * Strip anything that must not be read aloud: bounded bracket
     * spans — [like this], {like this}, (like this) — *stage
     * directions*, stray asterisks, standalone emotion labels
     * ("Crying: ...", "Sad — ...") at the start of the segment or
     * right after a sentence boundary, and markdown marks. The prompt
     * forbids all of these in transcript-voice mode, but if one slips
     * through the TTS would read it literally as words ("crying",
     * "sad"), so the speech path scrubs them itself.
     */
    private fun cleanForSpeech(raw: String): String {
        var t = raw
        // Bounded spans first (no newline inside, bounded length).
        t = t.replace(Regex("\\[[^\\]\\n]{0,100}\\]"), " ")
        t = t.replace(Regex("\\{[^}\\n]{0,100}\\}"), " ")
        t = t.replace(Regex("\\([^)\\n]{0,100}\\)"), " ")
        // *stage directions* spans, then any stray asterisks.
        t = t.replace(Regex("\\*[^*\\n]{0,80}\\*"), " ")
        t = t.replace("*", "")
        // Standalone emotion labels: keep the sentence boundary (first
        // capture group), drop the label and its separator.
        val emotionLabels = "(?i)(crying|sad|happy|angry|emotional|excited|" +
            "laughing|whispering|sighing|sighs|giggles|sniffles|weeps|weeping|" +
            "smiling|nervous|shy|upset|worried|romantic)"
        t = t.replace(
            Regex("(^|[.!?]\\s+)" + emotionLabels + "\\s*[:—-]\\s*"),
            "$1"
        )
        t = t.replace(Regex("[#`>]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
