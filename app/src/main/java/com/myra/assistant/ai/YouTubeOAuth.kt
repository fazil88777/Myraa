package com.myra.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.myra.assistant.util.YouTubeStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * YouTube OAuth — installed-app flow (loopback redirect + PKCE).
 *
 * Device flow (google.com/device) does NOT support the yt-analytics.readonly
 * scope ("Invalid device flow scope"), so we open Google's login page in
 * Chrome and catch the redirect on http://127.0.0.1:<port>/ locally.
 * Needs the DESKTOP OAuth client ID ("MYRA"), not the TV one ("MYRA-TV").
 * Read-only scope; tokens stay on the device.
 */
object YouTubeOAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/yt-analytics.readonly"
    private val http = OkHttpClient()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var authCode: String? = null
    @Volatile private var verifier: String? = null
    @Volatile private var port: Int = 0

    fun isLinked(context: Context): Boolean =
        YouTubeStore.getRefreshToken(context).isNotBlank() ||
                getValidAccessToken(context) != null

    /**
     * Step 1: start a tiny local server, open Google login in Chrome.
     * Returns the auth URL (already opened), or null if client ID missing.
     */
    fun startLogin(context: Context): String? {
        val clientId = YouTubeStore.getOAuthClientId(context)
        if (clientId.isBlank()) return null
        stopServer()
        authCode = null
        verifier = null
        return try {
            val v = genVerifier()
            verifier = v
            val srv = ServerSocket(0)
            port = srv.localPort
            server = srv
            Thread { acceptLoop(srv) }.apply { isDaemon = true; start() }
            val redirect = "http://127.0.0.1:$port/"
            val url = "https://accounts.google.com/o/oauth2/v2/auth" +
                    "?client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                    "&redirect_uri=${URLEncoder.encode(redirect, "UTF-8")}" +
                    "&response_type=code" +
                    "&scope=${URLEncoder.encode(SCOPE, "UTF-8")}" +
                    "&code_challenge=${pkceChallenge(v)}" +
                    "&code_challenge_method=S256" +
                    "&access_type=offline" +
                    "&prompt=consent"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            url
        } catch (_: Exception) {
            stopServer()
            null
        }
    }

    /**
     * Step 2: called after the user taps Allow in Chrome.
     * Exchanges the captured code for tokens and saves them.
     */
    fun confirmLogin(context: Context): String {
        val code = authCode
        if (code.isNullOrBlank()) {
            return if (server != null)
                "PENDING: lagta hai abhi Allow nahi daba. Chrome mein khule Google page pe " +
                        "apne CHANNEL wale Gmail se login karke Allow dabao, phir 'code daal diya' dobara bolo."
            else
                "ERROR: login session nahi mili — 'youtube login karo' se dobara shuru karo."
        }
        val clientId = YouTubeStore.getOAuthClientId(context)
        val v = verifier
        if (clientId.isBlank() || v.isNullOrBlank()) {
            stopServer()
            return "ERROR: client ID missing — pehle 'mera youtube client id ... hai' bolo (DESKTOP wali)."
        }
        return try {
            val redirect = "http://127.0.0.1:$port/"
            val (httpCode, json) = postForm(
                "https://oauth2.googleapis.com/token",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to v,
                "redirect_uri" to redirect,
                "grant_type" to "authorization_code"
            )
            stopServer()
            if (httpCode == 200 && json != null) {
                val access = json.optString("access_token")
                val refresh = json.optString("refresh_token")
                val expiresIn = json.optLong("expires_in", 3600)
                if (access.isBlank()) {
                    return "ERROR: token nahi mila. 'youtube login karo' se dobara try karo."
                }
                YouTubeStore.setAccessToken(context, access)
                if (refresh.isNotBlank()) YouTubeStore.setRefreshToken(context, refresh)
                YouTubeStore.setTokenExpiry(context, System.currentTimeMillis() + expiresIn * 1000)
                "OK: YouTube login ho gaya! Ab 'full analyze batao' bolo."
            } else {
                val err = json?.optString("error") ?: "unknown"
                "ERROR: Google ne mana kar diya ($err). 'youtube login karo' se dobara try karo."
            }
        } catch (e: Exception) {
            stopServer()
            "ERROR: network masla. Dobara 'youtube login karo' bolo."
        }
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
                val expiresIn = json.optLong("expires_in", 3600)
                YouTubeStore.setTokenExpiry(context, now + expiresIn * 1000)
                return access
            }
        }
        return null
    }

    /** GET with the user's OAuth token. Returns null when login is needed/failed. */
    fun authedGet(context: Context, url: String): JSONObject? {
        val token = getValidAccessToken(context) ?: return null
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- local redirect catcher ----------

    private fun acceptLoop(srv: ServerSocket) {
        try {
            srv.soTimeout = 300_000 // 5 minutes max
            while (authCode == null) {
                val sock = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val requestLine = reader.readLine() ?: ""
                    var line: String?
                    do {
                        line = reader.readLine()
                    } while (line != null && line.isNotEmpty())
                    val code = Regex("[?&]code=([^& ]+)").find(requestLine)?.groupValues?.get(1)
                    val err = Regex("[?&]error=([^& ]+)").find(requestLine)?.groupValues?.get(1)
                    val ok = !code.isNullOrBlank()
                    if (ok) authCode = URLDecoder.decode(code, "UTF-8")
                    val msg = if (ok) "Ho gaya! ✅<br>Wapas MYRA app mein jao aur kaho:<br><b>code daal diya</b>"
                    else "Error: ${err ?: "pata nahi"} — MYRA app mein dobara try karo"
                    val html = "<html><body style='font-family:sans-serif;text-align:center;" +
                            "padding-top:60px'><h2>$msg</h2></body></html>"
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val out = PrintWriter(sock.getOutputStream())
                    out.print(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    )
                    out.flush()
                    sock.getOutputStream().write(bytes)
                    sock.getOutputStream().flush()
                    sock.close()
                    if (ok) break
                } catch (_: Exception) {
                    try {
                        sock.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                srv.close()
            } catch (_: Exception) {
            }
            server = null
        }
    }

    private fun stopServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    // ---------- helpers ----------

    private fun genVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun postForm(url: String, vararg fields: Pair<String, String>): Pair<Int, JSONObject?> {
        val body = fields.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
            return resp.code to json
        }
    }
}
