package com.myra.assistant.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Secret Vault — a private, encrypted memory partition for things he
 * explicitly asks her to keep strictly "between just us" (the
 * save_secret tool).
 *
 * Storage: a separate file, `myra_secrets.json`, in the app's private
 * files dir — NEVER part of myra_brain.json and NEVER searched by
 * [MyraMemory.searchMemory] or injected into any prompt block. The only
 * way in or out is the save_secret / search_secrets tools, and the
 * prompt rules say she may only open it when HE raises the topic.
 *
 * Encryption: the file's whole payload (a JSON array of {text, ts}) is
 * encrypted with AES-256/GCM. The key is generated once and held in the
 * Android Keystore under [KEY_ALIAS] — it never leaves the Keystore and
 * is not written anywhere. On disk the file is a small wrapper:
 *   {"enc":1, "iv":"<base64>", "data":"<base64 ciphertext>"}
 * If encryption (or the Keystore) fails at runtime, the vault falls
 * back to the same separate file in plain form ({"enc":0,"items":[...]})
 * rather than losing his secret — still app-private, just unencrypted.
 */
object SecretVault {

    private const val VAULT_FILE = "myra_secrets.json"
    private const val KEY_ALIAS = "myra_secret_vault_key"
    private const val MAX_SECRETS = 100

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    private data class Secret(val text: String, val ts: Long)

    private fun vaultFile() = File(appContext.filesDir, VAULT_FILE)

    // ---------- crypto ----------

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null as KeyStore.LoadStoreParameter?)
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns (ivBase64, ciphertextBase64). */
    private fun encrypt(plain: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(ivB64: String, ctB64: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
    }

    // ---------- storage ----------

    private fun loadSecrets(): MutableList<Secret> {
        if (!::appContext.isInitialized) return mutableListOf()
        return try {
            val f = vaultFile()
            if (!f.exists()) return mutableListOf()
            val wrapper = JSONObject(f.readText())
            val arr: JSONArray = if (wrapper.optInt("enc", 0) == 1) {
                val plain = decrypt(wrapper.getString("iv"), wrapper.getString("data"))
                JSONArray(String(plain, Charsets.UTF_8))
            } else {
                wrapper.optJSONArray("items") ?: JSONArray()
            }
            val out = mutableListOf<Secret>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("text")
                if (t.isNotBlank()) out.add(Secret(t, o.optLong("ts", 0L)))
            }
            out
        } catch (e: Exception) {
            // E.g. Keystore key invalidated — the vault reads as empty
            // rather than crashing her.
            Log.d("SecretVault", "vault load failed: ${e.message}")
            mutableListOf()
        }
    }

    private fun storeSecrets(secrets: List<Secret>) {
        val arr = JSONArray()
        secrets.forEach {
            arr.put(JSONObject().put("text", it.text).put("ts", it.ts))
        }
        val wrapper = JSONObject()
        try {
            val (iv, data) = encrypt(arr.toString().toByteArray(Charsets.UTF_8))
            wrapper.put("enc", 1).put("iv", iv).put("data", data)
        } catch (e: Exception) {
            // Never lose a secret over a crypto failure: same separate
            // file, plain wrapper, app-private storage.
            Log.d("SecretVault", "encryption unavailable, storing plain: ${e.message}")
            wrapper.put("enc", 0).put("items", arr)
        }
        try {
            vaultFile().writeText(wrapper.toString())
        } catch (_: Exception) {
        }
    }

    // ---------- tools ----------

    /** Locks one secret into the vault. False only on storage failure. */
    fun addSecret(rawText: String): Boolean {
        if (!::appContext.isInitialized) return false
        val text = rawText.trim().take(1000)
        if (text.isEmpty()) return false
        val secrets = loadSecrets()
        if (secrets.any { it.text.equals(text, ignoreCase = true) }) return true
        secrets.add(0, Secret(text, System.currentTimeMillis()))
        while (secrets.size > MAX_SECRETS) secrets.removeAt(secrets.size - 1)
        storeSecrets(secrets)
        return true
    }

    /**
     * Keyword search inside the vault ONLY (same token style as
     * [MyraMemory.searchMemory]). Called by the search_secrets tool —
     * the prompt rules allow it only when HE brings the topic up.
     */
    fun searchSecrets(query: String): String {
        if (!::appContext.isInitialized) return "vault abhi ready nahi hai."
        val q = query.trim()
        if (q.isEmpty()) return "kuch nahi mila — query khaali thi."
        val tokens = q.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val scored = loadSecrets().mapNotNull { s ->
            val hay = s.text.lowercase()
            var score = tokens.count { hay.contains(it) }
            if (q.length >= 3 && hay.contains(q.lowercase())) score += 2
            if (score > 0) s to score else null
        }.sortedWith(
            compareByDescending<Pair<Secret, Int>> { it.second }
                .thenByDescending { it.first.ts }
        )
        if (scored.isEmpty()) {
            return "Vault mein '$q' ke baare mein koi secret nahi hai."
        }
        val sb = StringBuilder()
        sb.append("Vault search '").append(q).append("' — ")
            .append(scored.size.coerceAtMost(8))
            .append(" secret(s) mile. (YE SIRF HAMARE DARMIYAN HAI — sirf is liye " +
                "khul raha hai kyunke usne khud ye mauzu uthaya hai:)\n")
        scored.take(8).forEach { (s, _) ->
            sb.append("• ")
                .append(MyraMemory.formatLine(s.text, "", "SECRET", s.ts, ""))
                .append("\n")
        }
        return sb.toString().trim()
    }
}
