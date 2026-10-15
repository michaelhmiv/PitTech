package com.pittech.data

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal object AccountProtocol {
    // Enable only with the completed accounts/social/sync/deletion phase.
    const val ENABLED = false
    fun verifier(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun credential(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{43}")) && runCatching { Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value }.getOrDefault(false)
    fun proof(verifier: String): String = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun email(input: String): String {
        val value = input.trim().lowercase(Locale.ROOT)
        require(input.length <= 254 && input.all { it.code < 128 } && value.substringAfter('@').split('.').all { it.length <= 63 } && value.matches(Regex("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) && !value.startsWith(".") && ".." !in value && ".@" !in value && value.substringBefore('@').length <= 64)
        return value
    }
    fun link(input: String): Pair<String, String> {
        require(input.length <= 256)
        val uri = URI(input)
        require(uri.scheme == "https" && uri.host == "pittech.app" && uri.port == -1 && uri.rawUserInfo == null && uri.rawPath == "/auth" && uri.rawQuery == null)
        val fields = uri.rawFragment?.split('&') ?: error("Invalid link")
        require(fields.size == 2 && fields[0].startsWith("challenge=") && fields[1].startsWith("token="))
        val id = fields[0].removePrefix("challenge="); val token = fields[1].removePrefix("token=")
        require(id.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) && credential(token))
        return id.lowercase(Locale.ROOT) to token
    }
}
internal class AccountFailure(val status: Int = 0) : Exception(when(status) {
    401 -> "This sign-in has expired or is unavailable. Request a new code."
    429 -> "Too many attempts. Wait a minute before trying again."
    503 -> "Accounts are not available in this build. Your local cookbook works without signing in."
    else -> "Couldn't complete sign-in. Retry the same request when you're online."
})
internal data class AccountSession(val accessToken: String, val refreshToken: String, val accessExpiresAt: String, val sessionExpiresAt: String, val userId: String) {
    fun validate() { require(AccountProtocol.credential(accessToken) && AccountProtocol.credential(refreshToken) && userId.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) && Instant.parse(accessExpiresAt) <= Instant.parse(sessionExpiresAt)) }
    fun json() = JSONObject().put("accessToken", accessToken).put("refreshToken", refreshToken).put("accessExpiresAt", accessExpiresAt).put("sessionExpiresAt", sessionExpiresAt).put("userId", userId)
    companion object { fun parse(o: JSONObject) = AccountSession(o.getString("accessToken"), o.getString("refreshToken"), o.getString("accessExpiresAt"), o.getString("sessionExpiresAt"), o.getString("userId")).also { it.validate() } }
}
internal data class AccountChallenge(val id: String, val email: String, val verifier: String, val created: Long, val accepted: Boolean = false, val operation: String? = null, val secret: String? = null, val isLink: Boolean = false) {
    fun json() = JSONObject().put("id",id).put("email",email).put("verifier",verifier).put("created",created).put("accepted",accepted).put("operation",operation ?: JSONObject.NULL).put("secret",secret ?: JSONObject.NULL).put("isLink",isLink)
    companion object { fun parse(o: JSONObject) = AccountChallenge(o.getString("id"),o.getString("email"),o.getString("verifier"),o.getLong("created"),o.getBoolean("accepted"),o.nullString("operation"),o.nullString("secret"),o.getBoolean("isLink")) }
}
internal data class AccountState(val challenge: AccountChallenge? = null, val session: AccountSession? = null, val refreshOperation: String? = null, val logoutToken: String? = null) {
    fun json() = JSONObject().put("challenge",challenge?.json() ?: JSONObject.NULL).put("session",session?.json() ?: JSONObject.NULL).put("refreshOperation",refreshOperation ?: JSONObject.NULL).put("logoutToken",logoutToken ?: JSONObject.NULL)
    companion object { fun parse(o: JSONObject) = AccountState(o.optJSONObject("challenge")?.let(AccountChallenge::parse),o.optJSONObject("session")?.let(AccountSession::parse),o.nullString("refreshOperation"),o.nullString("logoutToken")) }
}
private fun JSONObject.nullString(key: String) = if (isNull(key)) null else getString(key)
