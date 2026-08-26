package com.agentdroid.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_secrets", Context.MODE_PRIVATE)
    private val masterAlias = "agentdroid_master_key"

    private fun key(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(masterAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(masterAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }

    @Synchronized fun put(secretAlias: String, secret: String) {
        require(secretAlias.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        check(prefs.edit().putString(secretAlias, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) { "Unable to persist encrypted secret" }
    }

    fun get(secretAlias: String): String? = runCatching {
        val encoded = prefs.getString(secretAlias, null) ?: return null
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun contains(secretAlias: String) = prefs.contains(secretAlias)
    fun mask(secretAlias: String): String = get(secretAlias)?.let { if (it.length <= 8) "••••••••" else "${it.take(3)}••••${it.takeLast(3)}" } ?: ""
    fun delete(secretAlias: String) { prefs.edit().remove(secretAlias).apply() }
    fun clear() { prefs.edit().clear().apply() }
}
