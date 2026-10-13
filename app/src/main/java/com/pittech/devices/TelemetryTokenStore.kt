package com.pittech.devices

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-source encrypted credentials in Android's no-backup directory.
 * No plaintext fallback, token logger, exported archive, or manufacturer relay.
 */
internal class TelemetryTokenStore(context: Context) {
    private val directory = context.applicationContext.noBackupFilesDir

    private fun id(provider: String, profile: String): String {
        TelemetryConnectorRegistry.requirePlanned(provider)
        require(profile.isNotBlank() && profile.length <= 512)
        return TelemetryNormalizer.localKey(provider, profile)
    }

    private fun file(key: String) = File(directory, "telemetry-" + key + ".bin")
    private fun alias(key: String) = "pittech-telemetry-" + key.take(40) + "-v1"
    private fun aad(key: String) = ("pittech:telemetry:v1:" + key).toByteArray(Charsets.UTF_8)

    @Synchronized fun save(provider: String, profile: String, token: String) {
        val key = id(provider, profile)
        require(token.isNotBlank() && token.length <= 16384 && token.none(Char::isWhitespace))
        val plain = token.toByteArray(Charsets.UTF_8)
        require(plain.size <= 16384)
        val target = file(key)
        val atomic = AtomicFile(target)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key))
            cipher.updateAAD(aad(key))
            val output = atomic.startWrite()
            try {
                output.write(cipher.iv)
                output.write(cipher.doFinal(plain))
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized fun load(provider: String, profile: String): String? {
        val key = id(provider, profile)
        val source = file(key)
        if (!source.exists()) return null
        return try {
        require(source.length() in 29..16412) { "Invalid encrypted session" }
        val bytes = AtomicFile(source).readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secret = store.getKey(alias(key), null) as? SecretKey ?: error("Session key unavailable")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(key))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        try {
            String(plain, Charsets.UTF_8).also {
                require(it.isNotBlank() && it.length <= 16384 && it.none(Char::isWhitespace))
            }
        } finally {
            plain.fill(0)
        }
        } catch (_: Exception) {
            // A corrupt or invalidated session cannot be restored. Require fresh authorization.
            clear(provider, profile)
            null
        }
    }

    @Synchronized fun clear(provider: String, profile: String) {
        val key = id(provider, profile)
        val source = file(key)
        AtomicFile(source).delete()
        check(!source.exists())
        File(directory, source.name + ".tmp").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias(key)) }
    }

    private fun secretKey(key: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias(key), null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(key),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
