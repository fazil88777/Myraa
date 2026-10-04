package com.myra.assistant.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MYRA's long-term memory about the user.
 *
 * Facts the user shares (name, family, work, likes, plans) are stored
 * on-device in SharedPreferences and injected into EVERY new voice session's
 * system prompt — so she remembers them days later, like a real friend.
 *
 * The model writes facts through the remember_fact tool; [memoryBlock] is
 * appended to the system instruction in GeminiLiveClient.sendSetup().
 */
object MyraMemory {

    private const val PREFS = "myra_memory"
    private const val KEY = "facts_json"
    private const val MAX_FACTS = 200

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            seedIfEmpty()
        }
    }

    private fun prefs() =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun allFacts(): List<String> {
        if (!::appContext.isInitialized) return emptyList()
        return try {
            val raw = prefs().getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.getJSONObject(i).optString("text") }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Adds a fact; ignores blanks and exact duplicates. Returns true if stored. */
    fun addFact(rawText: String): Boolean {
        if (!::appContext.isInitialized) return false
        val text = rawText.trim().take(300)
        if (text.isEmpty()) return false
        return try {
            val cur = allFacts().toMutableList()
            if (cur.any { it.equals(text, ignoreCase = true) }) return false
            cur.add(0, text)
            while (cur.size > MAX_FACTS) cur.removeAt(cur.size - 1)
            val arr = JSONArray()
            cur.forEach { arr.put(JSONObject().put("text", it)) }
            prefs().edit().putString(KEY, arr.toString()).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** First-run seeds so she knows the basics from day one. Never overwrites. */
    private fun seedIfEmpty() {
        if (allFacts().isNotEmpty()) return
        addFact("Naam: Fazil")
        addFact("Fazil YouTube par drama videos banata hai — uska channel BATTO STORY hai")
        addFact("Fazil ke betay hain")
        addFact("Fazil trading seekh raha hai — BTC me dilchaspi hai, Quotex ka demo account istemal karta hai")
    }

    /** Block appended to the system prompt. Empty when nothing is stored. */
    fun memoryBlock(): String {
        val facts = allFacts()
        if (facts.isEmpty()) return ""
        return "TUMHEIN FAZIL KE BAARE MEIN YE SAB YAAD HAI (ye kabhi mat bhoolna, " +
                "chahe kitne din guzar jayein):\n" +
                facts.joinToString("\n") { "• $it" } + "\n"
    }
}
