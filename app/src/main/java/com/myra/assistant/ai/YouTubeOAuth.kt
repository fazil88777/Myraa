package com.myra.assistant.ai

import android.content.Context
import com.myra.assistant.util.YouTubeStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube OAuth via the Device Flow — no SHA-1, no redirect URIs, no extra SDK.
 * User enters a short code at google.com/device in Chrome, app polls for tokens.
 * Only the READ-ONLY scope is requested: yt-analytics.readonly.
 */
object YouTubeOAuth {

    const val SCOPE = "https://www.googleapis.com/auth/yt-analytics.readonly"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    data class DeviceFlow(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int
    )

    private fun postForm(url: String, vararg params: Pair<String, String>): Pair<Int, JSONObject?> {
        return try {
            val body = FormBody.Builder()
            for ((k, v) in params) body.add(k, v)
            val req = Request.Builder().url(url).post(body.build()).build()
            http.newCall(req).execute().use { resp ->
                val json = try {
                    JSONObject(resp.body?.string() ?: "{}")
                } catch (_: Exception) {
                    JSONObject()
                }
                resp.code to json
            }
        } catch (_: Exception) {
            -1 to null
        }
    }

    /** Step 1: get the user code MYRA will speak out. */
    fun startDeviceFlow(context: Context): DeviceFlow? {
        val clientId = YouTubeStore.getOAuthClientId(context)
        if (clientId.isBlank()) return null
        val (code, json) = postForm(
            "https://oauth2.googleapis.com/device/code",
            "client_id" to clientId,
            "scope" to SCOPE
        )
        if (code != 200 || json == null) return null
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        if (deviceCode.isBlank() || userCode.isBlank()) return null
        return DeviceFlow(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = json.optString("verification_url", "https://www.google.com/device"),
            intervalSec = json.optInt("interval", 5)
        )
    }

    /** Step 2 (single poll): returns OK / PENDING / DENIED / ERROR:... */
    private fun tryPoll(context: Context, deviceCode: String): Triple<String, String?, String?> {
        val clientId = YouTubeStore.getOAuthClientId(context)
        val (code, json) = postForm(
            "https://oauth2.googleapis.com/token",
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
        )
        if (code == 200 && json != null) {
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val expiresIn = json.optLong("expires_in", 3600)
            if (access.isNotBlank()) {
                YouTubeStore.setAccessToken(context, access)
                if (refresh.isNotBlank()) YouTubeStore.setRefreshToken(context, refresh)
                YouTubeStore.setTokenExpiry(
                    context,
                    System.currentTimeMillis() + expiresIn * 1000
                )
                return Triple("OK", access, refresh)
            }
        }
        val err = json?.optString("error") ?: ""
        return when {
            err == "authorization_pending" || err == "slow_down" -> Triple("PENDING", null, null)
            err == "access_denied" -> Triple("DENIED", null, null)
            err == "expired_token" -> Triple("EXPIRED", null, null)
            else -> Triple("ERROR:$err", null, null)
        }
    }

    /**
     * Step 2 (blocking, up to ~75s): waits until the user approves (or denies).
     * MYRA calls this after the user says "code daal diya".
     */
    fun awaitApproval(context: Context, deviceCode: String, intervalSec: Int): String {
        val deadline = System.currentTimeMillis() + 75_000L
        val wait = (intervalSec * 1000L).coerceAtLeast(5000L)
        while (System.currentTimeMillis() < deadline) {
            when (val (status) = tryPoll(context, deviceCode)) {
                "OK" -> return "OK: YouTube login ho gaya! Ab 'full analyze batao' bolo."
                "DENIED" -> return "ERROR: tumne access deny kar diya. Dobara login karna ho to 'youtube login karo' bolo."
                "EXPIRED" -> return "ERROR: code expire ho gaya. 'youtube login karo' se naya code lo."
                else -> if (status.startsWith("ERROR:")) return status
            }
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
        return "PENDING: abhi approve nahi hua lagta hai. Pehle Chrome mein code daal ke " +
                "Allow dabao, phir 'code daal diya' dobara bolo."
    }

    /** Returns a valid access token, refreshing silently when needed. Null = login needed. */
    fun getValidAccessToken(context: Context): String? {
        val now = System.currentTimeMillis()
        val cached = YouTubeStore.getAccessToken(context)
        if (cached.isNotBlank() && now < YouTubeStore.getTokenExpiry(context) - 60_000) {
            return cached
        }
        val refresh = YouTubeStore.getRefreshToken(context)
        val clientId = YouTubeStore.getOAuthClientId(context)
        if (refresh.isBlank() || clientId.isBlank()) return null
        val (code, json) = postForm(
            "https://oauth2.googleapis.com/token",
            "client_id" to clientId,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token"
        )
        if (code == 200 && json != null) {
            val access = json.optString("access_token")
            if (access.isNotBlank()) {
                YouTubeStore.setAccessToken(context, access)
                YouTubeStore.setTokenExpiry(
                    context,
                    now + json.optLong("expires_in", 3600) * 1000
                )
                return access
            }
        }
        return null
    }

    fun isLinked(context: Context): Boolean =
        YouTubeStore.getRefreshToken(context).isNotBlank()

    /** Authenticated GET against googleapis; auto-refreshes once on 401. */
    fun authedGet(context: Context, url: String): JSONObject? {
        fun call(token: String): Pair<Int, JSONObject?> {
            return try {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "MYRA/1.0")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val json = try {
                        JSONObject(resp.body?.string() ?: "{}")
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    resp.code to json
                }
            } catch (_: Exception) {
                -1 to null
            }
        }
        var token = getValidAccessToken(context) ?: return null
        var (code, json) = call(token)
        if (code == 401) {
            YouTubeStore.setAccessToken(context, "")
            YouTubeStore.setTokenExpiry(context, 0)
            token = getValidAccessToken(context) ?: return null
            val r = call(token)
            code = r.first
            json = r.second
        }
        return if (code == 200) json else null
    }
}
