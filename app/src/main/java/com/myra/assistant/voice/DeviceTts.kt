package com.myra.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's own TextToSpeech engine — ONE shared instance for the
 * whole app (the session greeting and the Edge speaker's last-resort
 * fallback both speak through here, never through two engines).
 *
 * Two speaking styles:
 *  - [speakBlocking]: the fail-safe voice (Layer 3 of the voice chain).
 *    Urdu (ur-PK) if the engine has it, else Hindi (hi-IN), else the
 *    engine default; pitch 1.1 / rate 0.9 for a warm female tone. Runs
 *    on the caller's thread (the TTS pipeline thread) and returns only
 *    when the utterance finished, so segment order is preserved when
 *    MP3 segments and device-TTS segments interleave.
 *  - [speakWelcome]: the legacy greeting style ("Welcome to Myra
 *    Assistant" — US English, a female voice when offered, default
 *    pitch/rate), fire-and-forget with a "queued or given up" callback.
 *
 * Everything fails soft: any error just returns/calls back, never throws.
 */
object DeviceTts {

    private const val TAG = "DeviceTts"

    // initState: 0 = not created, 1 = creating, 2 = ready, 3 = failed.
    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initState = 0
    private val waiters = ArrayList<(Boolean) -> Unit>()

    // ------------------------------------------------------------------
    // Engine lifecycle (single shared instance)
    // ------------------------------------------------------------------

    /** Async: [cb] fires exactly once with engine readiness. */
    private fun withEngine(context: Context, cb: (Boolean) -> Unit) {
        var startCreate = false
        var immediate: Boolean? = null
        synchronized(waiters) {
            when (initState) {
                2 -> immediate = engine != null
                3 -> immediate = false
                else -> {
                    waiters.add(cb)
                    if (initState == 0) {
                        initState = 1
                        startCreate = true
                    }
                }
            }
        }
        if (immediate != null) {
            cb(immediate!!)
            return
        }
        if (!startCreate) return
        val create = Runnable {
            try {
                val t = TextToSpeech(context.applicationContext) { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    val pending: List<(Boolean) -> Unit>
                    synchronized(waiters) {
                        initState = if (ok) 2 else 3
                        pending = ArrayList(waiters)
                        waiters.clear()
                    }
                    for (w in pending) {
                        try {
                            w(ok)
                        } catch (_: Exception) {
                        }
                    }
                }
                engine = t
            } catch (e: Exception) {
                Log.d(TAG, "engine create failed: ${e.javaClass.simpleName}: ${e.message}")
                val pending: List<(Boolean) -> Unit>
                synchronized(waiters) {
                    initState = 3
                    pending = ArrayList(waiters)
                    waiters.clear()
                }
                for (w in pending) {
                    try {
                        w(false)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) create.run()
        else Handler(Looper.getMainLooper()).post(create)
    }

    /** Blocking engine wait for pipeline threads. */
    private fun awaitEngine(context: Context, timeoutMs: Long): Boolean {
        if (initState == 2 && engine != null) return true
        if (initState == 3) return false
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        withEngine(context) { good ->
            ok.set(good)
            latch.countDown()
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    /** Stop whatever the device engine is currently saying (barge-in). */
    fun stop() {
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Layer 3: fail-safe blocking speech (Urdu/Hindi, pitch 1.1, rate 0.9)
    // ------------------------------------------------------------------

    /**
     * Speak [text] on the device engine and BLOCK until it finishes (or
     * [cancelled] turns true, or a generous length-based cap hits).
     * True only when the utterance actually completed.
     */
    fun speakBlocking(
        context: Context,
        text: String,
        cancelled: () -> Boolean = { false }
    ): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if (!awaitEngine(context, 5000)) return false
        val t = engine ?: return false
        return try {
            // Locale by availability: Urdu first, then Hindi, else keep
            // whatever default the engine ships with.
            if (t.setLanguage(Locale("ur", "PK")) < 0) {
                if (t.setLanguage(Locale("hi", "IN")) < 0) {
                    Log.d(TAG, "no ur/hi device voice — engine default bol rahi hai")
                }
            }
            t.setPitch(1.1f)
            t.setSpeechRate(0.9f)
            val done = CountDownLatch(1)
            val success = AtomicBoolean(false)
            val utteranceId = "myra_device_" + System.nanoTime()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                }

                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        success.set(true)
                        done.countDown()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) done.countDown()
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) done.countDown()
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId) done.countDown()
                }
            })
            if (t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId) !=
                TextToSpeech.SUCCESS
            ) {
                return false
            }
            // Cap: ~120ms per char at the 0.9 rate plus slack (min 4s,
            // max 30s) — a hung engine can never hold the segment queue.
            val capMs = (4000L + clean.length * 120L).coerceAtMost(30000L)
            val deadline = System.currentTimeMillis() + capMs
            while (System.currentTimeMillis() < deadline) {
                if (done.await(100, TimeUnit.MILLISECONDS)) break
                if (cancelled()) {
                    stop()
                    return false
                }
            }
            success.get()
        } catch (e: Exception) {
            Log.d(TAG, "speakBlocking failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Greeting style: US English, female voice when offered, defaults
    // ------------------------------------------------------------------

    /**
     * Speak [text] in the legacy greeting voice. [onQueued] fires exactly
     * once — after the line has been queued, or immediately when the
     * engine is unavailable (the caller proceeds either way).
     */
    fun speakWelcome(context: Context, text: String, onQueued: () -> Unit) {
        withEngine(context) { ok ->
            try {
                if (ok) {
                    val t = engine
                    if (t != null) {
                        t.language = Locale.US
                        t.setPitch(1.0f)
                        t.setSpeechRate(1.0f)
                        pickFemaleVoice(t)
                        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "myra_welcome")
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    onQueued()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Prefers a female English TTS voice when the phone offers one. */
    private fun pickFemaleVoice(t: TextToSpeech) {
        try {
            val v = t.voices
                ?.firstOrNull { it.locale.language == "en" && it.name.contains("female", true) }
                ?: t.voices?.firstOrNull { it.locale.language == "en" }
            v?.let { t.voice = it }
        } catch (_: Exception) {
        }
    }
}
