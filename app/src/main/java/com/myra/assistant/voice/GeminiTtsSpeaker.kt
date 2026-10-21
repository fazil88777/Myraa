package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.myra.assistant.util.MoodManager
import com.myra.assistant.util.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gemini TTS speaker — MYRA's default realistic human voice.
 *
 * Used when the Voice Engine is GEMINI_TTS: the Gemini Live session
 * runs in AUDIO mode with outputAudioTranscription, Gemini's own audio
 * is discarded, and every model reply is fed here as text: the streamed
 * text is split into sentences, each sentence is synthesized by the
 * Gemini TTS model (gemini-2.5-flash-preview-tts, voice "Aoede") with
 * the app's own Gemini API key — no third-party TTS account, no extra
 * key — and the clips are played back strictly in order on a single
 * pipeline thread, so the first sentence starts speaking while the rest
 * of the reply is still being generated.
 *
 * Every request carries a style instruction: a soft, warm, breathy,
 * deeply emotional Hindi/Urdu female voice like a real girl on a phone
 * call, with a mood line from MoodManager appended (sad / crying /
 * missing / hurt -> slow and heavy-hearted; happy / romantic / playful
 * -> bright and loving).
 *
 * The TTS model returns raw 16-bit mono PCM (audio/L16, usually
 * 24 kHz); the PCM is streamed straight into an AudioTrack — no WAV
 * header, no temp file, no MediaPlayer — at the sample rate parsed
 * from the response's inlineData mimeType.
 *
 * Robustness rules (a TTS failure must NEVER break the session):
 *  - every network/playback error just drops that one segment
 *    (log only — there is no user-visible "voice failed" message);
 *  - ONE exception, a temporary debug surface at Fazil's request:
 *    MainViewModel toasts "Playing: Gemini Aoede" once per session
 *    when the first segment actually starts playing, and
 *    "Audio Error: <reason>" once per session on the first failure —
 *    so a silent voice is never again undiagnosable;
 *  - QUOTA BYPASS: the free tier's quota on this preview REST model is
 *    tiny (per-sentence calls hit HTTP 429 within a few replies). The
 *    first 429 flips a session-level flag: that segment and ALL later
 *    segments of the session are spoken by the free Google voice
 *    (GoogleTtsClient, tl=hi) instead of the REST model, and the debug
 *    surface announces the shift once. The flag lives on the speaker
 *    instance, so the next session tries the Gemini TTS model again;
 *  - stopAll() (barge-in, session stop, shutdown) clears the queue and
 *    halts playback immediately via a generation counter — stale work
 *    from an older moment can never play.
 */
class GeminiTtsSpeaker(context: Context) : VoiceSpeaker {

    companion object {
        private const val TAG = "GeminiTts"
        private const val TTS_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-2.5-flash-preview-tts:generateContent"
        private const val VOICE_NAME = "Aoede"
        private const val SAMPLE_RATE = 24000

        /** Style instruction prepended to every segment's text. */
        private const val STYLE_BASE =
            "Speak in a soft, warm, breathy, and deeply emotional " +
                "Hindi/Urdu female voice like a real girl on a phone call. " +
                "Include natural human pauses, sighs, giggles, and " +
                "emotional inflection."
    }

    private class Segment(
        val text: String,
        val gen: Int,
        val onPlayed: (() -> Unit)? = null,
        val onFailed: (() -> Unit)? = null
    )

    private val appCtx: Context = context.applicationContext

