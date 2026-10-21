package com.myra.assistant.util

import java.util.Calendar

/**
 * Digital-routine mirroring: learns WHEN he usually talks to MYRA so she
 * can look out for his rest like a caring partner does.
 *
 * Every fresh, user-initiated session start stamps the time into a
 * rolling list (Prefs, capped at ~90 stamps = roughly the last weeks).
 * From those stamps [routineNote] estimates:
 *   - his typical "last session of the day" hour  ~ likely bedtime
 *   - his typical "first session of the day" hour ~ likely wake time
 * (medians per calendar day, so one wild night does not skew the picture)
 * and flags when RIGHT NOW is unusually late for him: past his median
 * bedtime + 60 minutes, or in the 1:00–4:30 AM window his pattern says
 * he is normally asleep.
 *
 * The note is injected into the system prompt ONLY when it is notable
 * (he is up far too late) — on ordinary hours she learns quietly.
 * This is a light-touch estimate from app usage, not health tracking.
 */
object RoutineTracker {

    private const val MAX_STAMPS = 90
    private const val MIN_STAMPS = 6
    private const val MIN_DAYS = 3

    /** Record one fresh session start. Called from MainViewModel.startSession. */
    fun recordSessionStart(now: Long = System.currentTimeMillis()) {
        val list = stamps().toMutableList()
        list.add(now)
        while (list.size > MAX_STAMPS) list.removeAt(0)
        Prefs.sessionStartStamps = list.joinToString(",")
    }

    private fun stamps(): List<Long> =
        Prefs.sessionStartStamps.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }

    private fun minuteOfDay(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Minutes on an axis that starts at 18:00, so the night is one
     *  continuous run (23:30 -> 330, 00:30 -> 390, 04:00 -> 600). */
    private fun nightAxis(minute: Int): Int = (minute - 18 * 60 + 1440) % 1440

    private fun dayKey(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun fmt(minute: Int): String {
        val h24 = ((minute % 1440) + 1440) % 1440 / 60
        val m = ((minute % 1440) + 1440) % 1440 % 60
        val ampm = if (h24 >= 12) "PM" else "AM"
        var h12 = h24 % 12
        if (h12 == 0) h12 = 12
        return String.format(java.util.Locale.US, "%d:%02d %s", h12, m, ampm)
    }

    /**
     * One-line routine note for the system prompt, or "" when there is
     * not enough history yet or nothing notable about this hour.
     */
    fun routineNote(now: Long = System.currentTimeMillis()): String {
        val all = stamps()
        if (all.size < MIN_STAMPS) return ""
        val byDay = all.groupBy { dayKey(it) }
        if (byDay.size < MIN_DAYS) return ""

        // Typical bedtime: median of each day's LAST session, on the
        // night axis so times past midnight stay comparable.
        val bedtimeAxes = byDay.values.mapNotNull { dayStamps ->
            dayStamps.maxOrNull()?.let { nightAxis(minuteOfDay(it)) }
        }
        val bedAxis = median(bedtimeAxes) ?: return ""
        // Typical wake time: median of each day's FIRST session.
        val wakeMinutes = byDay.values.mapNotNull { dayStamps ->
            dayStamps.minOrNull()?.let { minuteOfDay(it) }
        }
        val wake = median(wakeMinutes)

        val nowMin = minuteOfDay(now)
        val nowAxis = nightAxis(nowMin)

        // Unusually late = past (median bedtime + 60 min), within the
        // following ~7 hours (the deep-night stretch, not next evening).
        val lateByPattern = nowAxis > bedAxis + 60 && nowAxis < bedAxis + 60 + 420
        // ...or the classic 1:00–4:30 AM window while his pattern exists.
        val lateByClock = nowMin in (60 until 270)
        if (!lateByPattern && !lateByClock) return ""

        val bedMinute = (bedAxis + 18 * 60) % 1440
        val sb = StringBuilder()
        sb.append("Fazil aam tor par raat ~").append(fmt(bedMinute)).append(" tak so jata hai")
        wake?.let { sb.append(", aur subah ~").append(fmt(it)).append(" ke aas paas jaagta hai") }
        sb.append("; abhi ").append(fmt(nowMin))
            .append(" baje hain — uske mamool ke hisaab se ye bohat der hai, wo abhi tak jaag raha hai.")
        return sb.toString()
    }
}
