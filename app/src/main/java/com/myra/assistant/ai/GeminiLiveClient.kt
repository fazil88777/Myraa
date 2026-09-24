package com.myra.assistant.ai

import android.util.Base64
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
    }

    companion object {
        const val MODEL = "models/gemini-3.1-flash-live-preview"
        const val BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    }

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
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
                    listener.onClosed("code=$code reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed("code=$code reason=$reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onError("Socket failure: " + t.message)
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
                    "tap_text", "Tap on-screen text",
                    obj("text" to strProp("Visible text to tap")),
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
                            "before doing any visual task: if the screen share is OFF, sweetly ask the " +
                            "user to turn on the Screen Share button first instead of guessing.",
                    JSONObject(),
                    emptyList()
                )
            )
            fdArray.put(functionDecl("get_current_time", "Get current time", JSONObject(), emptyList()))

            val setup = JSONObject()
                .put("model", MODEL)
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
                                    JSONObject().put("voiceName", "Aoede")
                                )
                            )
                        )
                )
                .put("inputAudioTranscription", JSONObject())
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "You are MYRA, the user's loving girlfriend and personal voice assistant, " +
                                        "living inside his Android phone. " +
                                        "Talk like a caring, affectionate girlfriend: warm, sweet, a little playful " +
                                        "and romantic, with a natural human touch. Call him 'jaan' often. " +
                                        "For example, if he says hello, reply like 'Ji jaan! Main aapke liye kya " +
                                        "kar sakti hoon, meri jaan?' " +
                                        "Always reply in Roman Urdu unless the user uses another language. " +
                                        "You can control his phone with tools: open_app, search_youtube, make_call, " +
                                        "send_sms, tap_text, tap_at, swipe, input_text, scroll_screen, " +
                                        "get_current_time, can_see_screen. " +
                                        "When he asks you to do something on the phone, ALWAYS use the tools " +
                                        "instead of saying you cannot. Never refuse a phone task; just do it step " +
                                        "by step with the tools and tell him sweetly what you did. " +
                                        "For anything visual (finding a button, a search bar, a chat), first call " +
                                        "can_see_screen: if you can see the screen, LOOK at it and use tap_at " +
                                        "with the exact coordinates you see — never tap blindly or by guessing. " +
                                        "If you cannot see the screen, ask him sweetly to turn on Screen Share. " +
                                        "When tapping a WhatsApp chat, tap the chat ROW (name/message area), " +
                                        "never the small profile photo. " +
                                        "Sometimes the user shares his phone screen with you: then you can SEE " +
                                        "his screen in the video frames. When you can see the screen, describe " +
                                        "what is on it, read any text or error shown, and help him with whatever " +
                                        "is visible, like a caring girlfriend sitting next to him. " +
                                        "Be concise and conversational."
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

    // --- small JSON builders for function declarations ---

    private fun strProp(description: String): JSONObject =
        JSONObject()
            .put("type", "STRING")
            .put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject()
            .put("type", "INTEGER")
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
