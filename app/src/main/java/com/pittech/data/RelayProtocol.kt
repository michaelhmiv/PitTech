package com.pittech.data

import com.pittech.online.OnlineConfiguration

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

object RelayProtocol {
    const val MAX_BYTES = 64L * 1024 * 1024
    val API: String get() = OnlineConfiguration.current.apiOrigin
    fun code(input: String): String = code(input, OnlineConfiguration.current)
    internal fun code(input: String, configuration: OnlineConfiguration): String {
        require(input.length <= 256) { "Enter the complete Cook Pack code." }
        val trimmed = input.trim()
        val raw = if (trimmed.contains("://")) {
            val uri = runCatching { URI(trimmed) }.getOrElse { error("Enter a PitTech share link or code.") }
            val path = uri.rawPath ?: error("This share link is not supported.")
            require(uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && '%' !in path) { "This share link is not supported." }
            val prefix = when {
                uri.scheme.equals("https", true) && uri.rawAuthority == configuration.publicHost -> "/s/"
                uri.scheme == configuration.linkScheme && uri.host.equals("share", true) -> "/"
                else -> error("Only PitTech share links are supported.")
            }
            require(uri.path.startsWith(prefix) && uri.path.removePrefix(prefix).isNotEmpty() && '/' !in uri.path.removePrefix(prefix)) { "Enter a complete share link." }
            uri.path.removePrefix(prefix)
        } else trimmed
        val compact = raw.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)
        require(compact.matches(Regex("[0-9A-F]{16}"))) { "Enter all 16 letters and numbers from the Cook Pack code." }
        return compact.chunked(4).joinToString("-")
    }
    fun link(code: String) = "${OnlineConfiguration.current.publicOrigin}/s/" + this.code(code)
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hash(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class RelayReceipt(val code: String, val expiresAt: String, val sha256: String, val bytes: Long, val kind: String, val dishes: Int) {
    val expired get() = Instant.parse(expiresAt).toEpochMilli() <= System.currentTimeMillis()
    fun json() = JSONObject().put("code", code).put("expiresAt", expiresAt).put("sha256", sha256).put("bytes", bytes).put("kind", kind).put("dishes", dishes)
    companion object {
        fun parse(value: JSONObject): RelayReceipt {
            val r = RelayReceipt(value.getString("code"), value.getString("expiresAt"), value.getString("sha256"), value.getLong("bytes"), value.getString("kind"), value.getInt("dishes"))
            require(r.code == RelayProtocol.code(r.code) && r.sha256.matches(Regex("[a-f0-9]{64}")) && r.bytes in 1..RelayProtocol.MAX_BYTES && r.kind in setOf("recipe", "collection", "cook") && r.dishes in 1..20) { "The relay returned an invalid receipt." }
            Instant.parse(r.expiresAt)
            return r
        }
    }
}
