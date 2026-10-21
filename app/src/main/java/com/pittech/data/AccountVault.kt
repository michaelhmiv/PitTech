package com.pittech.data

import com.pittech.online.OnlineConfiguration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AccountStorage { fun load(): AccountState; fun save(value: AccountState) }
/** Separate device-only namespace, absent from Room, preferences and archive exports. */
internal class AccountVault(context: Context, private val namespace: String = OnlineConfiguration.current.environment) : AccountStorage {
    init { require(namespace.matches(Regex("[a-z0-9-]{1,64}"))) }
    private val file = File(context.noBackupFilesDir, "account-$namespace-v1/state").also { check(it.parentFile!!.exists() || it.parentFile!!.mkdirs()) }
    private val alias = "pittech-account-$namespace-v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized override fun load(): AccountState {
        val plain=loadBytes() ?: return AccountState()
        return try { AccountState.parse(JSONObject(String(plain,Charsets.UTF_8))) } finally { plain.fill(0) }
    }
    @Synchronized fun loadBytes():ByteArray? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input -> require(input.channel.size() in 29..16412); input.readBytes() }
        require(bytes.size in 29..16412)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12))); cipher.updateAAD(alias.toByteArray())
        return cipher.doFinal(bytes,12,bytes.size-12)
    }
    @Synchronized override fun save(value: AccountState) {
        val plain=value.json().toString().toByteArray()
        try { saveBytes(plain) } finally { plain.fill(0) }
    }
    @Synchronized fun saveBytes(plain:ByteArray) {
        require(plain.size <= 16384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key(true)); cipher.updateAAD(alias.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(plain)
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Throwable) { atomic.failWrite(output); throw e }
    }
}
