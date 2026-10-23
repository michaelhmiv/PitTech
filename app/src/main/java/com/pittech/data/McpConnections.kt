package com.pittech.data

import com.pittech.online.OnlineConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MCP connection management (issue #114, M1b): the owner's own controls
 * for AI-assistant connections, exercised with the native account
 * session. The server enforces the same boundary this client assumes —
 * a grant token is never a session and can never call these routes.
 * A created token exists in memory exactly once (the create response)
 * and is never persisted on the device.
 */

internal fun interface McpAPI { suspend fun call(method: String, path: String, body: JSONObject?, access: String?): JSONObject }

internal class McpClient(client: OkHttpClient = OkHttpClient()) : McpAPI {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun call(method: String, path: String, body: JSONObject?, access: String?): JSONObject {
        require(method in setOf("GET", "POST", "DELETE"))
        require(path == "/v1/me/mcp/grants" || path == "/v1/me/mcp/oauth/approve" ||
            Regex("^/v1/me/mcp/grants/[0-9a-fA-F-]{36}$").matches(path)) { "Unlisted MCP path" }
        val request = Request.Builder().url("${OnlineConfiguration.current.apiOrigin}$path")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
        when (method) {
            "GET" -> request.get()
            "DELETE" -> request.delete()
            else -> { val bytes = (body ?: JSONObject()).toString().toByteArray(); require(bytes.size <= 8192); request.post(bytes.toRequestBody("application/json".toMediaType())) }
        }
        if (access != null) { require(AccountProtocol.credential(access)); request.header("Authorization", "Bearer $access") }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build()); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(AccountFailure()) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw AccountFailure(it.code)
                            val source = it.body?.source(); val buffer = okio.Buffer()
                            if (source != null) while (buffer.size < 65537 && source.read(buffer, minOf(65536L, 65537L - buffer.size)) != -1L) { }
                            require(buffer.size <= 65536)
                            if (buffer.size == 0L) JSONObject() else JSONObject(String(buffer.readByteArray(), Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Throwable) { if (continuation.isActive) continuation.resumeWithException(if (e is AccountFailure) e else AccountFailure()) }
                }
            })
        }
    }
}

internal data class McpGrantView(
    val id: String, val nickname: String, val scopes: List<String>,
    val createdAt: Long, val expiresAt: Long, val revokedAt: Long?, val lastUsedAt: Long?,
    val clientId: String?,
) {
    fun status(now: Long): String = when {
        revokedAt != null -> "Disconnected"
        expiresAt <= now -> "Expired"
        else -> "Connected"
    }
    val active: Boolean get() = revokedAt == null
    companion object {
        fun parse(o: JSONObject): McpGrantView {
            val scopes = o.optJSONArray("scopes")?.let { array -> List(array.length()) { array.optString(it) } } ?: emptyList()
            return McpGrantView(
                o.getString("id"), o.getString("nickname"), scopes,
                o.getLong("createdAt"), o.getLong("expiresAt"),
                if (o.isNull("revokedAt")) null else o.getLong("revokedAt"),
                if (o.isNull("lastUsedAt")) null else o.getLong("lastUsedAt"),
                if (o.isNull("clientId")) null else o.optString("clientId").ifEmpty { null },
            )
        }
    }
}

/** Plain-language scope labels for the connection screens. Draft copy
 * mirroring the server's consent labels; the owner reviews wording. */
internal object McpScopeCatalog {
    data class Entry(val id: String, val label: String, val detail: String)
    val all = listOf(
        Entry("library:read", "See your library", "Meals, recipes, logs and plans can be read."),
        Entry("library:write", "Add and edit", "New meals, recipes and notes can be created and edited."),
        Entry("library:delete", "Delete", "Meals, recipes and notes can be deleted."),
        Entry("media:read", "See photos", "Cook photos can be viewed."),
        Entry("media:write", "Add photos", "Photos can be added to your cooks."),
        Entry("planning:write", "Plans", "Cook plans can be created and changed."),
        Entry("device_actions:request", "Ask for device actions", "The assistant can ask; you always confirm in PitTech."),
        Entry("exports:read", "Exports", "Exports of your data can be downloaded."),
        Entry("imports:write", "Imports", "Data can be imported into your library."),
        Entry("community:read", "Community benchmarks", "Anonymous community comparisons can be viewed."),
    )
    /** Preselected for a new connection: everyday help without delete. */
    val defaultSelected = setOf("library:read", "library:write", "media:read", "media:write", "planning:write", "community:read")
    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}

