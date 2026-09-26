package com.myra.assistant.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.telephony.SmsManager
import com.myra.assistant.service.MyraAccessibilityService
import com.myra.assistant.service.ScreenShareService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Executes function-call tool requests from the Gemini Live session
 * against the Android device. Returns a short "OK: ..." / "ERROR: ..." string.
 */
object ToolHandler {

    private const val A11Y_OFF =
        "ERROR: MYRA Accessibility service is OFF — tell the user sweetly to turn it ON " +
                "in phone Settings > Accessibility > MYRA, then try again"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

    private fun isA11yOn(): Boolean = MyraAccessibilityService.instance != null

    fun execute(name: String, args: JSONObject, context: Context): String {
        return try {
            when (name) {
                "open_app" -> openApp(args.optString("app_name"), context)
                "search_youtube" -> searchYoutube(args.optString("query"), context)
                "make_call" -> makeCall(args.optString("phone_number"), context)
                "send_sms" -> sendSms(
                    args.optString("phone_number"),
                    args.optString("message"),
                    context
                )
                "answer_call" -> answerCall(context)
                "reject_call" -> rejectCall(context)
                "set_camera_access" -> {
                    val on = args.optBoolean("enabled", false)
                    CameraVision.setEnabled(context, on)
                    if (on) "OK: camera access ON — front camera vision active while the voice session is live"
                    else "OK: camera access OFF — camera closed"
                }
                "tap_text" ->
                    if (!isA11yOn()) A11Y_OFF
                    else if (MyraAccessibilityService.clickOnText(args.optString("text"))) "OK: tapped"
                    else "ERROR: text not found on screen"
                "tap_at" -> {
                    if (!isA11yOn()) A11Y_OFF
                    else {
                        val x = args.optDouble("x", -1.0).toInt()
                        val y = args.optDouble("y", -1.0).toInt()
                        if (x in 0..1000 && y in 0..1000 &&
                            MyraAccessibilityService.tapAt(x, y)
                        ) "OK: tapped at $x,$y"
                        else "ERROR: tap gesture failed"
                    }
                }
                "swipe" -> {
                    if (!isA11yOn()) A11Y_OFF
                    else {
                        val x1 = args.optDouble("x1", -1.0).toInt()
                        val y1 = args.optDouble("y1", -1.0).toInt()
                        val x2 = args.optDouble("x2", -1.0).toInt()
                        val y2 = args.optDouble("y2", -1.0).toInt()
                        if (x1 in 0..1000 && y1 in 0..1000 && x2 in 0..1000 && y2 in 0..1000 &&
                            MyraAccessibilityService.swipe(x1, y1, x2, y2)
                        ) "OK: swiped"
                        else "ERROR: swipe gesture failed"
                    }
                }
                "press_back" ->
                    if (!isA11yOn()) A11Y_OFF
                    else if (MyraAccessibilityService.pressBack()) "OK: back pressed"
                    else "ERROR: back failed"
                "can_see_screen" ->
                    if (ScreenShareService.isSharing) "OK: you can see the user's screen"
                    else "ERROR: screen share is OFF"
                "get_screen_elements" ->
                    if (!isA11yOn()) A11Y_OFF
                    else "OK:\n" + MyraAccessibilityService.getScreenElements()
                "input_text" ->
                    if (!isA11yOn()) A11Y_OFF
                    else if (MyraAccessibilityService.inputText(args.optString("text"))) "OK: typed"
                    else "ERROR: no input field focused"
                "scroll_screen" ->
                    if (!isA11yOn()) A11Y_OFF
                    else if (MyraAccessibilityService.scroll(args.optString("direction", "down"))) "OK: scrolled"
                    else "ERROR: cannot scroll"
                "get_current_time" ->
                    "OK: " + SimpleDateFormat("HH:mm, d MMM yyyy", Locale.getDefault()).format(Date())
                "save_user_name" ->
                    {
                        val n = args.optString("name", "").trim()
                        if (n.isEmpty()) "ERROR: no name given"
                        else {
                            com.myra.assistant.util.Prefs.userName = n
                            "OK: name saved as $n"
                        }
                    }
                "web_search" -> webSearch(args.optString("query"))
                "read_webpage" -> readWebpage(args.optString("url"))
                "open_website" -> openWebsite(args.optString("url"), context)
                "get_weather" -> getWeather(args.optString("location"))
                "save_script" -> saveScript(
                    args.optString("title"),
                    args.optString("script"),
                    context
                )
                else -> "ERROR: unknown tool $name"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // ---------------- HTTP helper ----------------

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string() ?: throw Exception("empty body")
        }
    }

    // ---------------- web_search ----------------

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .trim()

    private fun realUrl(href: String): String {
        return try {
            if (href.contains("uddg=")) {
                URLDecoder.decode(href.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            } else href
        } catch (_: Exception) {
            href
        }
    }

    private fun webSearch(query: String): String {
        if (query.isBlank()) return "ERROR: empty query"
        return try {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val html = httpGet(url)
            val titles = Regex(
                """class="result__a"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(html).map { stripTags(it.groupValues[1]) }.toList()
            val links = Regex(
                """class="result__a"[^>]*href="([^"]+)""""
            ).findAll(html).map { realUrl(it.groupValues[1]) }.toList()
            val snippets = Regex(
                """class="result__snippet"[^>]*>(.*?)</div>""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(html).map { stripTags(it.groupValues[1]) }.toList()
            if (titles.isEmpty()) return "ERROR: no results found for '$query'"
            val sb = StringBuilder("OK: top web results for '$query':\n")
            val n = minOf(5, titles.size)
            for (i in 0 until n) {
                sb.append("${i + 1}. ${titles[i]}\n")
                val snip = snippets.getOrNull(i) ?: ""
                if (snip.isNotBlank()) sb.append("   $snip\n")
                val link = links.getOrNull(i) ?: ""
                if (link.isNotBlank()) sb.append("   $link\n")
            }
            sb.toString().take(3000)
        } catch (e: Exception) {
            "ERROR: search failed: ${e.message}"
        }
    }

    // ---------------- read_webpage ----------------

    private fun readWebpage(url: String): String {
        if (url.isBlank()) return "ERROR: empty url"
        return try {
            var u = url.trim()
            if (!u.startsWith("http")) u = "https://$u"
            val html = httpGet(u)
            var text = html
                .replace(Regex("(?s)<script.*?</script>"), " ")
                .replace(Regex("(?s)<style.*?</style>"), " ")
            text = stripTags(text)
                .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            if (text.isEmpty()) return "ERROR: no readable text on page"
            "OK: page text (pehla hissa):\n" + text.take(3500)
        } catch (e: Exception) {
            "ERROR: cannot read page: ${e.message}"
        }
    }

    // ---------------- open_website ----------------

    private fun openWebsite(url: String, context: Context): String {
        if (url.isBlank()) return "ERROR: empty url"
        return try {
            var u = url.trim()
            if (!u.startsWith("http")) u = "https://$u"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "OK: opened $u in the browser — tell the user it is open on his screen"
        } catch (e: Exception) {
            "ERROR: cannot open website: ${e.message}"
        }
    }

    // ---------------- get_weather ----------------

    private fun weatherWord(code: Int): String = when (code) {
        0 -> "bilkul saaf aasman"
        1 -> "halka saaf"
        2 -> "halki badalchhai"
        3 -> "gahre baadal"
        45, 48 -> "dhund/kohra"
        51, 53, 55 -> "halki boonda-bandi"
        56, 57 -> "jamne wali boonda-bandi"
        61 -> "halki barish"
        63 -> "barish"
        65 -> "tez barish"
        66, 67 -> "jamne wali barish"
        71, 73, 75, 77 -> "barf-bari"
        80, 81, 82 -> "bauchhar"
        85, 86 -> "barf ke bauchhar"
        95 -> "garaj-chamak"
        96, 99 -> "olay ke saath toofan"
        else -> "mausam"
    }

    private fun getWeather(location: String): String {
        if (location.isBlank()) return "ERROR: empty location"
        return try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(location, "UTF-8") + "&count=1&language=en&format=json"
            val geo = JSONObject(httpGet(geoUrl))
            val arr = geo.optJSONArray("results")
                ?: return "ERROR: sheher nahi mila: $location"
            if (arr.length() == 0) return "ERROR: sheher nahi mila: $location"
            val g = arr.getJSONObject(0)
            val lat = g.getDouble("latitude")
            val lon = g.getDouble("longitude")
            val place = g.optString("name") +
                    (g.optString("country").let { if (it.isNotBlank()) ", $it" else "" })
            val wUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1"
            val root = JSONObject(httpGet(wUrl))
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val temp = cur.optDouble("temperature_2m", Double.NaN)
            val code = cur.optInt("weather_code", -1)
            val hum = cur.optInt("relative_humidity_2m", -1)
            val wind = cur.optDouble("wind_speed_10m", Double.NaN)
            val max = daily.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
            val min = daily.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
            "OK: $place — abhi ${temp.toInt()}°C, ${weatherWord(code)}; " +
                    "aaj max ${max.toInt()}°C / min ${min.toInt()}°C; " +
                    "barish ka imkaan $rain%; nami $hum%; hawa ${wind.toInt()} km/h."
        } catch (e: Exception) {
            "ERROR: weather failed: ${e.message}"
        }
    }

