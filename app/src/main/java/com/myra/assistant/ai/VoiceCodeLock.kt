package com.myra.assistant.ai

/**
 * Full-shutdown voice command for MYRA.
 *
 * "myra off ho jao" kills everything and sets a persisted flag so nothing
 * auto-restarts until the user opens the app.
 */
object VoiceCodeLock {

    // Normalized shutdown trigger phrases (matched with contains).
    private val SHUTDOWN_PHRASES = listOf(
        "myra off ho jao",
        "off ho jao",
        "band ho jao",
        "myra band ho jao",
        "turn off"
    )

    /** Lowercase, trimmed, single-spaced — tolerant speech matching. */
    fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+"), " ")

    /** True when the spoken text is a shutdown command (top-priority intent). */
    fun isShutdownCommand(speech: String): Boolean {
        val n = normalize(speech)
        return SHUTDOWN_PHRASES.any { n.contains(it) }
    }
}