    // Own client (NOT the Live websocket client), sized for one REST
    // synthesis: ~10s per-segment budget end to end.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val queue = LinkedBlockingQueue<Segment>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "myra-gemini-tts").apply { isDaemon = true }
    }

    /** Bumped by stopAll(): anything queued/playing from an older gen is dropped. */
    @Volatile private var generation = 0
    @Volatile private var released = false
    @Volatile private var currentTrack: AudioTrack? = null

    /**
     * Debug surface wired by MainViewModel (temporary, Fazil's
     * request): receives "Playing: Gemini Aoede" when the first
     * segment of the session actually starts playing, and
     * "Audio Error: <reason>" on the first failure — each at most
     * once per speaker instance (= once per session).
     */
    @Volatile var onDebugEvent: ((String) -> Unit)? = null
    @Volatile private var playingNotified = false
    @Volatile private var errorNotified = false

    /** Free-voice bypass target once the Gemini TTS quota dies (429). */
    private val googleTts = GoogleTtsClient(appCtx)

    /**
     * Session-level quota flag: set by the first HTTP 429 from the TTS
     * model; from then on every segment of THIS session is spoken by
     * the free Google voice instead of the REST model.
     */
    @Volatile private var quotaExhausted = false
    @Volatile private var quotaNotified = false

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
    override fun enqueueDelta(delta: String) {
        if (released || delta.isEmpty()) return
        synchronized(buffer) { buffer.append(delta) }
        for (s in extractSentences(flush = false)) dispatch(s)
    }

    /** The model's turn ended: speak whatever partial sentence remains. */
    override fun flushTurn() {
        if (released) return
        for (s in extractSentences(flush = true)) dispatch(s)
    }

    /**
     * Speak one standalone line (the session greeting). Exactly one of
     * [onPlayed] / [onFailed] fires, on the pipeline thread.
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
        queue.offer(Segment(clean, generation, onPlayed, onFailed))
    }

    /** Barge-in / new turn / session stop: silence NOW, drop everything pending. */
    override fun stopAll() {
        generation++
        queue.clear()
        synchronized(buffer) { buffer.setLength(0) }
        // If a segment is currently spoken by the Google bypass voice,
        // halt that playback too.
        try {
            googleTts.stop()
        } catch (_: Exception) {
        }
        // Pause + flush halts the sound immediately; the pipeline
        // thread's playPcm loop sees the generation bump and stops /
        // releases the track itself (releasing from here while a
        // blocking write is in flight is not safe on every device).
        try {
            currentTrack?.pause()
        } catch (_: Exception) {
        }
        try {
            currentTrack?.flush()
        } catch (_: Exception) {
        }
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
        queue.offer(Segment(clean, generation))
    }

    private fun playSegment(seg: Segment) {
        var ok = false
        try {
            if (quotaExhausted) {
                // The Gemini TTS quota already died this session: the
                // free Google voice speaks everything from here on.
                ok = speakViaGoogle(seg)
            } else {
                val res = synthesize(seg.text)
                if (res != null && seg.gen == generation && !released) {
                    ok = playPcm(res.pcm, res.sampleRate, seg.gen)
                } else if (res == null && quotaExhausted &&
                    seg.gen == generation && !released
                ) {
                    // That REST call was the one that hit HTTP 429 —
                    // THIS segment still gets spoken, by the free
                    // Google voice (synthesize() set the flag and
                    // announced the shift).
                    ok = speakViaGoogle(seg)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "segment dropped: ${e.javaClass.simpleName}: ${e.message}")
            notifyErrorOnce("playback: ${e.javaClass.simpleName}")
        }
        if (ok) seg.onPlayed?.let { safeCallback(it) }
        else seg.onFailed?.let { safeCallback(it) }
    }

    /**
     * Speak one segment with the free Google voice (the 429 bypass):
     * tl=hi — Roman Urdu/Hindi reads naturally with the Hindi voice.
     * Runs on this speaker's pipeline thread, in queue order.
     */
    private fun speakViaGoogle(seg: Segment): Boolean {
        if (seg.gen != generation || released) return false
        return googleTts.speakSegment(seg.text, "hi") {
            seg.gen == generation && !released
        }
    }

    // ------------------------------------------------------------------
    // Gemini TTS REST synthesis (generateContent, AUDIO modality)
    // ------------------------------------------------------------------

    /** Mood line appended to the style instruction, read at synthesis time. */
    private fun moodStyleSuffix(): String =
        when (MoodManager.currentMood()) {
            MoodManager.Mood.SAD, MoodManager.Mood.CRYING,
            MoodManager.Mood.MISSING_USER, MoodManager.Mood.HURT ->
                " Speak slowly and softly, with a heavy heart, as if you are about to cry."

            MoodManager.Mood.HAPPY, MoodManager.Mood.ROMANTIC,
            MoodManager.Mood.PLAYFUL ->
                " Speak brightly and lovingly, with a smile in your voice."

            else -> ""
        }

    /** One successful synthesis: raw PCM bytes + their sample rate. */
    private class SynthResult(val pcm: ByteArray, val sampleRate: Int)

    /** First-failure debug toast ("Audio Error: <reason>"), once per session. */
    private fun notifyErrorOnce(reason: String) {
        if (errorNotified) return
        errorNotified = true
        try {
            onDebugEvent?.invoke("Audio Error: $reason")
        } catch (_: Exception) {
        }
    }

    /** Quota-shift announcement (HTTP 429 bypass), once per session. */
    private fun notifyQuotaOnce() {
        if (quotaNotified) return
        quotaNotified = true
        try {
            onDebugEvent?.invoke("Quota khatam (429) — free awaaz par shift ho gayi hai")
        } catch (_: Exception) {
        }
    }

    /** First-playing debug toast ("Playing: Gemini Aoede"), once per session. */
    private fun notifyPlayingOnce() {
        if (playingNotified) return
        playingNotified = true
        try {
            onDebugEvent?.invoke("Playing: Gemini Aoede")
        } catch (_: Exception) {
        }
    }

    /**
     * One Gemini TTS synthesis: POST generateContent for the TTS model
     * with the style instruction + segment as the text, the app's Gemini
     * key in the X-goog-api-key header (same style as the Settings API
     * test). Returns the raw PCM + the sample rate declared in the
     * response's inlineData mimeType ("audio/L16;rate=24000"), null on
     * ANY failure — the segment is simply dropped, but the FIRST
     * failure's reason is surfaced once via [onDebugEvent].
     */
    private fun synthesize(text: String): SynthResult? {
        val key = Prefs.apiKey
        if (key.isBlank()) {
            Log.d(TAG, "no Gemini key set — segment dropped")
            notifyErrorOnce("no Gemini key set")
            return null
        }
        return try {
            val styledText = STYLE_BASE + moodStyleSuffix() + " " + text
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", styledText))
                        )
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", VOICE_NAME)
                                )
                            )
                        )
                )
                .toString()
            val request = Request.Builder()
                .url(TTS_URL)
                .addHeader("X-goog-api-key", key)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "Gemini TTS failed: HTTP ${resp.code}")
                    if (resp.code == 429) {
                        // Free-tier quota on the preview TTS model is
                        // exhausted: flip the session to the free Google
                        // voice (the caller routes this very segment
                        // there too) and announce the shift once.
                        quotaExhausted = true
                        notifyQuotaOnce()
                    } else {
                        notifyErrorOnce("HTTP ${resp.code}")
                    }
                    return null
                }
                val json = JSONObject(resp.body?.string() ?: return null)
                val parts = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return null
                var result: SynthResult? = null
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)
                        ?.optJSONObject("inlineData")
                        ?: continue
                    val data = inline.optString("data")
                    if (data.isNullOrEmpty()) continue
                    val pcm = Base64.decode(data, Base64.DEFAULT)
                    if (pcm.isEmpty()) continue
                    // The response declares its own rate ("audio/L16;
                    // rate=24000") — trust it instead of assuming 24 kHz.
                    val mime = inline.optString("mimeType")
                    val rate = Regex("rate=(\\d+)").find(mime)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: SAMPLE_RATE
                    result = SynthResult(pcm, rate)
                    break
                }
                if (result == null) {
                    Log.d(TAG, "Gemini TTS: no audio part in response")
                    notifyErrorOnce("no audio in response")
                }
                result
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gemini TTS error: ${e.javaClass.simpleName}: ${e.message}")
            notifyErrorOnce(e.javaClass.simpleName)
            null
        }
    }

    /**
     * Stream raw PCM straight into an AudioTrack (MODE_STREAM) and wait
     * until it has actually played out. No WAV header, no temp file, no
     * MediaPlayer — the bytes the TTS model returned are exactly what
     * the track consumes. False when stopped (barge-in / session end)
     * or on any error.
     */
    private fun playPcm(pcm: ByteArray, rate: Int, gen: Int): Boolean {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(
                if (minBuf > 0) minBuf else rate,
                minOf(pcm.size, rate * 2)
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.d(TAG, "AudioTrack not initialized")
                notifyErrorOnce("playback: AudioTrack init")
                return false
            }
            currentTrack = track
            track.play()
            notifyPlayingOnce()
            var offset = 0
            while (offset < pcm.size) {
                if (gen != generation || released) return false
                val n = minOf(8192, pcm.size - offset)
                val written = track.write(pcm, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.d(TAG, "AudioTrack write error: $written")
                    notifyErrorOnce("playback: write $written")
                    return false
                }
                offset += written
            }
            // Drain: wait until the playhead has passed every frame we
            // wrote (deadline-capped in case a device's head position
            // never advances — then the elapsed duration is the truth).
            val totalFrames = pcm.size / 2
            val deadline = System.currentTimeMillis() +
                (totalFrames * 1000L / rate) + 1500
            while (track.playbackHeadPosition < totalFrames) {
                if (gen != generation || released) return false
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(40)
            }
            return gen == generation && !released
        } catch (e: Exception) {
            Log.d(TAG, "playback error: ${e.javaClass.simpleName}: ${e.message}")
            notifyErrorOnce("playback: ${e.javaClass.simpleName}")
            return false
        } finally {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            try {
                track?.release()
            } catch (_: Exception) {
            }
            if (currentTrack == track) currentTrack = null
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
