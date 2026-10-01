package com.pittech.devices

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface PolarisSessionStore {
    fun load(): PolarisSession?
    fun save(session: PolarisSession)
    fun clear()
}

/** Keystore-backed AES-GCM, in no-backup storage. No plaintext fallback. Called on IO. */
internal class AndroidPolarisSessionStore(context: Context) : PolarisSessionStore {
    private val file = File(context.applicationContext.noBackupFilesDir, "grillirg-session-v1.bin")
    private val alias = "pittech-grillirg-session-v1"
    private val aad = "PitTech GrillirG session v1".toByteArray(Charsets.UTF_8)

    @Synchronized override fun load(): PolarisSession? {
        if (!file.exists()) return null
        require(file.length() in 29..32_768)
        val bytes = file.readBytes()
        require(bytes.size in 29..32_768)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(bytes, 12, bytes.size - 12)
        try {
            val data = JSONObject(String(plaintext, Charsets.UTF_8))
            val token = data.getString("token")
            require(token.isNotBlank() && token.length <= 16_384 && token.none { it.isWhitespace() })
            return PolarisSession(token, data.optLong("expires").takeIf { it > 0 }, data.optString("selected").takeIf { it.isNotBlank() })
        } finally { plaintext.fill(0) }
    }

    @Synchronized override fun save(session: PolarisSession) {
        val plaintext = JSONObject().put("token", session.token).put("expires", session.expiresAtMillis ?: 0)
            .put("selected", session.selectedDeviceId ?: "").toString().toByteArray(Charsets.UTF_8)
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            temporary.outputStream().use { stream ->
                stream.write(cipher.iv)
                stream.write(cipher.doFinal(plaintext))
                stream.flush()
            }
            check(temporary.renameTo(file))
        } finally {
            plaintext.fill(0)
            temporary.delete()
        }
    }

    @Synchronized override fun clear() {
        check(!file.exists() || file.delete())
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
