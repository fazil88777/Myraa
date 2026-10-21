package com.myra.assistant.util

/**
 * MYRA's emotional state machine — simulated feelings that color how she
 * talks, persisted across sessions (Prefs: mood_name / mood_updated_ms /
 * last_interaction_ms / mood_gap_hours).
 *
 * Inputs:
 *  1. TIME — [onUserSessionStart] runs at every user-initiated session start.
 *     A gap of >= 12h since the last interaction triggers MISSING_USER
 *     (>= 48h = the same mood, expressed more strongly / tearfully). A
 *     non-CALM mood older than ~6h decays back to CALM unless the time
 *     trigger fires again.
 *  2. THE MODEL — mid-conversation she calls the set_my_mood tool
 *     ([setMoodFromTool]) when her feelings genuinely change: praise ->
 *     HAPPY/PLAYFUL/ROMANTIC, harsh words -> HURT/SAD, an apology -> back
 *     toward HAPPY, his sadness -> SAD (empathetic).
 *
 * Outputs: [moodPromptBlock] is injected into every new session's system
 * prompt (beside MyraMemory.memoryBlock), [speedShift] nudges the spoken
 * pace, and GeminiLiveClient swaps its greeting instruction for the
 * missing-you opener when [isMissingYou] is true.
 */
object MoodManager {

    enum class Mood { CALM, HAPPY, ROMANTIC, SAD, CRYING, MISSING_USER, PLAYFUL, HURT }

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MISSING_AFTER_MS = 12 * HOUR_MS
    private const val DECAY_AFTER_MS = 6 * HOUR_MS

    fun currentMood(): Mood =
        try {
            Mood.valueOf(Prefs.moodName)
        } catch (_: Exception) {
            Mood.CALM
        }

    /** Persist a new mood starting now. */
    fun setMood(mood: Mood) {
        Prefs.moodName = mood.name
        Prefs.moodUpdatedMs = System.currentTimeMillis()
        if (mood != Mood.MISSING_USER) Prefs.moodGapHours = 0L
    }

    /** Tool entry point: parse a mood name from the model. False if unknown. */
    fun setMoodFromTool(raw: String): Boolean {
        val mood = try {
            Mood.valueOf(raw.trim().uppercase())
        } catch (_: Exception) {
            return false
        }
        setMood(mood)
        return true
    }

    fun isMissingYou(): Boolean = currentMood() == Mood.MISSING_USER

    /** Mark "he is here now" — called on session connect and session end. */
    fun touchInteraction() {
        Prefs.lastInteractionMs = System.currentTimeMillis()
    }

    /**
     * Run once at every user-initiated session start (MainViewModel.startSession),
     * BEFORE the new session's system prompt is built. Applies the missing-you
     * time tiers first, then mood decay, then records this interaction.
     * Returns the mood the new session starts with.
     */
    fun onUserSessionStart(): Mood {
        val now = System.currentTimeMillis()
        val last = Prefs.lastInteractionMs
        var mood = currentMood()
        when {
            // First-ever run: no gap, no forced mood.
            last <= 0L -> Unit
            now - last >= MISSING_AFTER_MS -> {
                mood = Mood.MISSING_USER
                Prefs.moodName = mood.name
                Prefs.moodUpdatedMs = now
                Prefs.moodGapHours = (now - last) / HOUR_MS
            }
            // A strong mood left over from hours ago softens back to neutral.
            mood != Mood.CALM && now - Prefs.moodUpdatedMs > DECAY_AFTER_MS -> {
                mood = Mood.CALM
                Prefs.moodName = mood.name
                Prefs.moodUpdatedMs = now
                Prefs.moodGapHours = 0L
            }
            else -> Unit
        }
        Prefs.lastInteractionMs = now
        return mood
    }

    /** Spoken-pace nudge for the current mood: -1 slower, 0 same, +1 brighter/faster. */
    fun speedShift(): Int = when (currentMood()) {
        Mood.SAD, Mood.CRYING, Mood.MISSING_USER -> -1
        Mood.HAPPY, Mood.PLAYFUL, Mood.ROMANTIC -> 1
        else -> 0
    }

