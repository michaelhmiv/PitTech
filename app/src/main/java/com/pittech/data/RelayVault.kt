package com.pittech.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class RelayOwnedShare(val operation: String, val document: String, val revision: Long, val owner: String, val inputSha: String, val title: String, val createdAt: Long = System.currentTimeMillis(), val receipt: RelayReceipt? = null, val revoked: Boolean = false) {
    fun json() = JSONObject().put("operation", operation).put("document", document).put("revision", revision).put("owner", owner).put("inputSha", inputSha).put("title", title).put("createdAt", createdAt).put("receipt", receipt?.json() ?: JSONObject.NULL).put("revoked", revoked)
    companion object {
        fun parse(o: JSONObject): RelayOwnedShare {
            val r = RelayOwnedShare(o.getString("operation"), o.getString("document"), o.getLong("revision"), o.getString("owner"), o.getString("inputSha"), o.getString("title"), o.getLong("createdAt"), o.optJSONObject("receipt")?.let(RelayReceipt::parse), o.optBoolean("revoked"))
            UUID.fromString(r.operation); require(r.owner.matches(Regex("[A-Za-z0-9_-]{43}")) && r.inputSha.matches(Regex("[a-f0-9]{64}")))
            return r
        }
    }
}

/** Device-only owner capabilities and immutable retry files never enter library exports or cloud backup. */
internal class RelayVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "relay-v1").apply { check(exists() || mkdirs()) }
    private val alias = "pittech-relay-owner-v1"
    private fun recordFile(operation: String) = File(directory, operation + ".owner")
    fun pack(share: RelayOwnedShare) = File(directory, share.operation + ".pittech")
    @Synchronized fun load(document: String): RelayOwnedShare? = all().filter { it.document == document }.maxByOrNull { it.createdAt }
    @Synchronized fun all(): List<RelayOwnedShare> = directory.listFiles().orEmpty().filter { it.extension == "owner" }.map { read(it.nameWithoutExtension) }.sortedByDescending { it.createdAt }
    private fun read(operation: String): RelayOwnedShare {
        UUID.fromString(operation)
        val file = recordFile(operation)
        require(file.length() in 29..32768) { "The saved sharing capability is unavailable." }
        val bytes = AtomicFile(file).readFully(), cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); cipher.updateAAD(operation.toByteArray())
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try { RelayOwnedShare.parse(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
    }
    @Synchronized fun save(value: RelayOwnedShare) {
        val bytes = value.json().toString().toByteArray(); require(bytes.size <= 32740)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret(true)); cipher.updateAAD(value.operation.toByteArray())
            val target = AtomicFile(recordFile(value.operation)); val output = target.startWrite()
            try { output.write(cipher.iv); output.write(cipher.doFinal(bytes)); target.finishWrite(output) } catch (e: Throwable) { target.failWrite(output); throw e }
        } finally { bytes.fill(0) }
    }
    @Synchronized fun prepare(document: String, revision: Long, title: String, source: File): RelayOwnedShare {
        load(document)?.let { previous ->
            require(previous.receipt != null || previous.revoked) { "Retry the interrupted upload before starting a different share." }
        }
        require(source.length() in 1..RelayProtocol.MAX_BYTES) { "Choose fewer dishes or photos." }
        require(directory.listFiles().orEmpty().filter { it.extension == "pittech" }.sumOf { it.length() } + source.length() <= 2 * RelayProtocol.MAX_BYTES) { "Resolve an interrupted share before preparing another pack." }
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = try { RelayOwnedShare(UUID.randomUUID().toString(), document, revision, Base64.getUrlEncoder().withoutPadding().encodeToString(random), RelayProtocol.hash(source), title.take(160)) } finally { random.fill(0) }
        val target = pack(value); source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        try { save(value) } catch (e: Throwable) { target.delete(); throw e }
        return value
    }
    fun finish(value: RelayOwnedShare, receipt: RelayReceipt): RelayOwnedShare = value.copy(receipt = receipt).also { save(it); pack(value).delete() }
    private fun secret(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "This device's sharing key is unavailable. Existing public copies may still be accessible." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
}
