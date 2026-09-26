package com.myra.assistant.util

import android.content.Context
import android.util.Base64

/**
 * Stores scheduled WhatsApp messages in SharedPreferences.
 * Each task: id | Base64(contact) | Base64(message) | atMillis (one per line).
 */
object ScheduledStore {

    private const val PREFS = "scheduled_msgs"
    private const val KEY_TASKS = "tasks"
    private const val KEY_LAST_RESULT = "last_result"

    data class Task(val id: Long, val contact: String, val message: String, val atMillis: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enc(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun dec(s: String): String =
        String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)

    fun add(c: Context, task: Task) {
        val all = all(c).toMutableList()
        all.removeAll { it.id == task.id }
        all.add(task)
        save(c, all)
    }

    fun all(c: Context): List<Task> {
        val raw = prefs(c).getString(KEY_TASKS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 4) null else try {
                Task(p[0].toLong(), dec(p[1]), dec(p[2]), p[3].toLong())
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.atMillis }
    }

    fun upcoming(c: Context): List<Task> =
        all(c).filter { it.atMillis > System.currentTimeMillis() - 60_000 }

    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })

    fun find(c: Context, id: Long): Task? = all(c).find { it.id == id }

    fun removePast(c: Context) =
        save(c, all(c).filter { it.atMillis > System.currentTimeMillis() - 60_000 })

    private fun save(c: Context, tasks: List<Task>) {
        val raw = tasks.joinToString("\n") {
            "${it.id}|${enc(it.contact)}|${enc(it.message)}|${it.atMillis}"
        }
        prefs(c).edit().putString(KEY_TASKS, raw).apply()
    }

    fun setLastResult(c: Context, s: String) =
        prefs(c).edit().putString(KEY_LAST_RESULT, s).apply()

    fun lastResult(c: Context): String =
        prefs(c).getString(KEY_LAST_RESULT, "") ?: ""
}
