package com.myra.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared free Google Translate TTS client — the no-key, no-quota
 * neural-voice safety net shared by both transcript speakers:
 *
 *  - GeminiTtsSpeaker routes a whole session here when the Gemini TTS
 *    model answers HTTP 429 (free-tier quota on the preview model);
 *  - EdgeTtsSpeaker uses it as layer 2 when the Edge WebSocket is
 *    unreachable on the phone's network.
 *
 * It speaks through the clients5.google.com translate_tts endpoint
 * (client=dict-chrome-ex): unlike translate.google.com, this host does
 * not block Android requests. The reply text is split into <=150-char
 * chunks at word boundaries (the endpoint's per-request limit) and the
 * MP3 chunks are fetched and played back-to-back, strictly in order,
 * on the CALLER's pipeline thread — every method blocks and simply
 * returns true/false; every failure is soft (log only) and never
 * surfaces a user-visible error.
 */
class GoogleTtsClient(context: Context) {

    companion object {
        private const val TAG = "GoogleTts"
        private const val MAX_CHUNK = 150
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    }

    private val appCtx: Context = context.applicationContext

    // Short timeouts on purpose: this client is a fallback — failing
    // FAST is what keeps the conversation moving instead of hanging.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var playLatch: CountDownLatch? = null

    /**
     * Speak one standalone line (e.g. a greeting). Blocking; true when
     * the line was fully spoken.
     */
    fun speakOnce(text: String, lang: String): Boolean =
        speakSegment(text, lang) { true }

    /**
     * Speak one reply segment: chunk it, then fetch + play each chunk
     * in order. Blocking on the caller's pipeline thread. [shouldContinue]
     * is polled between chunks and during playback so a barge-in
     * (generation bump in the owning speaker) aborts promptly. True only
     * when the whole segment was spoken.
     */
    fun speakSegment(text: String, lang: String, shouldContinue: () -> Boolean): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        return try {
            for (chunk in splitChunks(clean)) {
                if (!shouldContinue()) return false
                val mp3 = fetch(chunk, lang) ?: return false
                if (!shouldContinue()) return false
                if (!playMp3(mp3, shouldContinue)) return false
            }
            true
        } catch (e: Exception) {
            Log.d(TAG, "speak failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Halt an in-flight playback NOW (barge-in / session stop). */
    fun stop() {
        try {
            currentPlayer?.stop()
        } catch (_: Exception) {
        }
        playLatch?.countDown()
    }

    // ------------------------------------------------------------------
    // Fetch + playback
    // ------------------------------------------------------------------

    /** One chunk's MP3 from the clients5 endpoint; null on ANY failure. */
    private fun fetch(text: String, lang: String): ByteArray? {
        return try {
            val url = "https://clients5.google.com/translate_tts?client=dict-chrome-ex" +
                "&tl=" + URLEncoder.encode(lang, "UTF-8") +
                "&q=" + URLEncoder.encode(text, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "Google TTS failed: HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Google TTS error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Play one MP3 chunk to completion. False when aborted or on error. */
    private fun playMp3(bytes: ByteArray, shouldContinue: () -> Boolean): Boolean {
        var file: File? = null
        val latch = CountDownLatch(1)
        playLatch = latch
        // Written by the completion listener before countDown(), read
        // after the latch opens — the latch provides the memory barrier.
        var completed = false
        val mp = MediaPlayer()
        currentPlayer = mp
        try {
            file = File.createTempFile("myra_gtts_", ".mp3", appCtx.cacheDir)
            file.writeBytes(bytes)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                try {
                    if (shouldContinue()) p.start() else latch.countDown()
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
            while (true) {
                if (latch.await(120, TimeUnit.MILLISECONDS)) break
                if (!shouldContinue()) break
            }
            return completed
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
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Split into <=150-char chunks at word boundaries (endpoint limit). */
    private fun splitChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > MAX_CHUNK) {
            var cut = rest.lastIndexOf(' ', MAX_CHUNK)
            if (cut < 60) cut = MAX_CHUNK
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out.filter { it.isNotEmpty() }
    }
}
