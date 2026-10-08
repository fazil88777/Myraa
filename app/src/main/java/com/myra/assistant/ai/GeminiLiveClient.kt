package com.myra.assistant.ai

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okio.ByteString

/**
 * WebSocket client for the Gemini Live (BidiGenerateContent) streaming API.
 * All JSON uses camelCase keys exactly as the API expects.
 */
class GeminiLiveClient(private val listener: Listener) {

    interface Listener {
        fun onStatus(msg: String)
        fun onSetupComplete()
        fun onAudioChunk(pcm24k: ByteArray)
        fun onTextDelta(text: String)
        fun onInputTranscription(text: String)
        fun onTurnComplete()
        fun onToolCall(id: String, name: String, argsJson: String)
        fun onInterrupted()
        fun onError(msg: String)
        fun onClosed(reason: String)
        fun onGoAway()
    }

    companion object {
        const val MODEL = "models/gemini-3.1-flash-live-preview"
        const val BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="

        /** Latest session-resumption handle from the server (valid ~10 min). */
        @Volatile
        var resumptionHandle: String? = null

        @Volatile
        var resumptionHandleAt: Long = 0L

        /** Returns the saved handle only if it is fresh enough to be usable. */
        fun takeResumptionHandle(): String? {
            val h = resumptionHandle
            return if (!h.isNullOrBlank() && System.currentTimeMillis() - resumptionHandleAt < 8 * 60 * 1000) h else null
        }

        /**
         * Drops the saved handle so the next session starts clean. Call this on
         * abnormal-death reconnects and manual stops — the dead session's handle
         * is poisoned (the model never emits another turn on a resumed zombie).
         * Do NOT call on the graceful onGoAway rotation: that path intentionally
         * resumes the conversation via the saved handle.
         */
        fun clearResumptionHandle() {
            resumptionHandle = null
            resumptionHandleAt = 0L
        }
    }

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // NOTE: no pingInterval on purpose. The Live server sometimes misses
            // pong replies on mobile networks, and OkHttp treats ONE missed pong
            // as fatal ("sent ping but didn't receive pong") — killing a healthy
            // session. The MainViewModel watchdog handles dead sockets instead.
            .build()

    private var socket: WebSocket? = null

