package com.myra.assistant.voice

/**
 * Common contract of the transcript-mode speakers (GeminiTtsSpeaker,
 * EdgeTtsSpeaker): in a transcript session the Gemini Live socket runs
 * in AUDIO mode with output transcription, Gemini's own audio is
 * discarded unheard, and one of these speakers voices the reply's
 * transcript text — streamed deltas are cut into sentences and spoken
 * strictly in order on a single pipeline thread.
 *
 * Every implementation fails SOFT: a synthesis/playback failure just
 * drops that segment (log only) — it never breaks the session and never
 * surfaces a user-visible "voice failed" message.
 */
interface VoiceSpeaker {

    /** Feed one streamed text delta of the model's current reply. */
    fun enqueueDelta(delta: String)

    /** The model's turn ended: speak whatever partial sentence remains. */
    fun flushTurn()

    /**
     * Speak one standalone line (the session greeting). Exactly one of
     * [onPlayed] / [onFailed] fires, on the pipeline thread.
     */
    fun speakOnce(text: String, onPlayed: () -> Unit, onFailed: () -> Unit)

    /** Barge-in / new turn / session stop: silence NOW, drop everything pending. */
    fun stopAll()

    /** Session is over for good: stop everything and kill the pipeline thread. */
    fun release()
}