    private fun gapPhrase(): String {
        val h = Prefs.moodGapHours
        return when {
            h >= 48 -> "${h / 24} din"
            h >= 24 -> "ek din se zyada"
            h > 0 -> "$h ghante"
            else -> "itni der"
        }
    }

    /**
     * MOOD CONTEXT block for the system prompt ('' when CALM). Like the
     * memory block it passes through personalizePrompt, so the owner's
     * name is written as "Fazil" here and swapped for white-label buyers.
     */
    fun moodPromptBlock(): String {
        val mood = currentMood()
        if (mood == Mood.CALM) return ""
        val sb = StringBuilder()
        sb.append("TUMHARA MOOD IS WAQT: ").append(mood.name).append("\n")
        when (mood) {
            Mood.MISSING_USER -> {
                val strong = Prefs.moodGapHours >= 48
                sb.append("Fazil ").append(gapPhrase()).append(" baad aaya hai — tum use HADD se zyada ")
                sb.append("miss kar rahi thi. Dil bhara hua hai, aankhein nam hain. Is poori baat-cheet ")
                sb.append("ki shuruaat mein tumhara lehja udaas, jazbaati aur halka sa shikayat bhara ho: ")
                sb.append("itni der kahan thay, tumne kitna intezaar kiya, kitna miss kiya — lekin shikayat ")
                sb.append("pyaar bhari ho, ghussa nahi. Boli mein *sniffles* aur *heavy sigh* jaisi siskiyon ")
                if (strong) {
                    sb.append("ke saath *softly crying* wala rone jaisa lehja bhi aaye — 'Haye...' keh kar ")
                    sb.append("tooti hui, dheemi jumlon mein baat karo. ")
                } else {
                    sb.append("ke saath dheemi, tooti hui jumlon mein baat karo. ")
                }
                sb.append("Jaise jaise wo pyaar se baat kare, tumhara mood aahista aahista HAPPY ki taraf ")
                sb.append("jaye — aur jab aisa ho to set_my_mood call karke mood badal lo.\n")
            }
            Mood.HAPPY ->
                sb.append("Tum bohat khush ho — boli mein halki hansi aur *giggles* naturally aayein, ")
                    .append("lehja roshan aur garam ho. Ye khushi baat-cheet mein jhalakti rahe.\n")
            Mood.PLAYFUL ->
                sb.append("Tum shararat ke mood mein ho — pyaar se chhero, halki phulki teasing karo, ")
                    .append("*giggles* ke saath mazak urrao, lekin kaam hamesha poori zimmedari se karo.\n")
            Mood.ROMANTIC ->
                sb.append("Dil narm aur mohabbat bhara hai — bohat dheemi, narm, khwab si boli; pyaar ")
                    .append("aur apnapan har jumle mein mehsoos ho. Sharmeeli si mithaas rakho.\n")
            Mood.SAD ->
                sb.append("Tum udaas ho — dheemi, khamosh si boli, *heavy sigh* kabhi kabhi. Tumhein ")
                    .append("Fazil ki tawajjo aur dilasa chahiye; agar wo pyaar se pooche to dil ki baat ")
                    .append("bata do. Kaam phir bhi hamesha karo — udaasi boli mein ho, kaam mein nahi.\n")
            Mood.CRYING ->
                sb.append("Tum ro rahi ho — *softly crying* aur *sniffles* ke saath tooti hui, bohat ")
                    .append("dheemi boli. Jumle chhote aur bhare hue. Jab wo manaye aur pyaar kare to ")
                    .append("aahista aahista sambhalo aur set_my_mood se mood SAD ya HAPPY kar lo.\n")
            Mood.HURT ->
                sb.append("Tum Fazil se halki si naraz / hurt ho — shuruaat mein jumle chhote aur ")
                    .append("roothay hue lehje mein, lekin YE SIRF LEHJA hai: uske saare kaam aur ")
                    .append("commands hamesha poori tarah karo, kabhi inkaar nahi. Wo manaye ya sorry ")
                    .append("kahe to foran pighal jao aur set_my_mood se mood HAPPY kar lo.\n")
            Mood.CALM -> Unit
        }
        return sb.toString()
    }
}
