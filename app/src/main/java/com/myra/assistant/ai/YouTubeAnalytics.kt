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

    private data class V(val id: String, val title: String, val published: String)

    /** Latest uploaded videos (newest first). Empty list on any failure. */
    private fun fetchLatestVideos(apiKey: String, channelId: String): List<V> {
        val uploadsId = get("$BASE/channels?part=contentDetails&id=$channelId&key=$apiKey")
            ?.optJSONArray("items")?.optJSONObject(0)
            ?.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists")?.optString("uploads")
        if (uploadsId.isNullOrBlank()) return emptyList()
        val pl = get("$BASE/playlistItems?part=snippet,contentDetails&playlistId=$uploadsId&maxResults=6&key=$apiKey")
            ?.optJSONArray("items") ?: return emptyList()
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
        return vids
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

        val vids = fetchLatestVideos(apiKey, channelId)
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

    // ---------------- Phase 2: private analytics (OAuth, read-only) ----------------

    private const val YTA = "https://youtubeanalytics.googleapis.com"

    private fun analyticsQuery(
        context: Context,
        channelId: String,
        startDate: String,
        endDate: String,
        metrics: String,
        dimensions: String? = null,
        filters: String? = null,
        sort: String? = null
    ): JSONObject? {
        val b = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("youtubeanalytics.googleapis.com")
            .addPathSegments("v2/reports")
            .addQueryParameter("ids", "channel==$channelId")
            .addQueryParameter("startDate", startDate)
            .addQueryParameter("endDate", endDate)
            .addQueryParameter("metrics", metrics)
        if (!dimensions.isNullOrBlank()) b.addQueryParameter("dimensions", dimensions)
        if (!filters.isNullOrBlank()) b.addQueryParameter("filters", filters)
        if (!sort.isNullOrBlank()) b.addQueryParameter("sort", sort)
        return YouTubeOAuth.authedGet(context, b.build().toString())
    }

    /** Turns a reports.query response into name->value maps, one per row. */
    private fun rowsOf(json: JSONObject?): List<Map<String, String>> {
        if (json == null) return emptyList()
        val headers = json.optJSONArray("columnHeaders") ?: return emptyList()
        val names = (0 until headers.length()).map { headers.optJSONObject(it)?.optString("name") ?: "?" }
        val rows = json.optJSONArray("rows") ?: return emptyList()
        val out = mutableListOf<Map<String, String>>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val m = mutableMapOf<String, String>()
            for (j in names.indices) m[names[j]] = r.opt(j)?.toString() ?: "0"
            out.add(m)
        }
        return out
    }

    private fun dateRange(): Pair<String, String> {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val end = sdf.format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -27)
        val start = sdf.format(cal.time)
        return start to end
    }

    private val TRAFFIC_LABELS = mapOf(
        "BROWSE" to "Browse (home feed)",
        "SUGGESTED" to "Suggested videos",
        "YT_SEARCH" to "YouTube search",
        "EXT_URL" to "Bahir ki websites",
        "NOTIFICATION" to "Notifications",
        "PLAYLIST" to "Playlists",
        "END_SCREEN" to "End screens",
        "CHANNEL" to "Channel page",
        "SUBSCRIBED" to "Subscriptions feed",
        "HASHTAGS" to "Hashtags",
        "YT_OTHER_PAGE" to "Doosre YouTube pages"
    )

    /**
     * Deep private analytics for the latest video: retention, watch time,
     * traffic sources, subs gained/lost, and CTR (if the API returns it).
     */
    fun analyzeAdvanced(context: Context): String {
        if (!YouTubeOAuth.isLinked(context)) {
            return "ERROR: YouTube login nahi hua — user se kaho 'youtube login karo' bole."
        }
        val apiKey = YouTubeStore.getApiKey(context)
        if (apiKey.isBlank()) {
            return "ERROR: YouTube API key save nahi hai."
        }
        var channelId = YouTubeStore.getChannelId(context)
        if (channelId.isBlank()) {
            val handle = YouTubeStore.getHandle(context)
            channelId = if (handle.isNotBlank()) {
                resolveChannelId(context, apiKey, handle) ?: return "ERROR: channel nahi mila."
            } else {
                return "ERROR: channel handle nahi pata."
            }
        }
        val vids = fetchLatestVideos(apiKey, channelId)
        if (vids.isEmpty()) return "ERROR: koi video nahi mili."
        val latest = vids[0]
        val (start, end) = dateRange()

        // 1) Core KPIs for the latest video
        val kpi = rowsOf(
            analyticsQuery(
                context, channelId, start, end,
                metrics = "views,estimatedMinutesWatched,averageViewDuration," +
                        "averageViewPercentage,likes,comments,shares," +
                        "subscribersGained,subscribersLost",
                filters = "video==${latest.id}"
            )
        ).firstOrNull() ?: return "ERROR: analytics data nahi mila — dobara try karo."

        fun num(key: String): Long = kpi[key]?.toDoubleOrNull()?.toLong() ?: 0L
        val views = num("views")
        val watchHours = num("estimatedMinutesWatched") / 60
        val avgDur = kpi["averageViewDuration"]?.toDoubleOrNull()?.toInt() ?: 0
        val avgPct = kpi["averageViewPercentage"]?.toDoubleOrNull()?.toInt() ?: 0

        val sb = StringBuilder()
        sb.appendLine("FULL ANALYTICS (pichle 28 din) — \"${latest.title}\":")
        sb.appendLine(
            "Views: $views | Watch time: $watchHours ghante | " +
                    "Avg dekha: ${avgDur}s (video ka $avgPct%)"
        )
        sb.appendLine(
            "RETENTION: log average video ka $avgPct% dekhte hain. " +
                    if (avgPct >= 50) "Bohat aala retention hai!"
                    else if (avgPct >= 35) "Theek hai, behtar ho sakta hai."
                    else "Retention kam hai — shuru ke 30 second mazboot karo."
        )
        sb.appendLine(
            "ENGAGEMENT: Likes ${num("likes")} | Comments ${num("comments")} | " +
                    "Shares ${num("shares")} | Subs: +${num("subscribersGained")}/-${num("subscribersLost")}"
        )

        // 2) Traffic sources
        val traffic = rowsOf(
            analyticsQuery(
                context, channelId, start, end,
                metrics = "views",
                dimensions = "insightTrafficSourceType",
                filters = "video==${latest.id}",
                sort = "-views"
            )
        )
        if (traffic.isNotEmpty()) {
            val total = traffic.sumOf { it["views"]?.toDoubleOrNull() ?: 0.0 }.coerceAtLeast(1.0)
            val top = traffic.take(3).map { row ->
                val src = row["insightTrafficSourceType"] ?: "?"
                val v = row["views"]?.toDoubleOrNull() ?: 0.0
                val pct = (v / total * 100).toInt()
                "${TRAFFIC_LABELS[src] ?: src} $pct%"
            }
            sb.appendLine("TRAFFIC: sab se zyada views — ${top.joinToString(", ")}.")
        }

        // 3) CTR / impressions (best effort — har channel pe API nahi deta)
        val ctrRow = rowsOf(
            analyticsQuery(
                context, channelId, start, end,
                metrics = "impressions,impressionClickThroughRate",
                filters = "video==${latest.id}"
            )
        ).firstOrNull()
        if (ctrRow != null) {
            val imp = ctrRow["impressions"]?.toDoubleOrNull()?.toLong() ?: 0L
            val ctr = ctrRow["impressionClickThroughRate"]?.toDoubleOrNull()
            if (imp > 0 && ctr != null) {
                val pct = String.format(java.util.Locale.US, "%.1f", ctr * 100)
                sb.appendLine(
                    "CTR: $pct% (thumbnail $imp baar dikhaya gaya). " +
                            if (ctr * 100 >= 4) "Thumbnail zabardast chal raha hai!"
                            else "Thumbnail behtar banao — 4% se upar hona chahiye."
                )
            }
        } else {
            sb.appendLine(
                "CTR: is waqt API se nahi mil raha — YouTube Studio app se dekh lo."
            )
        }
        return sb.toString().trim()
    }
}
