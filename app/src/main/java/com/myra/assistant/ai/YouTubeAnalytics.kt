package com.myra.assistant.ai

import android.content.Context
import com.myra.assistant.util.YouTubeStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube channel analytics via the free YouTube Data API v3.
 * Only reads PUBLIC data (views, likes, titles, subs) — it can never
 * change anything on the channel. ~3 quota units per analysis.
 */
object YouTubeAnalytics {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://www.googleapis.com/youtube/v3"

    private fun get(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "MYRA/1.0")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve @handle -> channelId and cache it. Returns null if not found. */
    fun resolveChannelId(context: Context, apiKey: String, handle: String): String? {
        val h = handle.trim().removePrefix("@")
        if (h.isBlank()) return null
        val id = get("$BASE/channels?part=id&forHandle=$h&key=$apiKey")
            ?.optJSONArray("items")?.optJSONObject(0)?.optString("id")
            ?.takeIf { it.isNotBlank() }
        if (id != null) YouTubeStore.setChannelId(context, id)
        return id
    }

    /**
     * Full channel report as plain text for the voice model to speak:
     * subs, total views, latest video stats, and latest-vs-previous performance.
     */
    fun analyze(context: Context): String {
        val apiKey = YouTubeStore.getApiKey(context)
        if (apiKey.isBlank()) {
            return "ERROR: YouTube API key save nahi hai — user se kaho wo bole " +
                    "'meri youtube key' aur phir apni key bol de."
        }
        var channelId = YouTubeStore.getChannelId(context)
        if (channelId.isBlank()) {
            val handle = YouTubeStore.getHandle(context)
            if (handle.isBlank()) {
                return "ERROR: channel ka handle nahi pata — user se uske YouTube channel " +
                        "ka handle pucho (jaise @FazilDrama)."
            }
            channelId = resolveChannelId(context, apiKey, handle)
                ?: return "ERROR: '$handle' naam ka channel nahi mila — handle check karo."
        }

        val ch = get("$BASE/channels?part=statistics,snippet,contentDetails&id=$channelId&key=$apiKey")
            ?.optJSONArray("items")?.optJSONObject(0)
            ?: return "ERROR: channel data nahi mila — API key galat ho sakti hai ya quota khatam."
        val stats = ch.optJSONObject("statistics")
        val channelTitle = ch.optJSONObject("snippet")?.optString("title") ?: "?"
        val subs = stats?.optString("subscriberCount") ?: "?"
        val totalViews = stats?.optString("viewCount") ?: "?"
        val uploadsId = ch.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists")?.optString("uploads")
        if (uploadsId.isNullOrBlank()) return "ERROR: channel ki uploads list nahi mili."

        val pl = get("$BASE/playlistItems?part=snippet,contentDetails&playlistId=$uploadsId&maxResults=6&key=$apiKey")
            ?.optJSONArray("items")
            ?: return "ERROR: videos ki list nahi mili."
        data class V(val id: String, val title: String, val published: String)
        val vids = mutableListOf<V>()
        for (i in 0 until pl.length()) {
            val item = pl.optJSONObject(i) ?: continue
            val vid = item.optJSONObject("contentDetails")?.optString("videoId") ?: continue
            if (vid.isBlank()) continue
            val sn = item.optJSONObject("snippet")
            vids.add(
                V(
                    vid,
                    sn?.optString("title") ?: "?",
                    sn?.optString("publishedAt")?.take(10) ?: "?"
                )
            )
        }
        if (vids.isEmpty()) return "ERROR: channel pe koi video nahi mili."

        val ids = vids.joinToString(",") { it.id }
        val vst = get("$BASE/videos?part=statistics&id=$ids&key=$apiKey")
            ?.optJSONArray("items")
        val views = mutableListOf<Long>()
        val likes = mutableListOf<Long>()
        val comments = mutableListOf<Long>()
        for (i in 0 until (vst?.length() ?: 0)) {
            val st = vst?.optJSONObject(i)?.optJSONObject("statistics")
            views.add(st?.optString("viewCount")?.toLongOrNull() ?: 0L)
            likes.add(st?.optString("likeCount")?.toLongOrNull() ?: 0L)
            comments.add(st?.optString("commentCount")?.toLongOrNull() ?: 0L)
        }

        val sb = StringBuilder()
        sb.appendLine("CHANNEL: $channelTitle | Subs: $subs | Total views: $totalViews")
        val latest = vids[0]
        sb.appendLine(
            "LATEST VIDEO: \"${latest.title}\" (${latest.published}) — " +
                    "Views: ${views.getOrNull(0) ?: 0}, " +
                    "Likes: ${likes.getOrNull(0) ?: 0}, " +
                    "Comments: ${comments.getOrNull(0) ?: 0}"
        )
        if (views.size >= 3) {
            val prevAvg = views.drop(1).average()
            val pct = if (prevAvg > 0) ((views[0] - prevAvg) / prevAvg * 100).toInt() else 0
            val dir = if (pct >= 0) "UPAR" else "NEECHE"
            sb.appendLine(
                "PERFORMANCE: latest video pichli ${views.size - 1} videos ke average " +
                        "(${prevAvg.toLong()} views) se $pct% $dir hai."
            )
        }
        sb.appendLine("PICHLI VIDEOS:")
        for (i in 1 until minOf(vids.size, 5)) {
            sb.appendLine("- \"${vids[i].title}\" — ${views.getOrNull(i) ?: 0} views")
        }
        return sb.toString().trim()
    }
}
