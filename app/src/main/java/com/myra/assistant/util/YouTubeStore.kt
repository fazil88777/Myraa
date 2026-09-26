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

    // ---- OAuth (Phase 2: private analytics, read-only) ----

    fun getOAuthClientId(context: Context): String =
        sp(context).getString("yt_oauth_client_id", "") ?: ""

    fun setOAuthClientId(context: Context, v: String) {
        sp(context).edit().putString("yt_oauth_client_id", v.trim()).apply()
    }

    fun getAccessToken(context: Context): String =
        sp(context).getString("yt_access_token", "") ?: ""

    fun setAccessToken(context: Context, v: String) {
        sp(context).edit().putString("yt_access_token", v).apply()
    }

    fun getRefreshToken(context: Context): String =
        sp(context).getString("yt_refresh_token", "") ?: ""

    fun setRefreshToken(context: Context, v: String) {
        sp(context).edit().putString("yt_refresh_token", v).apply()
    }

    fun getTokenExpiry(context: Context): Long =
        sp(context).getLong("yt_token_expiry", 0)

    fun setTokenExpiry(context: Context, v: Long) {
        sp(context).edit().putLong("yt_token_expiry", v).apply()
    }
}
