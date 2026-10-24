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
import java.net.URLEncoder
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
 * accumulated and the WHOLE reply is synthesized as one request
 * with eleven_multilingual_v2 and played back on a single pipeline
 * thread — one request per reply keeps the prosody continuous,
 * while per-sentence requests made her speak every sentence
 * separately, with a gap at every boundary. (Only a reply longer
 * than ~400 chars is split mid-turn, at a sentence boundary.)
 * Roman-Urdu text is transliterated to Devanagari before it is
 * sent — see transliterateIfRomanUrdu for why.
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
        val onFailed: (() -> Unit)? = null,
        /** False only for the canned greeting (speakOnce never transliterates). */
        val transliterate: Boolean = true
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

    /** Streamed reply text accumulated until turn end (whole-turn synthesis). */
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

    /**
     * Feed one streamed text delta of the model's current reply. The
     * text is only ACCUMULATED here — nothing is dispatched per
     * sentence anymore (see flushTurn for why). One exception: a
     * reply that grows past ~400 chars is split mid-turn at the last
     * sentence boundary at or before 400 chars, so a very long reply
     * still starts speaking before generation finishes.
     */
    override fun enqueueDelta(delta: String) {
        if (released || delta.isEmpty()) return
        synchronized(buffer) { buffer.append(delta) }
        while (true) {
            val chunk = drainOverflowChunk() ?: break
            dispatch(chunk)
        }
    }

    /**
     * The model's turn ended: speak the whole accumulated reply as
     * ONE segment — a single ElevenLabs request for the entire
     * reply, so her voice flows continuously instead of restarting
     * (with a synthesis + playback gap) at every sentence.
     */
    override fun flushTurn() {
        if (released) return
        val whole = synchronized(buffer) {
            val s = buffer.toString()
            buffer.setLength(0)
            s
        }
        val text = whole.trim()
        if (text.isNotEmpty()) dispatch(text)
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
        queue.offer(Segment(clean, generation, onPlayed, onFailed, transliterate = false))
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
            val bytes = synthesize(seg.text, seg.transliterate)
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
    private fun synthesize(text: String, transliterate: Boolean = true): ByteArray? {
        val key = Prefs.elevenLabsKey
        val voiceId = Prefs.elevenLabsVoiceId
        if (key.isBlank() || voiceId.isBlank()) {
            notifyError(-1, "API key / Voice ID set nahi hai")
            return null
        }
        // What ElevenLabs actually receives: a Roman-Urdu reply is
        // transliterated to Devanagari first (fluency — see
        // transliterateIfRomanUrdu); anything else, and every
        // transliteration failure, keeps the original text.
        val speakText = if (transliterate) transliterateIfRomanUrdu(text) else text
        var call: Call? = null
        return try {
            val body = JSONObject()
                .put("text", speakText)
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
     * Overflow guard for very long replies: if the buffered text has
     * grown past ~400 chars, cut the oldest part at the LAST sentence
     * terminator that ends at or before position 400 and return it
     * for dispatch, keeping the remainder buffered. Returns null when
     * the buffer is still short enough — or when no terminator
     * qualifies (a single monster sentence is left for flushTurn).
     */
    private fun drainOverflowChunk(): String? {
        synchronized(buffer) {
            val s = buffer.toString()
            if (s.length <= 400) return null
            var cutEnd = -1
            var i = 0
            while (i < s.length && i <= 400) {
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
                    if (j <= 400) cutEnd = j
                    i = j
                } else {
                    i++
                }
            }
            if (cutEnd < 2) return null
            val chunk = s.substring(0, cutEnd).trim()
            buffer.setLength(0)
            buffer.append(s.substring(cutEnd))
            return chunk.ifEmpty { null }
        }
    }

    // ------------------------------------------------------------------
    // Roman Urdu -> Devanagari transliteration (ElevenLabs fluency)
    // ------------------------------------------------------------------

    /**
     * ElevenLabs' eleven_multilingual_v2 does NOT support Urdu, and
     * it reads Roman-Urdu (Latin script) text haltingly — word by
     * word, with gaps. It DOES support Hindi, and spoken Urdu and
     * spoken Hindi are the same language; Devanagari also has exact
     * letters for every Urdu sound. So text bound for ElevenLabs is
     * transliterated to Devanagari first (via Google Input Tools).
     * Only the TTS-bound text changes — the chat keeps showing
     * Roman Urdu. ANY failure here (network, timeout, malformed
     * reply) silently falls back to the original text:
     * transliteration must never break or block speech.
     */
    private val transliterationCache = HashMap<String, String>()

    /** Tight timeouts on purpose: a slow transliteration must never
     *  hold up her voice — on any delay the original text is used. */
    private val transliterationHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /** Common Roman-Urdu words. A segment counts as Roman Urdu when
     *  at least two of its whole words come from this set — that
     *  keeps plain English (which also contains "main", "he", ...)
     *  untouched. */
    private val romanUrduMarkers = setOf(
        "hai", "he", "hoon", "hun", "mein", "main", "tum", "aap",
        "ka", "ki", "ke", "ko", "se", "raha", "rahi", "rahe",
        "karo", "kar", "gaya", "gayi", "hua", "hui", "nahi", "bhi",
        "aur", "ye", "yeh", "wo", "woh", "mera", "meri", "tumhari",
        "kya", "abhi", "phir", "matlab", "bilkul", "theek", "thik",
        "acha", "achha", "bohat", "bahut", "zaroor", "shayad",
        "lekin", "magar", "kyun", "kyunke", "jab", "tab", "idhar",
        "udhar", "ghar", "baat", "awaaz", "sun"
    )

    private fun transliterateIfRomanUrdu(text: String): String {
        // Already written in a script ElevenLabs handles natively
        // (Urdu/Arabic script U+0600–U+06FF, or Devanagari
        // U+0900–U+097F) — leave it untouched.
        if (text.any { it in '؀'..'ۿ' || it in 'ऀ'..'ॿ' }) return text
        val words = text.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }
        if (words.count { it in romanUrduMarkers } < 2) return text
        synchronized(transliterationCache) {
            transliterationCache[text]?.let { return it }
        }
        val converted = fetchTransliteration(text) ?: return text
        synchronized(transliterationCache) {
            if (transliterationCache.size > 200) transliterationCache.clear()
            transliterationCache[text] = converted
        }
        return converted
    }

    /**
     * One Google Input Tools call converting the WHOLE phrase in a
     * single request. Reply shape:
     * ["SUCCESS",[["<chunk>",["<converted chunk>"],[],{...}], ...]]
     * Long input comes back as MULTIPLE segments in element [1] —
     * one per chunk of the input — so the conversions of ALL
     * segments must be joined in order; reading only segment [0]
     * truncated long replies to their first words (the v30 bug).
     * Null on any problem (the caller then uses the original text).
     */
    private fun fetchTransliteration(text: String): String? {
        return try {
            val url = "https://inputtools.google.com/request?itc=hi-t-i0-und" +
                "&num=1&cp=0&cs=1&ie=utf-8&oe=utf-8&text=" +
                URLEncoder.encode(text, "UTF-8")
            val request = Request.Builder().url(url).get().build()
            transliterationHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        null
                    } else {
                        val segments = JSONArray(body).optJSONArray(1)
                        if (segments == null || segments.length() == 0) {
                            null
                        } else {
                            // Join EVERY segment's conversion, in
                            // order — the segments cover successive
                            // chunks of the input and their texts
                            // already carry the joining spaces.
                            val joined = StringBuilder()
                            for (i in 0 until segments.length()) {
                                val segment = segments.optJSONArray(i)
                                val candidate =
                                    segment?.optJSONArray(1)?.optString(0)
                                if (!candidate.isNullOrBlank()) {
                                    joined.append(candidate)
                                } else {
                                    // No conversion for this chunk —
                                    // keep its original words so no
                                    // part of the reply is dropped.
                                    joined.append(segment?.optString(0).orEmpty())
                                }
                            }
                            joined.toString().takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "transliteration failed, original text used: ${e.javaClass.simpleName}")
            null
        }
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
