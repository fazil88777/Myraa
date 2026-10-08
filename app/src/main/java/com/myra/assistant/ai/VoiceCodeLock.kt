package com.myra.assistant.ai

/**
 * Voice code lock + full-shutdown state for MYRA.
 *
 * Feature 1 — code lock: every voice session starts locked. MYRA says
 * "Code batao" first; only the code phrase unlocks the session.
 * Feature 2 — full shutdown: "myra off ho jao" kills everything and sets a
 * persisted flag so nothing auto-restarts until the user opens the app.
 */
object VoiceCodeLock {

    /** The spoken code phrase — change in ONE place. */
    const val CODE_PHRASE = "your viral"

    /** Wrong-code attempts allowed per session before it ends. */
    const val MAX_WRONG_ATTEMPTS = 3

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

    /** True when the spoken text is the code phrase (tolerant variants). */
    fun isCodeMatch(speech: String): Boolean {
        val n = normalize(speech)
        return n == CODE_PHRASE ||
                n.contains("your viral") ||
                n.contains("youre viral") ||
                n.contains("you are viral")
    }

    /** True when the spoken text is a shutdown command (top-priority intent). */
    fun isShutdownCommand(speech: String): Boolean {
        val n = normalize(speech)
        return SHUTDOWN_PHRASES.any { n.contains(it) }
    }

    /**
     * System-prompt block: the code-lock protocol the model must follow.
     * Appended after the trading-class block; it overrides the normal
     * session-start greeting — "Code batao" comes first, always.
     */
    fun lockPromptBlock(): String =
        "CODE LOCK (greeting se pehle, sab se ahem): har voice session ke shuru me " +
                "tumhara PEHLA kaam code maangna hai — warm greeting ki jagah sab se pehle " +
                "'Code batao' kaho. Jab tak main (client) 'Code theek hai' na kahun, user ki " +
                "kisi bhi baat ka jawab mat do aur koi tool call mat karo — sirf code ka " +
                "intezar karo. Ghalat code par main bataunga ke kya kehna hai. Code verify " +
                "hone ke baad normal kaam karo. "
}