internal data class McpConnectionsPresentation(
    val loaded: Boolean = false,
    val available: Boolean = true,
    val grants: List<McpGrantView> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    /** The one-time token from a create, held in memory only until dismissed. */
    val createdToken: String? = null,
    val createdNickname: String? = null,
)

internal class McpConnectionsManager(
    private val account: AccountManager,
    private val scope: CoroutineScope,
    private val api: McpAPI = McpClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _presentation = MutableStateFlow(McpConnectionsPresentation())
    val presentation = _presentation.asStateFlow()
    private val mutex = Mutex()

    private fun publish(next: McpConnectionsPresentation) { _presentation.value = next }
    private fun perform(operation: suspend () -> Unit) {
        if (_presentation.value.busy) return
        publish(_presentation.value.copy(busy = true, message = null))
        scope.launch {
            mutex.withLock {
                try { operation() }
                catch (e: CancellationException) { publish(_presentation.value.copy(busy = false, message = "Request interrupted. Try again.")); throw e }
                catch (e: AccountFailure) {
                    publish(_presentation.value.copy(busy = false, message = when (e.status) {
                        401 -> "Sign in again to manage AI connections."
                        404 -> null // handled by callers that mark availability
                        else -> "Couldn't reach PitTech. Your connections are unchanged."
                    }))
                }
                catch (e: Exception) { publish(_presentation.value.copy(busy = false, message = "Something went wrong. Your connections are unchanged.")) }
            }
        }
    }

    private suspend fun loadLocked(token: String) {
        try {
            val reply = api.call("GET", "/v1/me/mcp/grants", null, token)
            val grants = reply.optJSONArray("grants")?.let { array -> List(array.length()) { McpGrantView.parse(array.getJSONObject(it)) } } ?: emptyList()
            publish(_presentation.value.copy(loaded = true, available = true, grants = grants, busy = false))
        } catch (e: AccountFailure) {
            if (e.status == 404) { publish(_presentation.value.copy(loaded = true, available = false, busy = false)); return }
            throw e
        }
    }

    fun refresh() = perform {
        if (!account.presentation.value.signedIn) { publish(McpConnectionsPresentation(loaded = true)); return@perform }
        loadLocked(account.access())
    }

    fun create(nickname: String, scopes: Set<String>, expiresInDays: Int) = perform {
        require(nickname.isNotBlank() && scopes.isNotEmpty() && expiresInDays in setOf(30, 90, 365))
        val token = account.access()
        val body = JSONObject().put("nickname", nickname.trim()).put("scopes", org.json.JSONArray(scopes.toList())).put("expiresInDays", expiresInDays)
        val reply = api.call("POST", "/v1/me/mcp/grants", body, token)
        val created = reply.getString("token")
        val grant = McpGrantView.parse(reply.getJSONObject("grant"))
        publish(_presentation.value.copy(
            grants = listOf(grant) + _presentation.value.grants.filterNot { it.id == grant.id },
            createdToken = created, createdNickname = grant.nickname, busy = false,
            message = "Connection created. Copy its token now — it is shown only once.",
        ))
    }

    fun dismissCreatedToken() { publish(_presentation.value.copy(createdToken = null, createdNickname = null, message = null)) }

    fun revoke(id: String) = perform {
        val token = account.access()
        api.call("DELETE", "/v1/me/mcp/grants/$id", null, token)
        publish(_presentation.value.copy(
            grants = _presentation.value.grants.map { if (it.id == id) it.copy(revokedAt = now()) else it },
            busy = false, message = "Connection disconnected.",
        ))
    }

    fun revokeAll() = perform {
        val token = account.access()
        api.call("DELETE", "/v1/me/mcp/grants", null, token)
        publish(_presentation.value.copy(
            grants = _presentation.value.grants.map { if (it.revokedAt == null) it.copy(revokedAt = now()) else it },
            busy = false, message = "All connections disconnected.",
        ))
    }

    fun approve(userCode: String) = perform {
        val code = userCode.trim().uppercase()
        require(code.isNotEmpty())
        val token = account.access()
        try {
            api.call("POST", "/v1/me/mcp/oauth/approve", JSONObject().put("userCode", code), token)
            publish(_presentation.value.copy(busy = false, message = "Approved. The assistant finishes connecting in your browser."))
            loadLocked(token)
        } catch (e: AccountFailure) {
            if (e.status == 404) { publish(_presentation.value.copy(busy = false, message = "That code isn't valid or has expired. Check the code in your browser and try again.")); return@perform }
            throw e
        }
    }
}
