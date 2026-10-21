package com.myra.assistant.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores MYRA's proactive check-ins (caring follow-up notifications she
 * schedules herself through the schedule_checkin tool) in a dedicated
 * SharedPreferences file as a JSON list.
 *
 * Each entry: { id, topic, message, triggerAtMs }. The alarm for each
 * entry lives in [com.myra.assistant.service.CheckInReceiver]; this
 * store is the source of truth and survives reboots (the boot receiver
 * re-arms every future entry). Capped at [MAX] entries — the oldest
 * (smallest id = earliest created) are dropped first.
 */
object CheckInStore {

    private const val PREFS = "checkins"
    private const val KEY = "items"
    private const val MAX = 20

    data class Entry(
        val id: Long,
        val topic: String,
        val message: String,
        val triggerAtMs: Long
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(c: Context, entry: Entry) {
        val all = all(c).toMutableList()
        all.removeAll { it.id == entry.id }
        all.add(entry)
        // Cap: drop the OLDEST created beyond the limit, keep the newest.
        val trimmed = if (all.size > MAX) all.sortedBy { it.id }.takeLast(MAX) else all
        save(c, trimmed)
    }

    fun all(c: Context): List<Entry> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getLong("id"),
                    o.optString("topic"),
                    o.optString("message"),
                    o.getLong("triggerAtMs")
                )
            }.sortedBy { it.triggerAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun find(c: Context, id: Long): Entry? = all(c).find { it.id == id }

    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })

    /** Drop entries whose moment has already passed (keeps a 1-minute grace). */
    fun removePast(c: Context) =
        save(c, all(c).filter { it.triggerAtMs > System.currentTimeMillis() - 60_000 })

    private fun save(c: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("topic", it.topic)
                    .put("message", it.message)
                    .put("triggerAtMs", it.triggerAtMs)
            )
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }
}
