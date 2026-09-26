package com.myra.assistant.util

import android.content.Context

/**
 * Own prefs file for YouTube analytics setup (API key + channel handle).
 * Never touches the main Prefs object.
 */
object YouTubeStore {

    private const val FILE = "myra_youtube"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String =
        sp(context).getString("yt_api_key", "") ?: ""

    fun setApiKey(context: Context, v: String) {
        sp(context).edit().putString("yt_api_key", v.trim()).apply()
    }

    fun getHandle(context: Context): String =
        sp(context).getString("yt_handle", "") ?: ""

    fun setHandle(context: Context, v: String) {
        sp(context).edit().putString("yt_handle", v.trim().removePrefix("@")).apply()
    }

    fun getChannelId(context: Context): String =
        sp(context).getString("yt_channel_id", "") ?: ""

    fun setChannelId(context: Context, v: String) {
        sp(context).edit().putString("yt_channel_id", v.trim()).apply()
    }
}
