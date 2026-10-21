package com.myra.assistant.util

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Laptop Control link: this phone <-> his Windows laptop, over the free
 * public EMQX MQTT broker (tcp://broker.emqx.io:1883).
 *
 * Topics are derived from the pairing code (Prefs.laptopPairingCode):
 * commands go to `myra/<seg>/cmd`, the laptop answers on
 * `myra/<seg>/result`, where <seg> is the first 16 lowercase hex chars
 * of the code's SHA-256 — the raw code itself must never appear in a
 * topic, because a code containing '#' or '+' (MQTT wildcard chars)
 * would make an invalid topic filter and the broker would reject the
 * subscription ("Invalid subscription filter"). The laptop agent
 * derives the identical segment (topic_segment() in maira_agent.py). The code is the shared secret — the laptop agent
 * also double-checks it inside every payload — so it must be a long
 * random string and treated like a password.
 *
 * Commands are JSON: {"id", "code", "action", ...params}. Fire-and-forget
 * actions just publish; the two request/response actions (list_directory,
 * capture_screen) register a waiter keyed by the command id and block the
 * calling tool thread until the correlated result arrives or times out.
 *
 * Everything runs on one daemon executor thread; the MQTT connection is
 * created lazily (session start, or the first command) and left open with
 * automatic reconnect — there is deliberately no disconnect at session
 * end. With an empty pairing code this object never touches the network:
 * laptop control is fully off.
 */
object LaptopLink {

    private const val TAG = "LaptopLink"
    private const val BROKER = "tcp://broker.emqx.io:1883"

    /** Outcome of one command: the laptop's result JSON, or why it failed. */
    sealed class Result {
        data class Ok(val json: JSONObject) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LaptopLink").apply { isDaemon = true }
    }

    @Volatile
    private var client: MqttClient? = null

    @Volatile
    private var activeCode: String = ""

    private class Pending {
        val latch = CountDownLatch(1)

        @Volatile
        var response: JSONObject? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Topic segment for [code]: first 16 lowercase hex chars of its
     * SHA-256 (UTF-8). Identical derivation to topic_segment() in
     * laptop-agent/maira_agent.py — both sides must agree, and the hash
     * (only [0-9a-f]) is always a valid MQTT topic segment no matter
     * what characters the raw pairing code contains. The raw code
     * still travels inside every payload's "code" field, where the
     * agent verifies it.
     */
    private fun topicSegment(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun cmdTopic(code: String) = "myra/${topicSegment(code)}/cmd"
    private fun resultTopic(code: String) = "myra/${topicSegment(code)}/result"

    /**
     * Make sure the MQTT connection is up. Called when a voice session
     * starts (and after the code is edited in Settings) so her first
     * laptop command doesn't pay the connect cost. Async, fire-and-forget,
     * and a strict no-op while no pairing code is set.
     */
    fun connectIfNeeded() {
        val code = Prefs.laptopPairingCode.trim()
        if (code.isEmpty()) return
        try {
            executor.execute { ensureConnected(code) }
        } catch (_: Exception) {
        }
    }

    /**
     * Publish one command. If [waitMs] > 0, wait up to that long for the
     * laptop's correlated result (same "id") and return it. Blocks the
     * caller — tool execution already runs on a background thread.
     */
    fun sendCommand(payload: JSONObject, waitMs: Long): Result {
        val code = Prefs.laptopPairingCode.trim()
        if (code.isEmpty()) return Result.Failed("no_code")
        val id = UUID.randomUUID().toString()
        payload.put("id", id)
        payload.put("code", code)
        val body = payload.toString()
        val future = executor.submit<Result> { doSend(code, id, body, waitMs) }
        return try {
            future.get(waitMs + 20_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Result.Failed("internal: ${e.message}")
        }
    }

    /** Runs on the executor thread: connect (if needed), publish, and —
     *  for request/response commands — wait for the correlated result. */
    private fun doSend(code: String, id: String, body: String, waitMs: Long): Result {
        val c = ensureConnected(code) ?: return Result.Failed("connect_fail")
        if (!c.isConnected) {
            // ensureConnected connects synchronously when it builds the
            // client; landing here means the broker is unreachable or a
            // reconnect is still in flight — report honestly.
            return Result.Failed("not_connected")
        }
        val waiter = if (waitMs > 0) Pending().also { pending[id] = it } else null
        try {
            val msg = MqttMessage(body.toByteArray(Charsets.UTF_8))
            msg.qos = 1
            c.publish(cmdTopic(code), msg)
        } catch (e: Exception) {
            if (waiter != null) pending.remove(id)
            return Result.Failed("publish_fail: ${e.message}")
        }
        if (waiter == null) return Result.Ok(JSONObject())
        return try {
            if (!waiter.latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                Result.Failed("timeout")
            } else {
                waiter.response?.let { Result.Ok(it) }
                    ?: Result.Failed("empty_response")
            }
        } catch (e: InterruptedException) {
            Result.Failed("interrupted")
        } finally {
            pending.remove(id)
        }
    }

    private fun connectOptions(): MqttConnectOptions = MqttConnectOptions().apply {
        isAutomaticReconnect = true
        isCleanSession = true
        connectionTimeout = 10
        keepAliveInterval = 30
    }

    /**
     * Returns the connected client for [code], creating (and synchronously
     * connecting) it if needed, or null when the broker can't be reached.
     * Runs on the executor thread; synchronized for safety.
     */
    @Synchronized
    private fun ensureConnected(code: String): MqttClient? {
        val existing = client
        if (existing != null) {
            if (activeCode == code) {
                if (existing.isConnected) return existing
                // Dropped link: automaticReconnect is usually already on
                // it; try one synchronous reconnect and report whatever
                // state we end in — the caller checks isConnected.
                return try {
                    existing.connect(connectOptions())
                    existing
                } catch (_: Exception) {
                    existing
                }
            }
            // Pairing code changed: tear the old client down.
            try {
                existing.disconnectForcibly(500, 500)
            } catch (_: Exception) {
            }
            try {
                existing.close()
            } catch (_: Exception) {
            }
            client = null
            activeCode = ""
        }
        return try {
            val c = MqttClient(
                BROKER,
                "myra-phone-" + UUID.randomUUID().toString().replace("-", "").take(12),
                MemoryPersistence()
            )
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        c.subscribe(resultTopic(code), 0)
                    } catch (_: Exception) {
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    try {
                        val json = JSONObject(String(message!!.payload, Charsets.UTF_8))
                        val rid = json.optString("id")
                        if (rid.isNotEmpty()) {
                            pending[rid]?.let { p ->
                                p.response = json
                                p.latch.countDown()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                }
            })
            c.connect(connectOptions())
            client = c
            activeCode = code
            c
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed: ${e.message}")
            null
        }
    }
}
