package com.pittech.devices

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class HomeAssistantSession(private val context: Context, private val profile: TelemetryProfile) {
    private val mutex = Mutex()
    private val endpoint = HomeAssistantEndpoint(profile.endpoint)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val vault = TelemetryTokenStore(context)
    suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        val stored = vault.load(profile.provider, profile.id) ?: throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
        val data = runCatching { JSONObject(String(Base64.getDecoder().decode(stored), Charsets.UTF_8)) }.getOrNull()
            ?: return@withLock stored // Explicit advanced long-lived token.
        if (!forceRefresh && data.getLong("expiresAt") > System.currentTimeMillis() + 60_000)
            return@withLock data.getString("access_token")
        val refreshed = postToken(FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", data.getString("refresh_token")).add("client_id", CLIENT_ID).build())
        save(refreshed.put("refresh_token", data.getString("refresh_token")))
        refreshed.getString("access_token")
    }
    suspend fun authorize(code: String) {
        require(code.isNotBlank() && code.length <= 4096)
        val result = postToken(FormBody.Builder().add("grant_type", "authorization_code")
            .add("code", code).add("client_id", CLIENT_ID).build())
        require(result.has("refresh_token"))
        save(result)
    }
    private fun save(json: JSONObject) {
        require(json.getString("token_type").equals("Bearer", ignoreCase = true))
        json.put("expiresAt", System.currentTimeMillis() + json.getLong("expires_in").coerceIn(1, 86_400) * 1000)
        vault.save(profile.provider, profile.id, Base64.getEncoder().encodeToString(json.toString().toByteArray()))
    }
    private suspend fun postToken(body: FormBody): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(endpoint.base + "/auth/token").post(body).build()).execute().use { r ->
            if (r.code in listOf(400, 401, 403)) {
                vault.clear(profile.provider, profile.id)
                throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
            }
            TelemetryHttpPolicy.check(r)
            JSONObject(r.body?.string() ?: "{}")
        }
    }
    suspend fun revoke() {
        val stored = vault.load(profile.provider, profile.id)
        try {
            val j = stored?.let { runCatching { JSONObject(String(Base64.getDecoder().decode(it))) }.getOrNull() }
            j?.optString("refresh_token")?.takeIf { it.isNotBlank() }?.let { token ->
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(endpoint.base + "/auth/revoke")
                        .post(FormBody.Builder().add("token", token).build()).build()).execute().close()
                }
            }
        } finally { vault.clear(profile.provider, profile.id) }
    }
    companion object {
        const val CLIENT_ID = "https://pittech-privacy-production.up.railway.app"
        const val REDIRECT = CLIENT_ID + "/ha/auth_callback"
        fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun authorizationUrl(server: String, state: String): String = (HomeAssistantEndpoint(server).base + "/auth/authorize").toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID).addQueryParameter("redirect_uri", REDIRECT).addQueryParameter("state", state).build().toString()
        fun authorizationCode(url: String, state: String): String? {
            val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
            val target = REDIRECT.toHttpUrl()
            if (parsed.scheme != target.scheme || parsed.host != target.host || parsed.port != target.port || parsed.encodedPath != target.encodedPath) return null
            require(parsed.queryParameterValues("state") == listOf(state)) { "Authorization response did not match this connection." }
            require(parsed.queryParameterValues("code").size == 1)
            return parsed.queryParameter("code")?.takeIf { it.isNotBlank() }
        }
    }
}

internal class HomeAssistantFactory : TelemetryConnectorFactory {
    override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String): ReadOnlyTelemetryConnector {
        HomeAssistantEndpoint(profile.endpoint)
        if (secret.isNotBlank()) TelemetryTokenStore(context).save(profile.provider, profile.id, secret)
        return restore(context, profile)
    }
    override suspend fun restore(context: Context, profile: TelemetryProfile): ReadOnlyTelemetryConnector {
        val session = HomeAssistantSession(context, profile)
        return HomeAssistantConnector(profile.endpoint, session.token(), tokenProvider = { session.token() }, tokenRefresh = { session.token(true) })
    }
}
