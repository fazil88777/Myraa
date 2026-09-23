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
        fun onTurnComplete()
        fun onToolCall(id: String, name: String, argsJson: String)
        fun onInterrupted()
        fun onError(msg: String)
        fun onClosed()
    }

    companion object {
        const val MODEL = "models/gemini-2.0-flash-exp"
        const val BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key="
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
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed()
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
                    obj("app_name", strProp("App name, e.g. WhatsApp")),
                    listOf("app_name")
                )
            )
            fdArray.put(
                functionDecl(
                    "make_call", "Call a phone number",
                    obj("phone_number", strProp("Phone number to call")),
                    listOf("phone_number")
                )
            )
            fdArray.put(
                functionDecl(
                    "send_sms", "Send an SMS",
                    obj(
                        "phone_number", strProp("Phone number to send to"),
                        "message", strProp("SMS message text")
                    ),
                    listOf("phone_number", "message")
                )
            )
            fdArray.put(functionDecl("answer_call", "Answer the ringing call", JSONObject(), emptyList()))
            fdArray.put(functionDecl("reject_call", "Reject/end the call", JSONObject(), emptyList()))
            fdArray.put(
                functionDecl(
                    "tap_text", "Tap on-screen text",
                    obj("text", strProp("Visible text to tap")),
                    listOf("text")
                )
            )
            fdArray.put(
                functionDecl(
                    "input_text", "Type text into the focused field",
                    obj("text", strProp("Text to type")),
                    listOf("text")
                )
            )
            fdArray.put(
                functionDecl(
                    "scroll_screen", "Scroll the screen",
                    obj("direction", strProp("up or down")),
                    emptyList()
                )
            )
            fdArray.put(functionDecl("get_current_time", "Get current time", JSONObject(), emptyList()))

            val setup = JSONObject()
                .put("model", MODEL)
                .put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                )
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "You are MYRA, a helpful voice assistant running on the user's Android phone. " +
                                        "Always reply in Roman Urdu unless the user uses another language. " +
                                        "You can control the phone via tools: open apps, make calls, send SMS, " +
                                        "tap screen text, type text, scroll. Be concise and conversational."
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
