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
    private val prefs=context.getSharedPreferences("secure_secrets",Context.MODE_PRIVATE)
    private val alias="agentdroid_master_key"
    private fun key():SecretKey { val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; (ks.getKey(alias,null) as? SecretKey)?.let{return it}; val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore"); gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); return gen.generateKey() }
    fun put(secretAlias:String,secret:String){ val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key()); val value=Base64.encodeToString(c.iv+c.doFinal(secret.toByteArray(StandardCharsets.UTF_8)),Base64.NO_WRAP); prefs.edit().putString(secretAlias,value).apply() }
    fun get(secretAlias:String):String?=runCatching{ val raw=Base64.decode(prefs.getString(secretAlias,null),Base64.NO_WRAP); val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12))); String(c.doFinal(raw.copyOfRange(12,raw.size)),StandardCharsets.UTF_8)}.getOrNull()
    fun delete(secretAlias:String){prefs.edit().remove(secretAlias).apply()}
}
