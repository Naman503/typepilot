package com.typepilot.android.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys, encrypted with an AES key that never leaves the Android Keystore. */
class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val cache = HashMap<String, String?>()
    private val alias = "typepilot.secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    @Synchronized
    fun get(name: String): String? {
        if (cache.containsKey(name)) return cache[name]
        val stored = prefs.getString(name, null)
        val value = stored?.let {
            try {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(c.doFinal(bytes, 12, bytes.size - 12))
            } catch (e: Exception) { Log.w("secrets", "could not decrypt $name"); null }
        }
        cache[name] = value
        return value
    }

    @Synchronized
    fun set(name: String, value: String?) {
        cache[name] = value?.ifBlank { null }
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.trim().toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun has(name: String) = !get(name).isNullOrBlank()

    companion object {
        const val GEMINI = "gemini"
        const val OPENAI = "openai"
        const val HUGGINGFACE = "huggingface"
    }
}
