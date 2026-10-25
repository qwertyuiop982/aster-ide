package dev.aster.ide.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AgentSecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("aster-agent-secret", Context.MODE_PRIVATE)
    private val configured = MutableStateFlow(preferences.contains("encrypted-key"))
    val hasKey = configured.asStateFlow()
    private val alias = "Aster.Agent.ApiKey"
    private var selected = AgentEngine.PI
    private fun slot(engine: AgentEngine) = engine.secretSlot
    @Synchronized fun select(engine: AgentEngine) { selected = engine; configured.value = preferences.contains(slot(engine)) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(value: String, engine: AgentEngine = selected) {
        if (value.isBlank()) { preferences.edit().remove(slot(engine)).apply(); if (selected == engine) configured.value = false; return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(slot(engine), packed).apply()
        if (selected == engine) configured.value = true
    }
    @Synchronized fun read(engine: AgentEngine = selected): String {
        val packed = preferences.getString(slot(engine), null) ?: return ""
        val (iv, ciphertext) = packed.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}