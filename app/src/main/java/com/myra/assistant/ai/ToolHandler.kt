package com.myra.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import com.myra.assistant.service.MyraAccessibilityService
import com.myra.assistant.service.ScreenShareService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes function-call tool requests from the Gemini Live session
 * against the Android device. Returns a short "OK: ..." / "ERROR: ..." string.
 */
object ToolHandler {

    private const val A11Y_OFF =
        "ERROR: MYRA Accessibility service is OFF — tell the user sweetly to turn it ON " +
                "in phone Settings > Accessibility > MYRA, then try again"

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
                else -> "ERROR: unknown tool $name"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

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
        val key = appName.lowercase(Locale.US)
        var pkg: String? = map[key]
        if (pkg == null) {
            val pm = context.packageManager
            for (app in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.lowercase(Locale.US).contains(key)) {
                    pkg = app.packageName
                    break
                }
            }
        }
        if (pkg == null) return "ERROR: app not found: $appName"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "ERROR: cannot launch $appName"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "OK: opened $appName"
    }

    private fun searchYoutube(query: String, context: Context): String {
        if (query.isBlank()) return "ERROR: empty query"
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
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
