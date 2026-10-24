package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.myra.assistant.util.Prefs
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * model reply's transcript text is fed here.
 *
 * HOW A REPLY IS SPOKEN (v33): the v30 whole-turn REST design made
 * her wait — silently — for the FULL reply text, one full synthesis
 * and the complete MP3 before the first sound (~5 s of dead air,
 * because first-audio latency was generation + transliteration +
 * full synthesis). Now a reply STREAMS: as soon as its first
 * sentence is complete it goes over ElevenLabs' stream-input
 * WebSocket (eleven_multilingual_v2, output_format=pcm_24000), the
 * returned PCM starts playing through an AudioTrack almost
 * immediately, and the remaining sentences follow over the same
 * socket, so speech flows continuously, like Gemini's own voice.
 * If the socket cannot be opened or dies mid-turn, whatever text
 * has not been spoken yet falls back to the v30 REST path below
 * (synthesize the whole remainder + MediaPlayer) — speech is
 * never lost, only delayed. speakOnce (the greeting) always uses
 * the REST path.
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
        private const val WS_BASE = "wss://api.elevenlabs.io/v1/text-to-speech/"
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
    // Streaming state (v33). The REST queue above remains the
    // greeting path AND the automatic fallback for any turn the
    // socket cannot carry.
    // ------------------------------------------------------------------

    /** One queued unit of streamed work for the stream worker. */
    private sealed class StreamItem {
        /** Generation this item belongs to (stale items are dropped). */
        abstract val gen: Int

        /** A complete sentence (or overflow chunk) of the turn. */
        class Chunk(override val gen: Int, val text: String) : StreamItem()

        /**
         * Turn end: [text] is the final partial sentence (may be
         * blank when everything was already chunked) — send it (if
         * any), then EOS, then ride the session to completion.
         */
        class Final(override val gen: Int, val text: String) : StreamItem()
    }

    private val streamQueue = LinkedBlockingQueue<StreamItem>()
    private val streamExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "myra-elevenlabs-stream").apply { isDaemon = true }
    }

    /**
     * Generation whose stream failed over to REST: later chunks of
     * that same turn go straight to the REST queue instead of the
     * socket. Generations only ever increase, so a stale value can
     * never match a future turn.
     */
    @Volatile private var streamFailedGen = -1

    /** Generation for which stream chunks were offered this turn. */
    @Volatile private var streamTurnGen = -1

    /**
     * Socket client: no read timeout (a WebSocket lives as long as
     * the turn does), plus pings so NATs don't reap it mid-reply.
     */
    private val wsHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** The live stream session, so stopAll() can tear it down NOW. */
    @Volatile private var activeSession: ElStreamSession? = null

    init {
        streamExecutor.execute { runStreamWorker() }
    }

    // ------------------------------------------------------------------
    // Public API (called from MainViewModel's Live listener callbacks)
    // ------------------------------------------------------------------

    /**
     * Feed one streamed text delta of the model's current reply.
     * Text accumulates in the buffer; every COMPLETE sentence is cut
     * and handed to the stream worker immediately, so the first
     * sentence starts sounding while Gemini is still writing the
     * rest — no more waiting for the whole reply. (A monster stretch
     * with no sentence boundary still falls back to the old ~400-char
     * overflow cut.) If this turn's stream has already failed over,
     * chunks go straight to the REST queue instead.
     */
    override fun enqueueDelta(delta: String) {
        if (released || delta.isEmpty()) return
        synchronized(buffer) { buffer.append(delta) }
        while (true) {
            val chunk = drainSentenceChunk() ?: drainOverflowChunk() ?: break
            offerChunk(chunk)
        }
    }

    /**
     * The model's turn ended. Whatever partial sentence remains
     * becomes the stream's final chunk (followed by EOS). When the
     * stream already failed over for this turn, the remainder goes
     * to the REST queue as one segment — v30's whole-turn synthesis.
     */
    override fun flushTurn() {
        if (released) return
        val whole = synchronized(buffer) {
            val s = buffer.toString()
            buffer.setLength(0)
            s
        }
        val text = whole.trim()
        val gen = generation
        if (streamFailedGen == gen) {
            if (text.isNotEmpty()) dispatch(text)
            return
        }
        if (text.isEmpty() && streamTurnGen != gen) return // nothing happened this turn
        // A reply that produced no chunk during the turn (one short
        // sentence) still streams — as a single Final chunk.
        streamQueue.offer(StreamItem.Final(gen, text))
    }

    /** Route one cut chunk: socket normally, REST queue after a failover. */
    private fun offerChunk(chunk: String) {
        val gen = generation
        if (streamFailedGen == gen) {
            dispatch(chunk)
            return
        }
        if (streamTurnGen != gen) streamTurnGen = gen
        streamQueue.offer(StreamItem.Chunk(gen, chunk))
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
        // Streaming teardown: drop queued stream work and silence the
        // live session NOW (socket cancelled, track torn down). The
        // stream worker sees the generation bump and abandons
        // anything it was holding for the dead turn.
        streamQueue.clear()
        streamTurnGen = -1
        try {
            activeSession?.kill()
        } catch (_: Exception) {
        }
        activeSession = null
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
        try {
            streamExecutor.shutdownNow()
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

    /**
     * Cut the FIRST complete sentence off the buffer and return it:
     * everything up to and including the first run of sentence
     * terminators (with the same decimal-point guard as the overflow
     * cut, so "3.5" never splits), plus any closing quotes/brackets
     * that belong to that sentence. Null while the buffer holds only
     * a partial sentence. This is what lets speech start at the
     * first sentence instead of at turn end.
     */
    private fun drainSentenceChunk(): String? {
        synchronized(buffer) {
            val s = buffer.toString()
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
                    while (j < s.length && (s[j] == '"' || s[j] == '\'' ||
                            s[j] == ')' || s[j] == '”' || s[j] == '’')
                    ) j++
                    val chunk = s.substring(0, j).trim()
                    buffer.setLength(0)
                    buffer.append(s.substring(j))
                    return chunk.ifEmpty { null }
                }
                i++
            }
            return null
        }
    }

    // ------------------------------------------------------------------
    // Streaming machinery (v33)
    // ------------------------------------------------------------------

    /**
     * Retire a stream session exactly once (stream worker thread
     * only): let any received-but-unplayed audio finish sounding
     * first — the streamed voice and a REST fallback must never
     * overlap — then hand whatever was never heard to the REST
     * queue and settle the session's isSpeaking count.
     */
    private fun closeOutSession(s: ElStreamSession) {
        try {
            s.awaitDrain(30_000)
        } catch (_: Exception) {
        }
        val unspoken = try {
            s.unspokenText()
        } catch (_: Exception) {
            ""
        }
        if (unspoken.isNotBlank() && s.gen == generation && !released) {
            streamFailedGen = s.gen
            dispatch(unspoken)
        }
        if (activeSession === s) activeSession = null
        segmentFinished()
    }

    /**
     * The stream worker: sole owner of the WebSocket send side, so
     * chunks reach ElevenLabs strictly in reply order. Per turn it
     * lazily opens an [ElStreamSession] on the first real chunk,
     * transliterates and sends every chunk, and on the turn's Final
     * item sends EOS and rides the session to the end of playback
     * before touching the next turn — two turns can never overlap.
     *
     * Failure rule: the moment a session is seen failed, its
     * unspoken tail is re-dispatched through the REST queue (see
     * [closeOutSession]) and [streamFailedGen] reroutes the rest of
     * that turn to REST — speech is never lost, only delayed.
     *
     * isSpeaking accounting: exactly one pendingSegments increment
     * per session (here, at creation) and exactly one decrement
     * (in [closeOutSession]) — REST fallback segments count
     * themselves separately, so the mic gate stays truthful across
     * the handoff.
     */
    private fun runStreamWorker() {
        var session: ElStreamSession? = null
        var sessionGen = -1
        while (!released) {
            val item = try {
                streamQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (item.gen != generation) {
                // Went stale while queued (barge-in / stop).
                continue
            }
            try {
                when (item) {
                    is StreamItem.Chunk -> {
                        if (session != null && sessionGen != item.gen) {
                            closeOutSession(session!!)
                            session = null
                            sessionGen = -1
                        }
                        var s = session
                        if (s != null && s.failed) {
                            closeOutSession(s)
                            session = null
                            sessionGen = -1
                            s = null
                        }
                        val clean = cleanForSpeech(item.text)
                        if (clean.length >= 2) {
                            if (streamFailedGen == item.gen) {
                                dispatch(item.text)
                            } else {
                                if (s == null) {
                                    s = ElStreamSession(item.gen)
                                    session = s
                                    sessionGen = item.gen
                                    activeSession = s
                                    pendingSegments.incrementAndGet()
                                }
                                s.sendChunk(
                                    clean,
                                    transliterateIfRomanUrdu(clean),
                                    final = false
                                )
                            }
                        }
                    }
                    is StreamItem.Final -> {
                        if (session != null && sessionGen != item.gen) {
                            closeOutSession(session!!)
                            session = null
                            sessionGen = -1
                        }
                        val clean = cleanForSpeech(item.text)
                        var s = session
                        if (s == null && clean.length >= 2 &&
                            streamFailedGen != item.gen
                        ) {
                            s = ElStreamSession(item.gen)
                            session = s
                            sessionGen = item.gen
                            activeSession = s
                            pendingSegments.incrementAndGet()
                        }
                        if (s != null) {
                            if (clean.length >= 2) {
                                if (s.failed) {
                                    s.noteUnsent(clean)
                                } else {
                                    s.sendChunk(
                                        clean,
                                        transliterateIfRomanUrdu(clean),
                                        final = true
                                    )
                                }
                            }
                            s.finishAndAwait()
                            session = null
                            sessionGen = -1
                            closeOutSession(s)
                        } else if (clean.length >= 2) {
                            // The stream failed and closed out before
                            // the final piece arrived — REST speaks it.
                            dispatch(item.text)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "stream item dropped: ${e.javaClass.simpleName}: ${e.message}")
                // A session that blew up mid-item: kill it and close
                // it out so its text fails over instead of vanishing,
                // and never let one bad item wedge the worker.
                val s = session
                if (s != null) {
                    try {
                        s.kill()
                    } catch (_: Exception) {
                    }
                    session = null
                    sessionGen = -1
                    closeOutSession(s)
                }
            }
        }
    }

    /**
     * One turn's ElevenLabs stream-input WebSocket plus its
     * AudioTrack playback. Created, fed and retired ONLY by the
     * stream worker thread; the WebSocket listener (OkHttp threads)
     * and the player thread touch only its thread-safe queues and
     * counters.
     *
     * Protocol (ElevenLabs docs + official SDK, verified for v33):
     * connect to wss://api.elevenlabs.io/v1/text-to-speech/{voiceId}
     * /stream-input?model_id=eleven_multilingual_v2&output_format=
     * pcm_24000, send a BOS message {"text":" ", voice_settings,
     * generation_config, xi_api_key}, then one {"text": chunk,
     * "try_trigger_generation": true} per chunk (the last with
     * "flush": true), then EOS {"text": ""}. Replies are JSON:
     * {"audio": "<base64 pcm>", "isFinal": bool}.
     *
     * Spoken-ness bookkeeping for the REST fallback: every chunk's
     * CLEANED source text is remembered with the moment it was
     * sent, and every audio message notes its arrival time. A chunk
     * counts as spoken once any audio arrived after it was sent;
     * [unspokenText] replays everything from the first chunk that
     * never saw audio, plus chunks never sent at all. (Streaming
     * has no per-chunk acknowledgement, so at a failure boundary a
     * few words can in the worst case be spoken twice — losing the
     * rest of the reply would be worse.)
     */
    private inner class ElStreamSession(val gen: Int) {

        private val lock = Object()
        private var ws: WebSocket? = null
        private val openLatch = CountDownLatch(1)
        @Volatile var failed = false
            private set
        @Volatile private var killed = false
        @Volatile private var bosSent = false
        @Volatile private var inputClosed = false

        // Fallback bookkeeping (lists guarded by lock).
        private val sentChunks = ArrayList<Pair<String, Long>>()
        private val unsentChunks = ArrayList<String>()
        @Volatile private var lastAudioAt = 0L
        @Volatile private var finalReceived = false
        /** Audio arrived but the track could not play it: nothing
         *  counts as spoken, the fallback replays the whole turn. */
        @Volatile private var playbackBroken = false

        // Playback.
        private val pcmQueue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var playerStarted = false
        private var track: AudioTrack? = null
        @Volatile private var framesWritten = 0L
        private val doneLatch = CountDownLatch(1)
        @Volatile private var done = false

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (!msg.isNull("error")) {
                        markFailed()
                        return
                    }
                    val audio = msg.optString("audio", "")
                    if (audio.isNotEmpty()) {
                        val pcm = Base64.decode(audio, Base64.DEFAULT)
                        if (pcm.isNotEmpty()) {
                            lastAudioAt = System.currentTimeMillis()
                            startPlayerIfNeeded()
                            // PCM16 chunks are byte-aligned; drop a
                            // stray odd byte rather than misalign.
                            pcmQueue.offer(
                                if (pcm.size % 2 == 0) pcm
                                else pcm.copyOf(pcm.size - 1)
                            )
                        }
                    }
                    if (msg.optBoolean("isFinal", false)) {
                        finalReceived = true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "stream message dropped: ${e.javaClass.simpleName}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                openLatch.countDown()
                // A close is clean only after the final audio
                // message; anything earlier loses the turn's tail
                // unless the fallback replays it.
                if (!finalReceived && !killed) markFailed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                openLatch.countDown()
                if (!killed) markFailed()
            }
        }

        /**
         * Send one already-cleaned chunk; [speakText] is its
         * transliterated form (what ElevenLabs actually receives).
         * Never throws: any failure marks the session failed and
         * parks the chunk in the unsent pool for the REST fallback.
         */
        fun sendChunk(cleanText: String, speakText: String, final: Boolean) {
            if (failed || killed) {
                noteUnsent(cleanText)
                return
            }
            if (!ensureOpen()) {
                markFailed()
                noteUnsent(cleanText)
                return
            }
            val payload = try {
                JSONObject()
                    .put("text", if (final) speakText else speakText + " ")
                    .put("flush", final)
                    .put("try_trigger_generation", true)
                    .toString()
            } catch (_: Exception) {
                markFailed()
                noteUnsent(cleanText)
                return
            }
            val sent = try {
                ws?.send(payload) == true
            } catch (_: Exception) {
                false
            }
            if (sent) {
                synchronized(lock) {
                    sentChunks.add(cleanText to System.currentTimeMillis())
                }
            } else {
                markFailed()
                noteUnsent(cleanText)
            }
        }

        /** Remember a chunk that never reached the socket. */
        fun noteUnsent(cleanText: String) {
            synchronized(lock) { unsentChunks.add(cleanText) }
        }

        /**
         * Turn is fully fed: send EOS, then wait until playback has
         * actually drained (or the wait is abandoned by a kill /
         * generation bump). The socket is closed on the way out.
         */
        fun finishAndAwait() {
            inputClosed = true
            if (!failed && !killed && bosSent) {
                val ok = try {
                    ws?.send(JSONObject().put("text", "").toString()) == true
                } catch (_: Exception) {
                    false
                }
                if (!ok) markFailed()
            }
            startPlayerIfNeeded()
            awaitDrain(600_000)
            closeSocket()
        }

        /**
         * Wait until the player thread reports playback complete.
         * Sliced, so a kill or generation bump (stopAll) abandons
         * the wait promptly; [timeoutMs] is only a paranoia cap.
         */
        fun awaitDrain(timeoutMs: Long) {
            if (!playerStarted) return
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!done) {
                if (killed || gen != generation || released) return
                if (System.currentTimeMillis() > deadline) return
                try {
                    if (doneLatch.await(150, TimeUnit.MILLISECONDS)) return
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        /**
         * The part of this turn that was never heard, in order:
         * chunks sent but followed by no audio at all, plus chunks
         * that never got sent. Blank when everything sent saw audio.
         */
        fun unspokenText(): String {
            val parts = ArrayList<String>()
            synchronized(lock) {
                if (playbackBroken) {
                    for ((text, _) in sentChunks) parts.add(text)
                } else {
                    val l = lastAudioAt
                    for ((text, sentAt) in sentChunks) {
                        if (l == 0L || sentAt > l) parts.add(text)
                    }
                }
                parts.addAll(unsentChunks)
            }
            return parts.joinToString(" ").trim()
        }

        /**
         * stopAll(): silence NOW — socket cancelled, track torn
         * down, and every wait loop observes [killed] and bails.
         */
        fun kill() {
            killed = true
            failed = true
            openLatch.countDown()
            try {
                ws?.cancel()
            } catch (_: Exception) {
            }
            closeSocket()
            releaseTrack()
            done = true
            doneLatch.countDown()
        }

        private fun markFailed() {
            failed = true
            openLatch.countDown()
        }

        /**
         * Open the socket and send the BOS config message. False on
         * any problem (blank key/voice, connect timeout, refusal) —
         * the caller then fails the session over to REST.
         */
        private fun ensureOpen(): Boolean {
            if (bosSent) return !failed && !killed
            val key = Prefs.elevenLabsKey
            val voiceId = Prefs.elevenLabsVoiceId
            if (key.isBlank() || voiceId.isBlank()) return false
            val url = WS_BASE + voiceId +
                "?model_id=" + MODEL_ID +
                "&output_format=pcm_24000" +
                "&inactivity_timeout=120"
            // The key travels as the upgrade header AND in the BOS
            // message — the docs bless both routes; belt and braces.
            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", key)
                .build()
            val socket = try {
                wsHttp.newWebSocket(request, listener)
            } catch (_: Exception) {
                return false
            }
            synchronized(lock) { ws = socket }
            // Wait for onOpen (or an async failure): sliced, so a
            // kill or generation bump breaks us out promptly, with a
            // hard 10 s cap so a hung connect can never wedge the
            // stream worker.
            val deadline = System.currentTimeMillis() + 10_000
            while (!killed && !failed && gen == generation && !released) {
                if (System.currentTimeMillis() > deadline) {
                    markFailed()
                    break
                }
                try {
                    if (openLatch.await(100, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (killed || failed || gen != generation || released) {
                try {
                    socket.cancel()
                } catch (_: Exception) {
                }
                return false
            }
            // BOS: the initial config message (docs + official SDK
            // shape): a single-space text, the voice settings this
            // speaker has always used, the API key, and a chunk
            // schedule whose first step (50 chars) keeps first-audio
            // latency low.
            val bos = try {
                JSONObject()
                    .put("text", " ")
                    .put("xi_api_key", key)
                    .put(
                        "voice_settings",
                        JSONObject()
                            .put("stability", 0.35)
                            .put("similarity_boost", 0.80)
                            .put("style", 0.45)
                            .put("use_speaker_boost", true)
                    )
                    .put(
                        "generation_config",
                        JSONObject().put(
                            "chunk_length_schedule",
                            JSONArray().put(50).put(120).put(500)
                        )
                    )
                    .put("try_trigger_generation", true)
                    .toString()
            } catch (_: Exception) {
                return false
            }
            val ok = try {
                socket.send(bos)
            } catch (_: Exception) {
                false
            }
            if (!ok) return false
            bosSent = true
            return true
        }

        private fun closeSocket() {
            val s = synchronized(lock) {
                val cur = ws
                ws = null
                cur
            }
            try {
                s?.close(1000, null)
            } catch (_: Exception) {
            }
        }

        // ----------------------------------------------------------
        // PCM playback: a per-session AudioTrack fed from a queue,
        // mirroring how AudioEngine plays Gemini's 24 kHz PCM.
        // ----------------------------------------------------------

        private fun startPlayerIfNeeded() {
            synchronized(lock) {
                if (playerStarted) return
                playerStarted = true
            }
            Thread({
                try {
                    while (true) {
                        if (killed) break
                        val data = try {
                            pcmQueue.poll(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (data != null) {
                            val t = ensureTrack() ?: break
                            var offset = 0
                            while (offset < data.size) {
                                if (killed) break
                                val w = try {
                                    t.write(
                                        data, offset, data.size - offset,
                                        AudioTrack.WRITE_BLOCKING
                                    )
                                } catch (_: Exception) {
                                    -1
                                }
                                if (w <= 0) {
                                    playbackBroken = true
                                    break
                                }
                                offset += w
                                framesWritten += w / 2
                            }
                            if (playbackBroken) break
                        } else {
                            // Nothing queued: finished when the input
                            // is closed (EOS sent) and every written
                            // frame has actually sounded — or, after a
                            // failure, when the socket can produce no
                            // more audio and the buffer has drained.
                            if (inputClosed && headCaughtUp()) break
                            if (failed && headCaughtUp()) break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    releaseTrack()
                    done = true
                    doneLatch.countDown()
                }
            }, "myra-elevenlabs-player").apply { isDaemon = true }.start()
        }

        private fun ensureTrack(): AudioTrack? {
            track?.let { return it }
            return try {
                val minBuf = AudioTrack.getMinBufferSize(
                    24000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(24000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                t.play()
                t
            } catch (e: Exception) {
                Log.d(TAG, "AudioTrack failed: ${e.javaClass.simpleName}: ${e.message}")
                playbackBroken = true
                markFailed()
                null
            }
        }

        /** True once every frame handed to the track has sounded. */
        private fun headCaughtUp(): Boolean {
            val t = track ?: return true
            return try {
                t.playbackHeadPosition.toLong() >= framesWritten
            } catch (_: Exception) {
                true
            }
        }

        private fun releaseTrack() {
            val t = synchronized(lock) {
                val cur = track
                track = null
                cur
            }
            try {
                t?.stop()
            } catch (_: Exception) {
            }
            try {
                t?.release()
            } catch (_: Exception) {
            }
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
