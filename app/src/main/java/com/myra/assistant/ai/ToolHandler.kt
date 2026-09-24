package com.myra.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import com.myra.assistant.service.MyraAccessibilityService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes function-call tool requests from the Gemini Live session
 * against the Android device. Returns a short "OK: ..." / "ERROR: ..." string.
 */
object ToolHandler {

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
                    if (MyraAccessibilityService.clickOnText(args.optString("text"))) "OK: tapped"
                    else "ERROR: text not found"
                "input_text" ->
                    if (MyraAccessibilityService.inputText(args.optString("text"))) "OK: typed"
                    else "ERROR: no input field"
                "scroll_screen" ->
                    if (MyraAccessibilityService.scrollForward()) "OK: scrolled"
                    else "ERROR: cannot scroll"
                "get_current_time" ->
                    "OK: " + SimpleDateFormat("HH:mm, d MMM yyyy", Locale.getDefault()).format(Date())
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
