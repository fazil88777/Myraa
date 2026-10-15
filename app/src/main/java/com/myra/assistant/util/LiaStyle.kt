package com.myra.assistant.util

import android.graphics.Color

/**
 * Central style maps for the LIA-style interface:
 * themes (background + accent), voices, languages.
 */
object LiaStyle {

    data class Theme(val key: String, val label: String, val gridColor: Int, val accent: Int)

    val THEMES = listOf(
        Theme("teal", "Teal Pulse", Color.parseColor("#0E222C"), Color.parseColor("#2DD4A8")),
        Theme("midnight", "Midnight Blue", Color.parseColor("#12233F"), Color.parseColor("#4D7CFE")),
        Theme("crimson", "Crimson Dark", Color.parseColor("#2C1214"), Color.parseColor("#FF5252")),
        Theme("violet", "Violet Haze", Color.parseColor("#221430"), Color.parseColor("#B388FF")),
        Theme("rose", "Rose Dark", Color.parseColor("#2C1420"), Color.parseColor("#FF6FA5")),
        Theme("black", "Pure Black", Color.parseColor("#141414"), Color.parseColor("#E8E8E8"))
    )

    fun theme(key: String): Theme = THEMES.firstOrNull { it.key == key } ?: THEMES[0]

    data class Voice(val key: String, val label: String)

    val VOICES = listOf(
        Voice("Sulafat", "Sulafat — Warm"),
        Voice("Aoede", "Aoede — Soft"),
        Voice("Kore", "Kore — Bright"),
        Voice("Puck", "Puck — Deep")
    )

    fun voiceLabel(key: String): String =
        VOICES.firstOrNull { it.key == key }?.label ?: "Sulafat — Warm"

    data class Lang(val key: String, val label: String)

    val LANGS = listOf(
        Lang("auto", "Auto"),
        Lang("urdu", "Roman Urdu"),
        Lang("english", "English"),
        Lang("hindi", "Hindi")
    )

    fun langLabel(key: String): String =
        LANGS.firstOrNull { it.key == key }?.label ?: "Auto"

    val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f)

    fun speedLabel(s: Float): String = when (s) {
        0.75f -> "0.75x — Slow"
        1.25f -> "1.25x — Natural"
        1.5f -> "1.5x — Brisk"
        else -> "1.0x — Soft"
    }
}
