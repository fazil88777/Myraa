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
 *  4. ENTRIES (same brain file) — PROJECT entries (save_project /
 *     update_project_status) and FAMILY facts (save_family_fact), each with a
 *     category, a created-at timestamp and a status. [searchMemory] searches
 *     across ALL stores at once (facts, tasks, projects, family, recap) and
 *     returns dated, categorized results for the search_memory tool.
 *
 * Old data survives upgrades: every new JSON field is optional on read
 * (optString/optLong with defaults), so existing facts/tasks/recaps load
 * unchanged.
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

    fun allFacts(): List<String> = allFactsWithTs().map { it.first }

    /** Facts with their save time (epoch ms). Pre-upgrade facts have no
     *  timestamp stored — they read back as 0, never an error. */
    fun allFactsWithTs(): List<Pair<String, Long>> {
        if (!::appContext.isInitialized) return emptyList()
        return try {
            val raw = prefs().getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                o.optString("text") to o.optLong("ts", 0L)
            }.filter { it.first.isNotBlank() }
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
            // Keep timestamps already stored for surviving facts; stamp now
            // for the new one (and any fact whose ts was never stored).
            val tsByText = allFactsWithTs().toMap()
            val now = System.currentTimeMillis()
            cur.forEach {
                arr.put(JSONObject().put("text", it).put("ts", tsByText[it] ?: now))
            }
            prefs().edit().putString(KEY, arr.toString()).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** First-run seeds so she knows the basics from day one. Never overwrites.
     *  White-label: only the owner's name from Settings is seeded. Personal
     *  facts (channel, family, trading) are learned fresh per owner through
     *  conversation instead of shipping Fazil's. Existing installs keep
     *  their saved facts untouched (this runs only on empty memory). */
    private fun seedIfEmpty() {
        if (allFacts().isNotEmpty()) return
        val name = com.myra.assistant.util.Prefs.userName.ifBlank { "Dost" }
        addFact("Naam: $name")
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

    /** Read-only accessor for the Memory screen. */
    fun lastRecapText(): String = lastRecap()

    private fun lastRecap(): String =
        if (!::appContext.isInitialized) ""
        else try {
            readBrain().optString("recap", "")
        } catch (_: Exception) {
            ""
        }

    // ---------- Categorized entries: projects + family (myra_brain.json) ----------

    private const val MAX_ENTRIES = 100

    private data class Entry(
        val title: String,
        val body: String,
        val category: String,      // "PROJECT" or "FAMILY"
        val ts: Long,              // created at (epoch ms)
        var status: String,        // PROJECT: e.g. "chal raha hai" / "mukammal"; FAMILY: ""
        var updated: Long          // last status change (epoch ms)
    )

    private fun readEntries(): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val arr = readBrain().optJSONArray("entries") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ts = o.optLong("ts", 0L)
                out.add(
                    Entry(
                        o.optString("title"),
                        o.optString("body"),
                        o.optString("category", "PROJECT"),
                        ts,
                        o.optString("status"),
                        o.optLong("updated", ts)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writeEntries(entries: List<Entry>) {
        val brain = readBrain()
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject()
                    .put("title", it.title)
                    .put("body", it.body)
                    .put("category", it.category)
                    .put("ts", it.ts)
                    .put("status", it.status)
                    .put("updated", it.updated)
            )
        }
        brain.put("entries", arr)
        writeBrain(brain)
    }

    /** Saves a project he handed her (script, film, ongoing work) with status
     *  'chal raha hai' and today's date. Re-saving the same title refreshes
     *  its details instead of duplicating it. Returns true if stored. */
    fun saveProject(name: String, details: String): Boolean {
        if (!::appContext.isInitialized) return false
        val title = name.trim().take(120)
        if (title.isEmpty()) return false
        val body = details.trim().take(600)
        val entries = readEntries()
        val now = System.currentTimeMillis()
        val existing = entries.indexOfFirst {
            it.category == "PROJECT" && it.title.equals(title, ignoreCase = true)
        }
        if (existing >= 0) {
            val old = entries[existing]
            entries[existing] = old.copy(body = if (body.isNotBlank()) body else old.body)
            entries[existing].updated = now
            writeEntries(entries)
            return true
        }
        entries.add(0, Entry(title, body, "PROJECT", now, "chal raha hai", now))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        writeEntries(entries)
        return true
    }

    /** Updates the status of the best-matching PROJECT entry (+ stamps the
     *  date of the change). Match: title contains the query (or the reverse),
     *  else best word overlap. Returns true if a project was updated. */
    fun updateProjectStatus(name: String, status: String): Boolean {
        if (!::appContext.isInitialized) return false
        val q = name.trim()
        val s = status.trim().take(200)
        if (q.isEmpty() || s.isEmpty()) return false
        val entries = readEntries()
        var idx = entries.indexOfFirst {
            it.category == "PROJECT" &&
                (it.title.contains(q, ignoreCase = true) || q.contains(it.title, ignoreCase = true))
        }
        if (idx < 0) {
            val tokens = tokensOf(q)
            var best = 0
            entries.forEachIndexed { i, e ->
                if (e.category != "PROJECT") return@forEachIndexed
                val overlap = tokens.count { e.title.lowercase().contains(it) }
                if (overlap > best) {
                    best = overlap
                    idx = i
                }
            }
            if (best == 0) idx = -1
        }
        if (idx < 0) return false
        entries[idx].status = s
        entries[idx].updated = System.currentTimeMillis()
        writeEntries(entries)
        return true
    }

    /** Saves one fact about his family (health, news, visits) so she can
     *  follow up on it later like family does. Returns true if stored. */
    fun saveFamilyFact(rawText: String): Boolean {
        if (!::appContext.isInitialized) return false
        val text = rawText.trim().take(300)
        if (text.isEmpty()) return false
        val entries = readEntries()
        if (entries.any { it.category == "FAMILY" && it.title.equals(text, ignoreCase = true) }) return false
        val now = System.currentTimeMillis()
        entries.add(0, Entry(text, "", "FAMILY", now, "", now))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        writeEntries(entries)
        return true
    }

    fun recentProjects(limit: Int = 5): List<Entry> =
        if (!::appContext.isInitialized) emptyList()
        else readEntries().filter { it.category == "PROJECT" }.take(limit)

    fun recentFamily(limit: Int = 5): List<Entry> =
        if (!::appContext.isInitialized) emptyList()
        else readEntries().filter { it.category == "FAMILY" }.take(limit)

    // ---------- Unified long-term memory search (search_memory tool) ----------

    private data class SearchItem(
        val title: String,
        val body: String,
        val category: String,
        val ts: Long,
        val status: String
    )

    private fun tokensOf(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }

    private fun dateLabel(ts: Long): String =
        if (ts <= 0) ""
        else java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH)
            .format(java.util.Date(ts))

    /** One result line, e.g. "[12 Oct, PROJECT, chal raha hai] Title — body".
     *  Date/status parts are omitted when unknown (old data has no ts). */
    fun formatLine(title: String, body: String, category: String, ts: Long, status: String): String {
        val parts = mutableListOf<String>()
        dateLabel(ts).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        parts.add(category)
        status.trim().takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val text = when {
            title.isNotBlank() && body.isNotBlank() && !body.equals(title, ignoreCase = true) ->
                "$title \u2014 $body"
            title.isNotBlank() -> title
            else -> body
        }
        return "[" + parts.joinToString(", ") + "] " + text
    }

    private fun formatLine(e: Entry): String =
        formatLine(e.title, e.body, e.category, e.ts, e.status)

    private fun allSearchItems(): List<SearchItem> {
        val out = mutableListOf<SearchItem>()
        // Facts (ts may be 0 for pre-upgrade facts).
        allFactsWithTs().forEach { (text, ts) -> out.add(SearchItem(text, "", "FACT", ts, "")) }
        // Tasks: id IS the creation time (addTask stamps currentTimeMillis).
        readTasks().forEach {
            out.add(SearchItem(it.text, "", "TASK", it.id, if (it.done) "mukammal" else "chal raha hai"))
        }
        // Projects + family entries.
        readEntries().forEach { out.add(SearchItem(it.title, it.body, it.category, it.ts, it.status)) }
        // Last session recap.
        try {
            val brain = readBrain()
            val recap = brain.optString("recap", "")
            if (recap.isNotBlank()) {
                out.add(SearchItem("", recap, "RECAP", brain.optLong("recap_ts", 0L), ""))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Case-insensitive keyword search over every memory store. Returns a
     *  ready-to-read result string for the model (max 8 hits, best first),
     *  or a clear 'nothing found' line — she must never invent a past that
     *  is not in here. */
    fun searchMemory(query: String): String {
        if (!::appContext.isInitialized) return "kuch nahi mila \u2014 memory abhi ready nahi hai."
        val q = query.trim()
        if (q.isEmpty()) return "kuch nahi mila \u2014 query khaali thi."
        val tokens = tokensOf(q)
        val scored = allSearchItems().mapNotNull { item ->
            val hay = (item.title + " " + item.body).lowercase()
            var score = tokens.count { hay.contains(it) }
            if (q.length >= 3 && hay.contains(q.lowercase())) score += 2
            if (score > 0) item to score else null
        }.sortedWith(
            compareByDescending<Pair<SearchItem, Int>> { it.second }
                .thenByDescending { it.first.ts }
        )
        if (scored.isEmpty()) {
            return "kuch nahi mila \u2014 '$q' ke baare mein memory mein kuch save nahi hai. " +
                "User ko imandari se batao ke ye baat yaad-daasht mein nahi hai; kahani mat banao."
        }
        val sb = StringBuilder()
        sb.append("Memory search '").append(q).append("' \u2014 ")
            .append(scored.size.coerceAtMost(8)).append(" result(s) mile:\n")
        scored.take(8).forEach { (item, _) ->
            sb.append("\u2022 ")
                .append(formatLine(item.title, item.body, item.category, item.ts, item.status))
                .append("\n")
        }
        return sb.toString().trim()
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
        val projects = recentProjects()
        if (projects.isNotEmpty()) {
            sb.append("CHAL RAHE PROJECTS (har project ki tareekh aur status \u2014 ")
            sb.append("wo poochhe to search_memory karke taaza jawab dena):\n")
            projects.forEach { sb.append("\u2022 ").append(formatLine(it)).append("\n") }
        }
        val family = recentFamily()
        if (family.isNotEmpty()) {
            sb.append("GHAR WALON KE BAARE MEIN YAAD RAKHA HAI (family \u2014 in par ")
            sb.append("khud follow-up karna, jaise 'Ammi ki tabiyat ab kaisi hai?'):\n")
            family.forEach { sb.append("\u2022 ").append(formatLine(it)).append("\n") }
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