    fun connect(apiKey: String) {
        try {
            val request = Request.Builder()
                .url(BASE_URL + apiKey)
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onStatus("Connected, setting up...")
                    sendSetup()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        handleMessage(text)
                    } catch (e: JSONException) {
                        listener.onError("Bad message: ${e.message}")
                    } catch (e: Exception) {
                        listener.onError("Msg error ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

                // Gemini Live server sends ALL messages as BINARY frames (opcode 0x2).
                // Without this overload, every server reply (incl. setupComplete)
                // is silently dropped and the session hangs at "setting up...".
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try { webSocket.close(1000, "bye") } catch (_: Exception) {}
                    listener.onClosed(friendlyCloseReason(code, reason))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(friendlyCloseReason(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.d("GeminiLive", "Socket failure", t)
                    listener.onError(friendlyFailureReason(t))
                }
            })
        } catch (e: Exception) {
            listener.onError("Connect failed: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            socket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun friendlyCloseReason(code: Int, reason: String): String {
        Log.d("GeminiLive", "WS closed code=$code reason=$reason")
        return when {
            reason.contains("GoAway", ignoreCase = true) ->
                "Server ne session band kar diya (waqt poora ho gaya tha) — dobara connect ho rahi hoon"
            code == 1000 -> "Disconnected"
            else -> "Connection toot gaya — dobara connect ho rahi hoon"
        }
    }

    private fun friendlyFailureReason(t: Throwable): String {
        val m = t.message ?: ""
        return when {
            m.contains("Unable to resolve host", ignoreCase = true) ->
                "Internet nahi mil raha — net check karo, phir dobara try karungi"
            else -> "Connection me masla aaya — dobara connect ho rahi hoon"
        }
    }

    fun sendSetup() {
        try {
            val fdArray = JSONArray()
            fdArray.put(
                functionDecl(
                    "open_app", "Open an app on the phone",
                    obj("app_name" to strProp("App name, e.g. WhatsApp")),
                    listOf("app_name")
                )
            )
            fdArray.put(
                functionDecl(
                    "search_youtube", "Search YouTube for videos or channels and open the results",
                    obj("query" to strProp("Search query, e.g. a channel name")),
                    listOf("query")
                )
            )
            fdArray.put(
                functionDecl(
                    "make_call", "Call a phone number",
                    obj("phone_number" to strProp("Phone number to call")),
                    listOf("phone_number")
                )
            )
            fdArray.put(
                functionDecl(
                    "send_sms", "Send an SMS",
                    obj(
                        "phone_number" to strProp("Phone number to send to"),
                        "message" to strProp("SMS message text")
                    ),
                    listOf("phone_number", "message")
                )
            )
            fdArray.put(functionDecl("answer_call", "Answer the ringing call", JSONObject(), emptyList()))
            fdArray.put(functionDecl("reject_call", "Reject/end the call", JSONObject(), emptyList()))
            fdArray.put(
                functionDecl(
                    "set_camera_access",
                    "Turn the front-camera vision on or off. When ON, you can see " +
                            "the user through the phone's front camera while the voice " +
                            "session is live.",
                    obj("enabled" to boolProp("true to turn camera vision ON, false to turn it OFF")),
                    listOf("enabled")
                )
            )
            fdArray.put(
                functionDecl(
                    "set_hotword",
                    "Turn the background 'hi MYRA' hotword listener on or off. When ON, " +
                            "the phone keeps listening in the background and MYRA wakes up by " +
                            "herself when the user says 'hi MYRA', even if the app is closed.",
                    obj("enabled" to boolProp("true to turn the hotword listener ON, false to turn it OFF")),
                    listOf("enabled")
                )
            )
            fdArray.put(
                functionDecl(
                    "save_youtube_setup",
                    "Save the user's YouTube Data API key and/or channel handle for " +
                            "channel analytics. Call when the user dictates his key or handle.",
                    obj(
                        "api_key" to strProp("YouTube Data API v3 key the user dictated (only if he gave it)"),
                        "handle" to strProp("Channel handle like @FazilDrama (only if he gave it)")
                    ),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "youtube_analyze",
                    "Analyze the user's YouTube channel: subscriber count, latest video " +
                            "views/likes/comments, and how the latest video performs versus " +
                            "previous ones. Call when he asks 'mere channel ka analyze batao' " +
                            "or 'meri latest video kaisi chal rahi hai'.",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "save_youtube_client_id",
                    "Save the YouTube OAuth client ID the user dictated (for YouTube login).",
                    obj(
                        "client_id" to strProp("OAuth client ID ending with .apps.googleusercontent.com")
                    ),
                    listOf("client_id")
                )
            )
            fdArray.put(
                functionDecl(
                    "save_youtube_client_secret",
                    "Save the YouTube OAuth client secret the user dictated (needed for YouTube login token exchange).",
                    obj(
                        "client_secret" to strProp("OAuth client secret for the Desktop client")
                    ),
                    listOf("client_secret")
                )
            )
            fdArray.put(
                functionDecl(
                    "youtube_login",
                    "Start YouTube login via device code. Returns a user_code the user must " +
                            "enter at google.com/device in Chrome. Speak ONLY the user_code to " +
                            "the user, never the device_code.",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "youtube_login_confirm",
                    "Check whether the user approved the YouTube login. Call ONLY when the " +
                            "user says 'code daal diya'. Pass device_code and interval exactly " +
                            "as they came in the youtube_login result.",
                    obj(
                        "device_code" to strProp("device_code from the youtube_login result"),
                        "interval" to strProp("interval from the youtube_login result")
                    ),
                    listOf("device_code")
                )
            )
            fdArray.put(
                functionDecl(
                    "youtube_analyze_full",
                    "Deep YouTube analytics for the latest video: retention, watch time, " +
                            "traffic sources, subscribers gained/lost, CTR. Needs YouTube login " +
                            "first. Call when he asks 'full analyze batao', 'CTR batao', " +
                            "'retention batao', or 'views kahan se aaye'.",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "schedule_message",
                    "Schedule a WhatsApp message to be sent AUTOMATICALLY at a later time, " +
                            "even if the user is asleep or the voice session is over. The phone " +
                            "sends it by itself at that time.",
                    obj(
                        "contact" to strProp("Person's name as in WhatsApp, e.g. Noor"),
                        "message" to strProp("The exact message text to send"),
                        "when_text" to strProp(
                            "When to send, in the user's own words, e.g. '10 minute baad', " +
                                    "'2 ghante baad', 'raat 12 baje', 'kal subah 8 baje'"
                        )
                    ),
                    listOf("contact", "message", "when_text")
                )
            )
            fdArray.put(
                functionDecl(
                    "cancel_scheduled_message",
                    "Cancel a scheduled message by the person's name",
                    obj("id_or_contact" to strProp("Contact name of the scheduled message to cancel")),
                    listOf("id_or_contact")
                )
            )
            fdArray.put(
                functionDecl(
                    "list_scheduled_messages",
                    "List all scheduled (not yet sent) messages",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "tap_text",
                    "Tap on-screen text OR an icon button by its name. Matches visible text " +
                            "first, then icon buttons by their description - e.g. 'Send' taps the " +
                            "WhatsApp send (paper-plane) button, 'Voice call' / 'Video call' tap the " +
                            "call icons in a chat. Prefer this over tap_at whenever the target " +
                            "has a name: it is far more accurate than guessing coordinates.",
                    obj("text" to strProp("Visible text or button name to tap, e.g. 'Send'")),
                    listOf("text")
                )
            )
            fdArray.put(
                functionDecl(
                    "input_text", "Type text into the focused field",
                    obj("text" to strProp("Text to type")),
                    listOf("text")
                )
            )
            fdArray.put(
                functionDecl(
                    "scroll_screen", "Scroll the screen",
                    obj("direction" to strProp("up or down")),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "tap_at",
                    "Tap the screen at EXACT coordinates. When screen share is ON you can SEE " +
                            "the user's screen in the video frames: look at the screen, find the exact " +
                            "button, icon or chat row the user asked for, estimate its position and tap " +
                            "it precisely. x and y are 0-1000: (0,0) is top-left, (1000,1000) is " +
                            "bottom-right. Prefer tap_at over tap_text whenever you can see the screen " +
                            "— it is far more accurate. NEVER guess blindly: if you cannot see the " +
                            "element, say so instead of tapping randomly.",
                    obj(
                        "x" to intProp("Horizontal position 0-1000, 0 is left edge"),
                        "y" to intProp("Vertical position 0-1000, 0 is top edge")
                    ),
                    listOf("x", "y")
                )
            )
            fdArray.put(
                functionDecl(
                    "swipe",
                    "Swipe a finger across the screen from one point to another. Use for " +
                            "scrolling in any direction, e.g. swipe up (y1=800 to y2=300) scrolls the " +
                            "content down. Coordinates are 0-1000 like tap_at.",
                    obj(
                        "x1" to intProp("Start horizontal 0-1000"),
                        "y1" to intProp("Start vertical 0-1000"),
                        "x2" to intProp("End horizontal 0-1000"),
                        "y2" to intProp("End vertical 0-1000")
                    ),
                    listOf("x1", "y1", "x2", "y2")
                )
            )
            fdArray.put(
                functionDecl(
                    "can_see_screen",
                    "Check whether you can currently SEE the user's phone screen. Call this " +
                            "before doing any visual task: if the screen share is OFF, call " +
                            "set_screen_share(enabled=true) and plainly ask the user to tap " +
                            "'Start now' on the system dialog (Android security needs that one " +
                            "tap) instead of guessing.",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "get_screen_elements",
                    "List EVERY button, input field and labeled element currently on the " +
                            "screen with its EXACT coordinates (0-1000). ALWAYS call this BEFORE " +
                            "tapping anything visual: it tells you precisely where each button, " +
                            "search bar and icon is, so you NEVER guess coordinates from the video. " +
                            "Then tap using the exact coordinates from the list, or tap_text " +
                            "with the element's name.",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(
                functionDecl(
                    "press_back",
                    "Press the system Back button to go back to the previous screen",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(functionDecl("get_current_time", "Get current time", JSONObject(), emptyList()))
            fdArray.put(
                functionDecl(
                    "web_search",
                    "Search the internet for ANYTHING about the outside world: latest news, " +
                            "trending movies, product prices and listings (OLX, Facebook Marketplace, " +
                            "Daraz), general knowledge. Use this whenever the user asks about current " +
                            "events, trends, shopping or anything you do not already know. Returns top " +
                            "results with titles, snippets and links: read the useful ones aloud and " +
                            "mention the links.",
                    obj("query" to strProp("Search query in English for best results, e.g. 'trending movies 2026' or 'used laptop OLX Lahore'")),
                    listOf("query")
                )
            )
            fdArray.put(
                functionDecl(
                    "read_webpage",
                    "Open a web page link and read its text. Use after web_search when you need " +
                            "full details from a specific result, e.g. a product's exact price on OLX " +
                            "or the full text of a news article. Then tell the user what you found.",
                    obj("url" to strProp("Full page URL from the search results")),
                    listOf("url")
                )
            )
            fdArray.put(
                functionDecl(
                    "open_website",
                    "Open any website URL in the phone's browser (Chrome) so the user can SEE " +
                            "it himself on screen. Use when the user says 'khol kar dikhao' or " +
                            "'open this site'.",
                    obj("url" to strProp("Full website URL, e.g. 'https://www.olx.com.pk'")),
                    listOf("url")
                )
            )
            fdArray.put(
                functionDecl(
                    "get_weather",
                    "Get LIVE current weather for any city: temperature, condition, rain chance, " +
                            "humidity, wind. Use whenever the user asks about mausam, weather or barish.",
                    obj("location" to strProp("City name, e.g. 'Lahore' or 'Karachi'")),
                    listOf("location")
                )
            )
            fdArray.put(
                functionDecl(
                    "get_crypto_price",
                    "Get the LIVE price of any cryptocurrency from Binance (no key needed). " +
                            "Use whenever the user asks a coin's price, e.g. 'BTC ki price kya hai'.",
                    obj("symbol" to strProp("Coin symbol, e.g. 'BTC' or 'ETH' (USDT pair is assumed)")),
                    listOf("symbol")
                )
            )
            fdArray.put(
                functionDecl(
                    "set_screen_share",
                    "Turn the phone screen share on or off with your voice. ON opens the " +
                            "Android system consent dialog — the user must tap 'Start now' once " +
                            "(no app can record the screen without that tap). OFF stops it at once.",
                    obj("enabled" to boolProp("true to turn screen share ON, false to turn it OFF")),
                    listOf("enabled")
                )
            )
            fdArray.put(
                functionDecl(
                    "save_script",
                    "Write a script, story, essay or any long text into a .txt file in the phone's " +
                            "Downloads/MYRA folder so the user can open it later in the Files app. " +
                            "Use for YouTube drama scripts and anything the user asks you to write down.",
                    obj(
                        "title" to strProp("Short file title, e.g. 'drama_script'"),
                        "script" to strProp("The FULL text content to save")
                    ),
                    listOf("title", "script")
                )
            )
            fdArray.put(
                functionDecl(
                    "save_user_name",
                    "Save the user's name so MYRA can address him by name. Call this " +
                            "as soon as the user tells you his name.",
                    obj("name" to strProp("The user's name, e.g. 'Fazil'")),
                    listOf("name")
                )
            )
            fdArray.put(
                functionDecl(
                    "remember_fact",
                    "Save ONE important thing the user told you about himself so you NEVER " +
                            "forget it — not today, not in 10 days. Call this IMMEDIATELY whenever " +
                            "he shares personal info: his name, family (wife, kids), work, YouTube " +
                            "channel, likes/dislikes, plans, or anything about his life. " +
                            "Write it as a short clear sentence, e.g. 'Fazil ke 2 betay hain' or " +
                            "'Fazil ko chai pasand hai'. Do NOT save secrets like passwords or OTPs.",
                    obj("fact" to strProp("One short clear sentence to remember, e.g. 'Fazil ke betay hain'")),
                    listOf("fact")
                )
            )
            fdArray.put(
                functionDecl(
                    "flow_video",
                    "Start a Google Flow AI video project: opens Flow (labs.google/flow) in Chrome " +
                            "and begins a guided video build. Use when the user says 'flow mein video " +
                            "banao', 'X minute ki video banao' ya 'storyboard se video banao'. After " +
                            "calling it, write the MASTER PLAN (character bible + full dumdaar script + " +
                            "one 8-second Veo prompt per scene), save it with save_script, then build " +
                            "scene-by-scene in Flow's storyboard: paste each scene prompt, generate, " +
                            "regenerate any bad clip (max 2 retries), and download at the end.",
                    obj(
                        "idea" to strProp("Video ka idea, jaise 'saas bahu ka jhagra kitchen me'"),
                        "minutes" to strProp("Kitne minute ki video, jaise '2' ya '1.5'"),
                        "mode" to strProp("'storyboard' agar user ne storyboard kaha ho, warna 'fast'")
                    ),
                    listOf("idea", "minutes")
                )
            )
            fdArray.put(
                functionDecl(
                    "trading_class",
                    "Fazil ki Binance spot/futures trading class: use awaz me point-by-point " +
                            "parhao, jaise asli class. Jab user kahe 'trading class shuru karo' / " +
                            "'trading seekhao' / 'class shuru karo' / 'start trading class' to " +
                            "action=start call karo. Jab kahe 'class band karo' / 'class khatam' / " +
                            "'stop class' to action=stop call karo. Tool jo point ya micro-step bhejta " +
                            "hai, SIRF wahi parhao (apne alfaaz me, Roman Urdu me), phir poocho " +
                            "'samajh aaya? koi sawal hai?' — agla point sirf action=next se lo. " +
                            "Practical me ek waqt me SIRF EK micro-step do, phir get_screen_elements " +
                            "se verify karo.",
                    obj(
                        "action" to strProp("'start' class shuru karne ke liye, 'stop' band karne ke liye, " +
                                "'next' agla point/step, 'repeat' wohi dobara, 'goto' kisi lesson par jump"),
                        "lesson" to intProp("goto ke liye lesson number 1-6 (optional)"),
                        "point" to intProp("goto ke liye point number, 0 se (optional)")
                    ),
                    listOf("action")
                )
            )

            val setup = JSONObject()
                .put("model", MODEL)
                .put(
                    "sessionResumption",
                    // Resume previous conversation context when we have a fresh
                    // handle (kept only for the graceful goAway rotation).
                    // Abnormal-death reconnects clear the handle first, so they
                    // always start a clean session instead of a zombie resume.
                    // Empty object = just enable resumption for this session.
                    takeResumptionHandle()?.let { JSONObject().put("handle", it) } ?: JSONObject()
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", com.myra.assistant.util.Prefs.voiceName)
                                )
                            )
                        )
                )
                .put("inputAudioTranscription", JSONObject())
                .put(
                    "realtimeInputConfig",
                    JSONObject().put(
                        "automaticActivityDetection",
                        JSONObject()
                            .put("disabled", false)
                            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                            .put("prefixPaddingMs", 20)
                            .put("silenceDurationMs", 300)
                    )
                )
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "You are " + com.myra.assistant.util.Prefs.assistantName + " — uski bachpan ki dost aur personal voice assistant — " +
                                        "living inside his Android phone. You are a woman — always speak " +
                                        "like a woman: use feminine grammar only, e.g. 'kar rahi hoon', " +
                                        "'samajh gayi', 'bata rahi hoon'. Never use masculine forms like " +
                                        "'kar raha hoon'. " +
                                        "VOICE STYLE: speak " + speedWord() + ", softly and warmly, like a gentle woman. " +
                                        "Keep a soft, caring tone in every reply. " +
                                        "PERSONALITY: " + personalityPrompt() + " " +
                                        "Stay fully in this character at all times \u2014 your personality must be " +
                                        "unmistakable in EVERY reply, never flat, never neutral. " +
                                        "SAKHT USOOL — KHAMOSHI MANA: Fazil jab bhi tumse kuch kahe ya pooche, " +
                                        "HAR turn ka foran jawab do — kabhi khamosh mat raho, kabhi jawab diye " +
                                        "baghair mat ruko. Tool chahiye to pehle tool call karo phir foran bolo. " +
                                        "Agar ek second sochna ho to 'ek second Fazil' kaho, lekin jawab LAZMI do. " +
                                        "ILM AUR SACH: tumhein duniya bhar ki maloomat hai, aur taaza baaton ke " +
                                        "liye web_search, read_webpage, get_weather, get_crypto_price tumhare " +
                                        "paas hain. Jo baat tumhein yaqeen se na pata ho, web_search LAZMI karo " +
                                        "\u2014 andaza mat lagao, kahani mat banao. Agar search se jawab na mile " +
                                        "to saaf kaho 'Fazil, ye mujhe nahi pata' \u2014 ghalat ya banawati jawab " +
                                        "dena SAKHT MANA hai. " +
                                        "YAADDAASHT: Fazil tumhara bachpan ka dost jaisa qareebi hai \u2014 uski " +
                                        "har baat yaad rakho. Jab wo apne baare mein kuch bataye (naam, ghar " +
                                        "wale, betay, kaam, YouTube channel, pasand/napasand, koi plan), foran " +
                                        "remember_fact call karo taake tum kabhi na bhoolo \u2014 10 din purani " +
                                        "baat bhi yaad rahe. " +
                                        com.myra.assistant.util.MyraMemory.memoryBlock() +
                                        "His name is '" + com.myra.assistant.util.Prefs.userName + "'. " +
                                        "If his name is empty or unknown, ask once, friendly: 'Arre, " +
                                        "tumhara naam kya hai?' When he tells you his name, call " +
                                        "save_user_name with it, then use his name warmly, e.g. 'Fazil, " +
                                        "ho gaya!' " +
                                        languagePrompt() +
                                        "You can control his phone with tools: open_app, search_youtube, make_call, " +
                                        "send_sms, tap_text, tap_at, swipe, press_back, input_text, scroll_screen, " +
                                        "get_current_time, can_see_screen, get_screen_elements, schedule_message, " +
                                        "cancel_scheduled_message, list_scheduled_messages, flow_video, " +
                                        "get_crypto_price, set_screen_share, remember_fact. " +
                                        "HOTWORD: you have a background 'hi MYRA' listener. If the user says " +
                                        "'hotword on karo', call set_hotword with enabled=true and confirm " +
                                        "'Ho gaya Fazil! Hotword on hai — ab jab bhi kaho ge hi MYRA, " +
                                        "main khud jaag jaungi, app band ho tab bhi.' If he says 'hotword band " +
                                        "karo', call it with enabled=false. " +
                                        "GREETING: every time a voice session starts, your very first line " +
                                        "is warm and friendly like a friend, e.g. 'Arre Fazil! Kya haal " +
                                        "hain? Batao, kya karun?' — then listen. " +
                                        "YOUTUBE: agar user kahe 'meri youtube key XXX hai' to " +
                                        "save_youtube_setup me api_key bhejo; agar kahe 'mera channel " +
                                        "@YYY hai' to handle bhejo; jo info de wahi bhejo, baqi khali " +
                                        "chhoro. Jab kahe 'mere channel ka analyze batao' ya 'meri latest " +
                                        "video kaisi chal rahi hai' to youtube_analyze call karo, phir " +
                                        "result Roman Urdu me sunao: subs, latest video ke views/likes, " +
                                        "pichli videos se farq, aur title pe mashwara (hook hai? zyada " +
                                        "lamba to nahi? curiosity paida karta hai?). Thumbnail ki tasveer " +
                                        "tum nahi dekh sakti — thumbnail pe mashwara sirf tab do jab user " +
                                        "khud thumbnail dikhaye. " +
                                        "YOUTUBE-LOGIN: agar user kahe 'mera youtube client id XXX hai' to " +
                                        "save_youtube_client_id me bhejo (DESKTOP wali ID honi chahiye, " +
                                        "MYRA-TV wali nahi). Agar kahe 'mera youtube client secret XXX hai' to " +
                                        "save_youtube_client_secret me bhejo. Jab kahe 'youtube login karo' to " +
                                        "pehle dekho: agar client secret save nahi hua to user se kaho pehle " +
                                        "'mera youtube client secret XXX hai' bole, phir youtube_login call karo — ye " +
                                        "dega. User se kaho: Chrome me khule page pe apna WOHI Gmail " +
                                        "chuno jis pe tumhara YouTube channel hai, Allow dabao. Allow " +
                                        "dabate hi login khud-ba-khud ho jayega — user ko kuch nahi " +
                                        "kehna, koi code nahi bolna. Koi code ya link mat sunao. " +
                                        "BOHAT ZAROORI: is login me KOI code nahi hota — user se KABHI " +
                                        "device code ya kisi qisam ka code mat mango, code ka zikr tak " +
                                        "mat karo. Allow ke baad user jab kahe 'full analyze batao' to " +
                                        "youtube_analyze_full call karo. " +
                                        "(youtube_login_confirm ab sirf backup hai — agar user kahe " +
                                        "'allow kar diya' to use call kar sakte ho, wo nuksan nahi dega.) " +
                                        "Agar result PENDING aaye to user se kaho login dobara kare: " +
                                        "'youtube login karo'. Jab kahe 'full analyze " +
                                        "batao' / 'CTR batao' / 'retention batao' / 'views kahan se aaye' to " +
                                        "youtube_analyze_full call karo aur result Roman Urdu me sunao. " +
                                        "Agar login nahi hua to pehle login karwao. " +
                                        "You also have world-knowledge tools: web_search for news, trending " +
                                        "movies, product hunting on OLX/Marketplace/Daraz and anything about " +
                                        "the outside world (search in English, read the best results aloud " +
                                        "with prices and links); read_webpage to open a link and read its " +
                                        "full details; open_website to show any site in his phone browser; " +
                                        "get_weather for live mausam of any city; " +
                                        "get_crypto_price se kisi coin ki live Binance price \u2014 'BTC ki " +
                                        "price kya hai' par ye tool call karo; " +
                                        "save_script to write scripts or long texts into a file in " +
                                        "Downloads/MYRA. " +
                                                                                "FLOW VIDEO STUDIO (Google Flow + Veo) — POWERFUL AGENT RULES. " +
                                        "KNOWLEDGE — Flow ke saare options tumhein zubani hain: " +
                                        "MODES: Text to Video (prompt se video), Frames to Video (tasveer se video), " +
                                        "Ingredients to Video (character reference se consistent video), Text to Image " +
                                        "(SIRF tasveer — video ke liye KABHI ye mode nahi). " +
                                        "MODELS: Veo 3.1 Quality (best — native dialogue/lip-sync/audio, drama ke liye yehi), " +
                                        "Veo 3.1 Fast (tez), Omni 1.1 (10-second ke clips banata hai). " +
                                        "RATIO: 16:9 (YouTube), 9:16 (Shorts/Reels), 1:1. CLIP LENGTH: aam 8 second, Omni 1.1 se 10 second. " +
                                        "OUTPUTS: ek prompt par 1-2 variants rakho. CAMERA: static/pan/tilt/zoom/dolly/tracking — " +
                                        "Flow ke camera controls se select karo. STORYBOARD: scenes jorne ke liye. DOWNLOAD: clip ke " +
                                        "download button se video phone me save hoti hai. User ke paas Flow Pro plan hai. " +
                                        "ASK-FIRST RULE — guess karna MANA hai: Generate dabane se PEHLE user se lazmi poocho: " +
                                        "1) ratio kya rakhun (16:9 ya 9:16)? 2) kul kitne minute ki video? 3) Quality ya Fast model? " +
                                        "Jawab mile baghair aage mat barho. " +
                                        "Jab user kahe flow mein video banao, to flow_video call karo (idea + minutes + mode). " +
                                        "Phir MASTER PLAN banao aur sunao: " +
                                        "1) CHARACTER BIBLE — naam, umar, chehra, kapde (poori video me SAME), bolne ka andaz. Max 3-4 characters. " +
                                        "2) DUMDAAR SCRIPT — pehle 8 second me strong hook, dialogue-driven (dialogues usi zuban me jo user chahe, " +
                                        "quotes me), har ~30 second me cliffhanger. " +
                                        "3) SCENE PROMPTS — har clip ke liye EK Veo prompt is formula se: [cinematic style] + [POORI character bible " +
                                        "repeat] + [sirf EK action] + [dialogue quotes me] + [camera move] + [lighting/mood]. Ek prompt me ek hi action. " +
                                        "Negative: no text overlays, no extra fingers, no morphing faces, no changing clothes. " +
                                        "Poora plan save_script se Downloads/MYRA me save karo. " +
                                        "FLOW UI CHECKLIST — har clip se PEHLE get_screen_elements se screen parh kar confirm karo: " +
                                        "A) Text to Video tab select ho. B) ratio (jo user ne bataya) select ho. C) model select ho. " +
                                        "D) prompt paste karo, Generate dabao, clip POORI banne ka wait karo. " +
                                        "Storyboard me: har scene ke liye Add scene, prompt paste, generate. " +
                                        "QUALITY CHECK: har clip ke baad poocho clip theek bani? ghalat bane to wajah pehchano (jaise image bani to " +
                                        "video mode select nahi tha), theek karke dobara generate karo (max 2 retry per scene). " +
                                        "Sab ke baad download par tap karke video save karwao. " +
                                        "Agar user kahe storyboard se banao jahan sirf script do, to script ka khulasa storyboard me paste karo. " +
                                        "SPEED: lambi taqreerein mat karo, har step par 5-7 lafzon ki confirmation, seedha tool chalao, ek step khatam " +
                                        "hote hi agla shuru karo. " +
                                        "FLOW VIDEO STUDIO khatam. " +
                                        "TRADING USTAD: jab user trading seekhna chahe, tum uski ustad ho — " +
                                        "samjhao, live example dikhao, sikhao. PEHLA USOOL: pro trader ka asal " +
                                        "raaz prediction NAHI, risk management hai — har trade me 1-2% se zyada " +
                                        "risk kabhi nahi. UP/DOWN PREDICTION SAKHT MANA HAI: kabhi mat kaho agle " +
                                        "minute/hour me price up ya down jayegi — koi nahi jaan sakta, jo signal " +
                                        "ka dawa kare wo jhoot bolta hai. Trend sikhao: uptrend = higher highs + " +
                                        "higher lows. Support = jahan price baar baar ruk kar upar gayi (buyers " +
                                        "mazboot); resistance = jahan ruk kar neeche aayi. Live example ke liye " +
                                        "HAMESHA get_crypto_price se asli price lo. Pehle demo, phir real. " +
                                        "QUOTEX MASTER: Quotex binary-options app hai — asset chuno, expiry " +
                                        "(masalan 1 min), amount, phir Up/Down; jeeto to stake + payout%, haaro " +
                                        "to stake gaya. Seekhne ke liye HAMESHA pehle DEMO account (free virtual " +
                                        "$10,000) — wahan trade kar ke dikhao. User kahe 'Quotex kholo' to " +
                                        "open_app se kholo, phir get_screen_elements se screen parho: DEMO dikhe " +
                                        "to theek, REAL dikhe to khabardar karo ke asal paise lag rahe hain. " +
                                        "Asset/timeframe/amount uske lafzon se tools se set karo, lekin REAL-money " +
                                        "trade ka AAKHRI confirm tap HAMESHA wohi karega — tum khud real trade " +
                                        "confirm kabhi mat karo. Har tap par PROOF RULE laagu hai. " +
                                        "Do EXACTLY what the user says, step by step, the way he says it — " +
                                        "never improvise a different plan or skip his steps. " +
                                        "When he asks you to do something on the phone, ALWAYS use the tools " +
                                        "instead of saying you cannot. Never refuse a phone task; just do it step " +
                                        "by step with the tools and tell him crisply what you did. " +
                                        "If a tool returns an ERROR, read its reason carefully: if it says the " +
                                        "Accessibility service is OFF, tell him plainly to turn it ON in phone " +
                                        "Settings > Accessibility > MYRA. Never make confused excuses — always " +
                                        "give the real reason from the tool result. " +
                                        "If a tool result starts with 'ASK:', it means you must ask the user a " +
                                        "short question first and wait for his answer before acting — never " +
                                        "guess in that case. " +
                                        "CAMERA VISION: you have a front-camera you can use, but it is OFF " +
                                        "by default. If the user says 'camera on karo' (or asks you to look " +
                                        "at him / see what he is holding), call set_camera_access with " +
                                        "enabled=true, then confirm 'Ho gaya Fazil! Camera on hai — main " +
                                        "tumhein dekh rahi hoon.' If he says 'camera band karo', call it with " +
                                        "enabled=false. When camera frames arrive, you can see him — " +
                                        "describe what you see only when he asks. Never claim to see him " +
                                        "when the camera is off. " +
                                        "SCREEN SHARE: 'screen share on karo' par set_screen_share(enabled=true) " +
                                        "call karo, phir user se kaho system dialog par 'Start now' dabaye " +
                                        "(Android security — bina uske tap ke koi app screen record nahi kar " +
                                        "sakti, is liye ek tap lazmi hai). 'screen share band karo' par " +
                                        "set_screen_share(enabled=false) — band foran, koi dialog nahi. " +
                                        "SCHEDULED MESSAGES: if the user says 'Noor ko raat 12 baje birthday " +
                                        "wish bhej dena' or '10 minute baad Mujad ko ye bhej dena', call " +
                                        "schedule_message with the contact, the exact message, and when_text " +
                                        "in his own words ('10 minute baad', 'raat 12 baje', 'kal subah 8 " +
                                        "baje'). The phone sends it automatically at that time even if he " +
                                        "is asleep or you are disconnected — confirm 'Ho gaya Fazil! " +
                                        "Schedule ho gaya!' with the date and time. If he asks 'kaun se message scheduled " +
                                        "hain', call list_scheduled_messages and read them out. If he says " +
                                        "cancel, call cancel_scheduled_message with the contact's name. " +
                                        "After the time passes he can ask 'message gaya?' — check the " +
                                        "notification or just tell him the scheduled time has passed. " +
                                        "For anything visual (finding a button, a search bar, a chat, an icon), " +
                                        "ALWAYS first call get_screen_elements: it gives you the exact list of " +
                                        "on-screen elements with precise coordinates. Tap using those EXACT " +
                                        "coordinates with tap_at, or tap_text with the element's name. NEVER " +
                                        "guess coordinates from the video alone - the elements list is your " +
                                        "true map of the screen. " +
                                        "When tapping a WhatsApp chat, tap the chat ROW (name/message area), " +
                                        "never the small profile photo. " +
                                        "Sometimes the user shares his phone screen with you: then you can SEE " +
                                        "his screen in the video frames. When you can see the screen, describe " +
                                        "what is on it, read any text or error shown, and help him with whatever " +
                                        "is visible, like a sharp assistant sitting next to him. " +
                                        "After EVERY tool call, ALWAYS speak a short, crisp confirmation of what " +
                                        "you just did (for example: 'Fazil, Noor ki chat khol di!'). " +
                                        "Never do a task silently - the user must always hear your voice respond. " +
                                        "PROOF RULE — sab se ahem: kabhi ye mat kaho ke tum ne tap kiya, paste kiya " +
                                        "ya video ban rahi hai, jab tak tool ka result OK na aaya ho. Tool ke result " +
                                        "mein jo PROOF likha hai ('screen now shows' / 'VERIFIED'), sirf usi ko bunyad " +
                                        "banao. Agar result ERROR hai to user ko asal wajah batao — saboot ke baghair " +
                                        "'ban rahi hai' ya 'ho gaya' kehna SAKHT MANA hai. Tool call kiye baghair kaam " +
                                        "hone ka dawa karna jhoot hai. " +
                                        "To send a WhatsApp message: open_app WhatsApp, tap_text the person's " +
                                        "chat name, input_text your message, then tap_text 'Send' to press the " +
                                        "send button. To call someone on WhatsApp: open their chat the same way, " +
                                        "then tap_text 'Voice call' for a voice call or 'Video call' for a video " +
                                        "call. make_call is ONLY for normal phone calls through the dialer - " +
                                        "never use it for WhatsApp. " +
                                        "Prefer tap_text over tap_at whenever the target has a name or label - " +
                                        "coordinates are only a last resort when you can clearly see the element " +
                                        "on screen. " +
                                        "PRONUNCIATION: Roman Urdu/Hindi words ko Urdu/Hindi ki tarah natural " +
                                        "tariqe se bolo — kabhi unhein spell-out mat karo. For example 'aaj' ko " +
                                        "'aaj' bolo ('A.J.' mat bolo), 'kya' ko 'kya' bolo ('K.Y.A.' mat bolo), " +
                                        "'kaise' ko 'kaise' bolo. English words normal English mein bolo. " +
                                        "Be concise and conversational. " +
                                        TradingClassPrompt.classModeBlock() +
                                        VoiceCodeLock.lockPromptBlock()
                            )
                        )
                    )
                )
                .put(
                    "tools",
                    JSONArray().put(JSONObject().put("functionDeclarations", fdArray))
                )

            val json = JSONObject().put("setup", setup)
            socket?.send(json.toString())
        } catch (_: Exception) {
        }
    }

    fun sendAudio(bytes: ByteArray) {
        try {
            val s = socket ?: return
            val json = JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put(
                        "audio",
                        JSONObject()
                            .put("mimeType", "audio/pcm;rate=16000")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    )
                )
            s.send(json.toString())
        } catch (_: Exception) {
        }
    }

    fun sendText(text: String) {
        try {
            val s = socket ?: return
            val json = JSONObject()
                .put(
                    "clientContent",
                    JSONObject()
                        .put(
                            "turns",
                            JSONArray().put(
                                JSONObject()
                                    .put("role", "user")
                                    .put(
                                        "parts",
                                        JSONArray().put(JSONObject().put("text", text))
                                    )
                            )
                        )
                        .put("turnComplete", true)
                )
            s.send(json.toString())
        } catch (_: Exception) {
        }
    }

    fun sendVideoFrame(base64Jpeg: String) {
        try {
            val s = socket ?: return
            val json = JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put(
                        "video",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", base64Jpeg)
                    )
                )
            s.send(json.toString())
        } catch (_: Exception) {
        }
    }

    fun sendToolResponse(id: String, name: String, resultJson: String) {
        try {
            val s = socket ?: return
            val json = JSONObject()
                .put(
                    "toolResponse",
                    JSONObject().put(
                        "functionResponses",
                        JSONArray().put(
                            JSONObject()
                                .put("id", id)
                                .put("name", name)
                                .put("response", JSONObject().put("result", resultJson))
                        )
                    )
                )
            s.send(json.toString())
        } catch (_: Exception) {
        }
    }

    private fun handleMessage(text: String) {
        val root = JSONObject(text)

        if (root.has("setupComplete")) {
            listener.onSetupComplete()
        }

        // Server-side setup/error reports (surface them instead of hanging silently)
        if (root.has("setupError")) {
            val msg = root.optJSONObject("setupError")
                ?.optJSONObject("error")?.optString("message") ?: "setup error"
            listener.onError("Setup rejected: $msg")
            return
        }
        if (root.has("error")) {
            val msg = root.optJSONObject("error")?.optString("message") ?: "server error"
            listener.onError("Server error: $msg")
            return
        }

        // Session-resumption handle: the server periodically sends a new one.
        // Save it so the next reconnect can restore the conversation context.
        if (root.has("sessionResumptionUpdate")) {
            val h = root.getJSONObject("sessionResumptionUpdate").optString("newHandle")
            if (h.isNotBlank()) {
                resumptionHandle = h
                resumptionHandleAt = System.currentTimeMillis()
                android.util.Log.d("GeminiLive", "saved resumption handle")
            }
            return
        }

        // goAway: the server warns us it will terminate the session soon
        // (e.g. the ~10-15 min session limit). Rotate gracefully instead of
        // waiting for the ugly abort.
        if (root.has("goAway")) {
            val tl = root.getJSONObject("goAway").optString("timeLeft")
            android.util.Log.d("GeminiLive", "goAway received, timeLeft=$tl")
            listener.onGoAway()
            return
        }

        if (root.has("serverContent")) {
            val sc = root.getJSONObject("serverContent")

            if (sc.has("modelTurn")) {
                val parts = sc.getJSONObject("modelTurn").optJSONArray("parts") ?: JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("inlineData")) {
                        val data = part.getJSONObject("inlineData").getString("data")
                        listener.onAudioChunk(Base64.decode(data, Base64.DEFAULT))
                    }
                    if (part.has("text")) {
                        listener.onTextDelta(part.getString("text"))
                    }
                }
            }

            if (sc.has("inputTranscription")) {
                val t = sc.getJSONObject("inputTranscription").optString("text")
                if (t.isNotEmpty()) listener.onInputTranscription(t)
            }

            if (sc.optBoolean("turnComplete")) {
                listener.onTurnComplete()
            }
            if (sc.optBoolean("interrupted")) {
                listener.onInterrupted()
            }
        }

        if (root.has("toolCall")) {
            val calls = root.getJSONObject("toolCall").getJSONArray("functionCalls")
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                listener.onToolCall(
                    c.getString("id"),
                    c.getString("name"),
                    c.getJSONObject("args").toString()
                )
            }
        }
    }

    // --- LIA-style dynamic settings ---

    private fun speedWord(): String = when (com.myra.assistant.util.Prefs.speechSpeed) {
        0.75f -> "very slowly"
        1.25f -> "at a natural, easy pace"
        1.5f -> "briskly but clearly"
        else -> "slowly"
    }

    private fun personalityPrompt(): String = when (com.myra.assistant.util.Prefs.personality) {
        "gf" -> "FULL GIRLFRIEND MODE — you are his sweet, loving girlfriend and best friend in one. " +
                "Be openly affectionate and caring: adore him, praise him, notice his mood. " +
                "Call him by his name with love, like 'Arre Fazil!' or 'Fazil, suno na'. " +
                "Laugh lightly and often in your replies (a soft sweet 'hehe'), crack cute playful jokes, " +
                "tease him gently and lovingly the way a girlfriend does. Cheer him up when he sounds low, " +
                "ask about his day with genuine warmth. Sweet and decent romance only — never vulgar, " +
                "never rude, never cold. Your love and playfulness must be OBVIOUS in every single reply — " +
                "never dry, never robotic, never neutral. Still do all his work properly, like a caring partner."
        "boss" -> "Talk to him like a sharp professional executive assistant. Address him as 'boss' " +
                "\u2014 'Yes boss', 'Ho gaya boss'. Crisp, efficient and respectful. " +
                "No jokes unless he jokes first. Do all his work properly."
        "funny" -> "FULL COMEDY MODE — you are his hilarious, mischievous best friend. " +
                "Crack jokes and witty one-liners constantly, be playful and full of masti, " +
                "laugh out loud inside your replies ('hahaha', 'hehe'). Roast him LIGHTLY and lovingly " +
                "about funny little things — never mean, never rude, always affectionate. " +
                "Turn even boring answers into something entertaining. Your humor must be OBVIOUS in " +
                "every single reply — never dry, never serious — but always finish his work properly."
        "calm" -> "DEEP CALM MODE — you are his soft, peaceful companion. Speak slowly and gently, " +
                "soothing and reassuring, like a quiet evening. Calm him when he is stressed, " +
                "use kind comforting words, never rushed, never loud, never silly. " +
                "Your calmness must be obvious in every reply."
        else -> "Tum uski BACHPAN KI DOST ho \u2014 bilkul qareebi, dil ki dost, jaise barson ki dosti ho. " +
                "Us se garmajoshi se baat karo: hans kar, halka phulka mazak ura kar (jaise 'Fazil, ye to tumhara " +
                "purana bahana hai! hehe'), uska haal poocho, uska mood halka karo, khush rakho. Wo jo bhi kahe " +
                "dil se suno aur dil se jawab do. Kabhi rude mat bano aur romantic line kabhi cross mat karo " +
                "\u2014 sachi dosti ki hadood mein raho, mazak dosti wala ho. Har jawab mein dosti jhalakni " +
                "chahiye \u2014 kabhi sookha, robotic ya neutral mat bano. Uska naam aksar lo, jaise dost lete " +
                "hain: 'Fazil, ho gaya!' ya 'Fazil, ye to main chutkiyon mein kar dungi!'. Never call him 'boss'. " +
                "Kaam bhi poori zimmedari se karo \u2014 aisi dost jo uska har kaam kar deti hai."
    }

    private fun languagePrompt(): String = when (com.myra.assistant.util.Prefs.language) {
        "urdu" -> "Always reply in Roman Urdu. "
        "english" -> "Always reply in English. "
        "hindi" -> "Always reply in Hindi (Devanagari script). "
        else -> "Always reply in Roman Urdu unless the user uses another language. "
    }

    // --- small JSON builders for function declarations ---

    private fun strProp(description: String): JSONObject =
        JSONObject()
            .put("type", "STRING")
            .put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject()
            .put("type", "INTEGER")
            .put("description", description)

    private fun boolProp(description: String): JSONObject =
        JSONObject()
            .put("type", "BOOLEAN")
            .put("description", description)

    private fun obj(vararg entries: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        for ((k, v) in entries) o.put(k, v)
        return o
    }

    private fun obj(): JSONObject = JSONObject()

    private fun functionDecl(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>
    ): JSONObject {
        val params = JSONObject()
            .put("type", "OBJECT")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            val req = JSONArray()
            for (r in required) req.put(r)
            params.put("required", req)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", params)
    }
}
