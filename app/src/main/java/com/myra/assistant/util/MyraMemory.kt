package com.myra.assistant.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MYRA's long-term memory about the user — all on-device, no cloud.
 *
 * Three stores:
 *  1. FACTS the user shares (name, family, work, likes, plans) — SharedPreferences,
 *     written by the model through the remember_fact tool.
 *  2. TASKS with pending/done status (myra_brain.json in the app's private files
 *     dir) — written through add_task / complete_task.
 *  3. SESSION RECAP — a 2-3 line summary of the last session (same file),
 *     written through save_recap when a session ends.
 *
 * [memoryBlock] is appended to EVERY new voice session's system prompt, so she
 * remembers old conversations and proactively follows up on pending tasks —
 * like "Fazil, aap video generate kar rahe thay, uska kya bana?"
 */
object MyraMemory {

    private const val PREFS = "myra_memory"
    private const val KEY = "facts_json"
    private const val MAX_FACTS = 200
    private const val BRAIN_FILE = "myra_brain.json"
    private const val MAX_TASKS = 50

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

    // ---------- Tasks + session recap (myra_brain.json, private files dir) ----------

    private fun brainFile() = java.io.File(appContext.filesDir, BRAIN_FILE)

    private fun readBrain(): JSONObject {
        return try {
            val f = brainFile()
            if (!f.exists()) JSONObject() else JSONObject(f.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeBrain(brain: JSONObject) {
        try {
            brainFile().writeText(brain.toString())
        } catch (_: Exception) {
        }
    }

    private data class Task(val id: Long, val text: String, val done: Boolean)

    private fun readTasks(): MutableList<Task> {
        val out = mutableListOf<Task>()
        try {
            val arr = readBrain().optJSONArray("tasks") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Task(o.optLong("id"), o.optString("text"), o.optBoolean("done")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writeTasks(tasks: List<Task>) {
        val brain = readBrain()
        val arr = JSONArray()
        tasks.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done))
        }
        brain.put("tasks", arr)
        writeBrain(brain)
    }

    /** Adds a pending task; ignores blanks and near-duplicates. Returns true if stored. */
    fun addTask(rawText: String): Boolean {
        if (!::appContext.isInitialized) return false
        val text = rawText.trim().take(200)
        if (text.isEmpty()) return false
        val tasks = readTasks()
        if (tasks.any { !it.done && it.text.equals(text, ignoreCase = true) }) return false
        if (tasks.any { !it.done && (it.text.contains(text, ignoreCase = true) || text.contains(it.text, ignoreCase = true)) }) return false
        tasks.add(0, Task(System.currentTimeMillis(), text, false))
        while (tasks.size > MAX_TASKS) tasks.removeAt(tasks.size - 1)
        writeTasks(tasks)
        return true
    }

    /** Marks a pending task done — matched by id or a few words of its text. */
    fun completeTask(query: String): Boolean {
        if (!::appContext.isInitialized) return false
        val q = query.trim()
        if (q.isEmpty()) return false
        val tasks = readTasks()
        val id = q.toLongOrNull()
        val idx = when {
            id != null -> tasks.indexOfFirst { it.id == id && !it.done }
            else -> tasks.indexOfFirst { !it.done && it.text.contains(q, ignoreCase = true) }
        }
        if (idx < 0) return false
        tasks[idx] = tasks[idx].copy(done = true)
        writeTasks(tasks)
        return true
    }

    fun pendingTasks(): List<String> =
        if (!::appContext.isInitialized) emptyList()
        else readTasks().filter { !it.done }.map { it.text }

    fun recentDone(limit: Int = 4): List<String> =
        if (!::appContext.isInitialized) emptyList()
        else readTasks().filter { it.done }.take(limit).map { it.text }

    /** Saves a short summary of the current session for next time. */
    fun saveRecap(rawText: String): Boolean {
        if (!::appContext.isInitialized) return false
        val text = rawText.trim().take(600)
        if (text.isEmpty()) return false
        val brain = readBrain()
        brain.put("recap", text)
        brain.put("recap_ts", System.currentTimeMillis())
        writeBrain(brain)
        return true
    }

    private fun lastRecap(): String =
        if (!::appContext.isInitialized) ""
        else try {
            readBrain().optString("recap", "")
        } catch (_: Exception) {
            ""
        }

    /** Block appended to the system prompt. Empty when nothing is stored. */
    fun memoryBlock(): String {
        if (!::appContext.isInitialized) return ""
        if (!Prefs.memoryEnabled) return ""
        val sb = StringBuilder()
        val facts = allFacts()
        if (facts.isNotEmpty()) {
            sb.append("TUMHEIN FAZIL KE BAARE MEIN YE SAB YAAD HAI (ye kabhi mat bhoolna, ")
            sb.append("chahe kitne din guzar jayein):\n")
            facts.forEach { sb.append("\u2022 ").append(it).append("\n") }
        }
        val pending = pendingTasks()
        if (pending.isNotEmpty()) {
            sb.append("TUMHARE ADHOORE KAAM (purane sessions ke pending tasks ")
            sb.append("\u2014 inka zikr karna ya inhein poora karna tumhari zimmedari hai):\n")
            pending.forEach { sb.append("\u2022 ").append(it).append("\n") }
        }
        val done = recentDone()
        if (done.isNotEmpty()) {
            sb.append("HAAL HI MEIN POORE KIYE:\n")
            done.forEach { sb.append("\u2022 ").append(it).append("\n") }
        }
        val recap = lastRecap()
        if (recap.isNotBlank()) {
            sb.append("PICHHLI BAAR YE HUA THA (last session ka khulasa):\n")
            sb.append(recap).append("\n")
        }
        if (pending.isNotEmpty()) {
            sb.append("PURANI BAATON KO YAAD RAKHNA \u2014 SAKHT ZAROORI: Fazil jab dobara aaye ")
            sb.append("to nayee shuruaat mat karo \u2014 upar ADHOORE KAAM mein se EIK ka zikr ")
            sb.append("greeting ke saath naturally karo (ek se zyada nahi, nagging nahi). Jab koi ")
            sb.append("kaam poora ho jaye to foran complete_task call karo; naya kaam mile to ")
            sb.append("foran add_task call karo.\n")
        }
        return sb.toString()
    }
}
