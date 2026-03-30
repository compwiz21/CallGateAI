package com.guardiansoftech.callgateai.settings

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted settings storage.
 * Stores API keys and credentials in AES-256-GCM encrypted JSON.
 */
class SettingsManager(private val context: Context) {
    companion object {
        private const val TAG = "SettingsManager"
        private const val PREFS_NAME = "callgateai_settings"
        private const val KEY_ENCRYPTED = "encrypted_data"
        private const val ENC_PASSWORD = "Gavin155!@"
        private const val SALT = "callgate-salt"
        private const val GCM_TAG_LENGTH = 128
    }

    private fun deriveKey(): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(ENC_PASSWORD.toCharArray(), SALT.toByteArray(), 65536, 256)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    private fun encrypt(plaintext: String): String {
        val key = deriveKey()
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray())

        val result = JSONObject().apply {
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        return result.toString()
    }

    private fun decrypt(ciphertext: String): String {
        val key = deriveKey()
        val json = JSONObject(ciphertext)
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(json.getString("data"), Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(data))
    }

    fun load(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_ENCRYPTED, null) ?: return JSONObject()

        return try {
            JSONObject(decrypt(encrypted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt settings", e)
            JSONObject()
        }
    }

    fun save(settings: JSONObject) {
        try {
            val encrypted = encrypt(settings.toString())
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENCRYPTED, encrypted)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt settings", e)
        }
    }

    fun get(key: String): String? {
        return load().optString(key, null)
    }

    fun set(key: String, value: String) {
        val settings = load()
        settings.put(key, value)
        save(settings)
    }

    fun getOpenAiKey(): String? = get("openai_api_key")
    fun setOpenAiKey(key: String) = set("openai_api_key", key)

    fun getApiUsername(): String = get("api_username") ?: "admin"
    fun setApiUsername(user: String) = set("api_username", user)

    fun getApiPassword(): String? = get("api_password")
    fun setApiPassword(pass: String) = set("api_password", pass)

    fun getAiInstructions(): String = get("ai_instructions") ?: DEFAULT_INSTRUCTIONS

    fun getWebhookUrl(): String? = get("webhook_url")
    fun setWebhookUrl(url: String) = set("webhook_url", url)
}

private const val DEFAULT_INSTRUCTIONS = """
You are a friendly AI assistant on a phone call. Be conversational, helpful, and concise.
Greet the caller warmly when the call starts. Keep your responses brief and natural.
"""