    // ---------------- save_script ----------------

    private fun saveScript(title: String, script: String, context: Context): String {
        if (script.isBlank()) return "ERROR: empty script"
        val safe = title.ifBlank { "myra_script" }
            .replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
        val fileName = "$safe.txt"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MYRA")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "ERROR: cannot create file"
                resolver.openOutputStream(uri)?.use {
                    it.write(script.toByteArray(Charsets.UTF_8))
                } ?: return "ERROR: cannot write file"
                "OK: script saved to Downloads/MYRA/$fileName — tell the user to open it from the Files app"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MYRA"
                )
                dir.mkdirs()
                File(dir, fileName).writeText(script, Charsets.UTF_8)
                "OK: script saved to Downloads/MYRA/$fileName — tell the user to open it from the Files app"
            }
        } catch (e: Exception) {
            "ERROR: save failed: ${e.message}"
        }
    }

    // ---------------- existing phone tools ----------------

    private fun openApp(appName: String, context: Context): String {
        val map = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "play store" to "com.android.vending",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana"
        )
        val key = appName.lowercase(Locale.US).trim()
        if (key.isEmpty()) return "ERROR: empty app name"
        // 1. Known apps: exact map hit
        map[key]?.let { return launchPkg(it, appName, context) }
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        // 2. Exact label match (e.g. user said "Camera", app is "Camera")
        val exact = apps.filter {
            pm.getApplicationLabel(it).toString().equals(key, ignoreCase = true)
        }
        if (exact.size == 1) return launchPkg(exact[0].packageName, appName, context)
        // 3. Partial matches: open only when there is exactly ONE candidate,
        // otherwise ask the user instead of opening the wrong app.
        val partial = apps.filter {
            pm.getApplicationLabel(it).toString().lowercase(Locale.US).contains(key)
        }
        if (partial.size == 1) return launchPkg(partial[0].packageName, appName, context)
        if (partial.size > 1) {
            val names = partial.take(5).joinToString(", ") {
                pm.getApplicationLabel(it).toString()
            }
            return "ASK: '$appName' se milti-julti ye apps hain: $names — user se poocho kaunsi kholni hai, phir uska poora naam lekar dobara open_app call karo"
        }
        return "ERROR: app not found: $appName"
    }

    private fun launchPkg(pkg: String, appName: String, context: Context): String {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "ERROR: cannot launch $appName"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "OK: opened $appName"
    }

    private fun searchYoutube(query: String, context: Context): String {
        if (query.isBlank()) return "ERROR: empty query"
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            try {
                // Prefer the YouTube app
                val appIntent = Intent(Intent.ACTION_VIEW, uri)
                appIntent.setPackage("com.google.android.youtube")
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(appIntent)
            } catch (_: Exception) {
                // Fall back to the browser
                val webIntent = Intent(Intent.ACTION_VIEW, uri)
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webIntent)
            }
            "OK: YouTube search opened for '$query'"
        } catch (e: Exception) {
            "ERROR: cannot search YouTube: ${e.message}"
        }
    }

    private fun makeCall(number: String, context: Context): String {
        if (number.isBlank()) return "ERROR: empty number"
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "OK: calling $number"
    }

    private fun sendSms(number: String, message: String, context: Context): String {
        if (number.isBlank() || message.isBlank()) return "ERROR: empty number/message"
        val sm = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sm.divideMessage(message)
        sm.sendMultipartTextMessage(number, null, parts, null, null)
        return "OK: sms sent"
    }

    private fun answerCall(context: Context): String {
        return try {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            "OK: answered"
        } catch (e: Exception) {
            "ERROR: cannot answer: ${e.message}"
        }
    }

    private fun rejectCall(context: Context): String {
        return try {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            "OK: rejected"
        } catch (e: Exception) {
            "ERROR: cannot reject: ${e.message}"
        }
    }
}
