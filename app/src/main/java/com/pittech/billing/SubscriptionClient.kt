package com.pittech.billing

import kotlinx.coroutines.suspendCancellableCoroutine
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

internal class SubscriptionFailure(val status: Int = 0) : Exception(
    when (status) {
        401 -> "Sign in again to verify this purchase. Your local cookbook is unchanged."
        409 -> "This purchase is already bound to a different PitTech account."
        503 -> "Subscriptions are not available in this build. Your local cookbook works without one."
        else -> "Couldn't reach PitTech. Retry the same request when you're online."
    },
)

internal fun interface SubscriptionAPI {
    suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject
}

/** Backend verification transport. Every purchase proof goes to the server;
 * the client never sets entitlement from a local purchase callback alone. */
internal class SubscriptionClient(client: OkHttpClient = OkHttpClient()) : SubscriptionAPI {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
        require(path in setOf("/v1/me/entitlement", "/v1/me/entitlement/reconcile", "/v1/me/purchases/verify", "/v1/me/purchases/restore", "/v1/me/purchases/release"))
        val request = Request.Builder().url("https://api.pittech.app$path").header("Accept", "application/json").header("Cache-Control", "no-store")
        if (path == "/v1/me/entitlement") {
            require(body == null)
            request.get()
        } else {
            val bytes = (body ?: JSONObject()).toString().toByteArray()
            require(bytes.size <= 65536)
            request.post(bytes.toRequestBody("application/json".toMediaType()))
        }
        require(access != null && com.pittech.data.AccountProtocol.credential(access))
        request.header("Authorization", "Bearer $access")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(SubscriptionFailure())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw SubscriptionFailure(it.code)
                            val source = it.body?.source()
                            val buffer = okio.Buffer()
                            if (source != null) while (buffer.size < 65537 && source.read(buffer, minOf(8192L, 65537L - buffer.size)) != -1L) { }
                            require(buffer.size <= 65536)
                            if (buffer.size == 0L) JSONObject() else JSONObject(String(buffer.readByteArray(), Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(if (e is SubscriptionFailure) e else SubscriptionFailure())
                    }
                }
            })
        }
    }
}
