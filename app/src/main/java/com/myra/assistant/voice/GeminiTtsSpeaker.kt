package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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
import java.io.File
import java.util.concurrent.CountDownLatch
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
 * The TTS model returns raw 24 kHz 16-bit mono PCM (audio/L16); a
 * standard 44-byte WAV header is prepended and the clip plays through
 * the same MediaPlayer temp-file path as the other speakers.
 *
 * Robustness rules (a TTS failure must NEVER break the session):
 *  - every network/playback error just drops that one segment, silently
 *    (log only — there is no user-visible "voice failed" message);
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
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var playLatch: CountDownLatch? = null

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
        queue.offer(Segment(clean, generation))
    }

    private fun playSegment(seg: Segment) {
        var ok = false
        var file: File? = null
        try {
            val wav = synthesize(seg.text)
            if (wav != null && seg.gen == generation && !released) {
                file = File.createTempFile("myra_gtts_", ".wav", appCtx.cacheDir)
                file.writeBytes(wav)
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

    /**
     * One Gemini TTS synthesis: POST generateContent for the TTS model
     * with the style instruction + segment as the text, the app's Gemini
     * key in the X-goog-api-key header (same style as the Settings API
     * test). Returns WAV bytes (PCM + header), null on ANY failure —
     * silently: the segment is simply dropped.
     */
    private fun synthesize(text: String): ByteArray? {
        val key = Prefs.apiKey
        if (key.isBlank()) {
            Log.d(TAG, "no Gemini key set — segment dropped")
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
                    return null
                }
                val json = JSONObject(resp.body?.string() ?: return null)
                val parts = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return null
                var pcm: ByteArray? = null
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)
                        ?.optJSONObject("inlineData")
                        ?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        pcm = Base64.decode(data, Base64.DEFAULT)
                        break
                    }
                }
                if (pcm == null || pcm.isEmpty()) {
                    Log.d(TAG, "Gemini TTS: no audio part in response")
                    null
                } else {
                    wavHeader(pcm.size) + pcm
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gemini TTS error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Standard 44-byte WAV (RIFF) header for raw PCM16 mono at 24 kHz. */
    private fun wavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) header[off + i] = s[i].code.toByte()
        }

        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataSize)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)                 // fmt chunk size
        putShort(20, 1)                // audio format: PCM
        putShort(22, 1)                // channels: mono
        putInt(24, SAMPLE_RATE)        // 24000 Hz
        putInt(28, SAMPLE_RATE * 2)    // byte rate: 24000 * 16bit * 1ch / 8
        putShort(32, 2)                // block align: 1ch * 16bit / 8
        putShort(34, 16)               // bits per sample
        putStr(36, "data")
        putInt(40, dataSize)
        return header
    }

    /** Play one WAV file to completion. False when stopped or on error. */
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
