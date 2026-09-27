package com.myra.assistant.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.myra.assistant.util.MyraBridge
import com.myra.assistant.util.Prefs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * PC Link: a tiny HTTP server on the phone. A computer on the same Wi-Fi
 * opens the shown address in its browser and can start/stop MYRA's voice
 * session and send her text — no extra app needed on the PC.
 */
object PcLink {

    private const val PORT = 8090
    private var socket: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = try {
        socket?.isClosed == false
    } catch (_: Exception) {
        false
    }

    fun start(context: Context) {
        if (isRunning()) return
        Thread({
            try {
                val ss = ServerSocket(PORT)
                socket = ss
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Thread({ handle(client) }, "PcLink-client").start()
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }, "PcLink").apply { isDaemon = true; start() }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun url(context: Context): String = "http://" + wifiIp(context) + ":" + PORT

    private fun wifiIp(context: Context): String {
        return try {
            val wm =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (_: Exception) {
            "phone-ip"
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val h = line!!
                if (h.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (contentLength > 0 && contentLength < 8192) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }
            val (code, ctype, respBody) = route(method, path, body)
            val bytes = respBody.toByteArray(Charsets.UTF_8)
            val out = client.getOutputStream()
            val head = "HTTP/1.1 $code OK\r\n" +
                    "Content-Type: $ctype; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun route(method: String, path: String, body: String): Triple<Int, String, String> {
        return when {
            method == "GET" && path == "/" -> Triple(200, "text/html", page())
            method == "GET" && path == "/status" -> Triple(200, "application/json", statusJson())
            method == "POST" && path == "/start" -> {
                postMain { startVoice() }
                Triple(200, "text/plain", "ok")
            }
            method == "POST" && path == "/stop" -> {
                postMain { stopVoice() }
                Triple(200, "text/plain", "ok")
            }
            method == "POST" && path == "/say" -> {
                val text = URLDecoder.decode(body.substringAfter("text=", ""), "UTF-8")
                postMain { say(text) }
                Triple(200, "text/plain", "ok")
            }
            else -> Triple(404, "text/plain", "not found")
        }
    }

    private fun postMain(fn: () -> Unit) = main.post {
        try {
            fn()
        } catch (_: Exception) {
        }
    }

    private fun statusJson(): String {
        val vm = MyraBridge.viewModel
        val connected = vm?.isConnected?.value == true
        val status = (vm?.statusText?.value ?: "Idle").replace("\"", "'")
        val name = Prefs.assistantName.ifBlank { "MYRA" }.replace("\"", "'")
        return "{\"connected\":$connected,\"status\":\"$status\",\"name\":\"$name\"}"
    }

    private fun startVoice() {
        val vm = MyraBridge.viewModel ?: return
        if (vm.isConnected.value != true && Prefs.apiKey.isNotBlank()) {
            vm.startSession(Prefs.apiKey)
        }
    }

    private fun stopVoice() {
        MyraBridge.viewModel?.stopSession()
    }

    private fun say(text: String) {
        if (text.isNotBlank()) MyraBridge.viewModel?.sendTypedText(text)
    }

    private fun page(): String {
        val name = Prefs.assistantName.ifBlank { "MYRA" }
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>" + name + " PC Link</title>" +
                "<style>body{background:#05080d;color:#fff;font-family:sans-serif;" +
                "display:flex;flex-direction:column;align-items:center;padding:32px}" +
                ".orb{width:120px;height:120px;border-radius:60px;" +
                "background:radial-gradient(circle,#2dd4a8 0%,#0b3b2e 70%,transparent 100%);margin:16px}" +
                "button{background:#0c141c;color:#2dd4a8;border:1px solid #16303b;" +
                "border-radius:12px;padding:12px 22px;margin:6px;font-size:16px;cursor:pointer}" +
                "input{background:#0c141c;color:#fff;border:1px solid #16303b;" +
                "border-radius:12px;padding:12px;width:280px;margin:6px;font-size:15px}" +
                "#st{color:#8a9ba8;margin:10px}</style></head><body>" +
                "<h2>" + name + " — PC Link</h2><div class='orb'></div>" +
                "<div id='st'>connecting…</div>" +
                "<div><button onclick=\"post('/start')\">Start Voice</button>" +
                "<button onclick=\"post('/stop')\">Stop</button></div>" +
                "<div><input id='t' placeholder='Type a message…'>" +
                "<button onclick=\"say()\">Send</button></div>" +
                "<script>" +
                "function post(p){fetch(p,{method:'POST'})}" +
                "function say(){var t=document.getElementById('t').value;" +
                "fetch('/say',{method:'POST',body:'text='+encodeURIComponent(t)});" +
                "document.getElementById('t').value=''}" +
                "setInterval(function(){fetch('/status').then(function(r){return r.json()})" +
                ".then(function(j){document.getElementById('st').textContent=" +
                "(j.connected?'Connected':'Idle')+' — '+j.status})" +
                ".catch(function(){})},2000)" +
                "</script></body></html>"
    }
}
