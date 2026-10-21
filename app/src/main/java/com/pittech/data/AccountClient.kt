package com.pittech.data

import com.pittech.online.OnlineConfiguration

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

internal fun interface AccountAPI { suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject }
internal class AccountClient(client: OkHttpClient = OkHttpClient()) : AccountAPI {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).callTimeout(30,TimeUnit.SECONDS).build()
    override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
        require(path in setOf("/v1/auth/challenges","/v1/auth/verify","/v1/auth/refresh","/v1/auth/logout","/v1/me","/v1/me/deletion","/v1/deletion/recover","/v1/deletion/status","/v1/auth/provider/challenges","/v1/auth/provider/verify","/v1/me/identities"))
        val request = Request.Builder().url("${OnlineConfiguration.current.apiOrigin}$path").header("Accept","application/json").header("Cache-Control","no-store")
        if (body != null) { val bytes = body.toString().toByteArray(); require(bytes.size <= 8192); request.post(bytes.toRequestBody("application/json".toMediaType())) }
        if (access != null) { require(AccountProtocol.credential(access)); request.header("Authorization","Bearer $access") }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build()); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(AccountFailure()) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw AccountFailure(it.code)
                            val source = it.body?.source(); val buffer = okio.Buffer()
                            if(source != null) while(buffer.size < 8193 && source.read(buffer,minOf(8192L,8193L-buffer.size)) != -1L) { }
                            require(buffer.size <= 8192)
                            if(buffer.size == 0L) JSONObject() else JSONObject(String(buffer.readByteArray(),Charsets.UTF_8))
                        }
                        if(continuation.isActive) continuation.resume(result)
                    } catch(e: Throwable) { if(continuation.isActive) continuation.resumeWithException(if(e is AccountFailure) e else AccountFailure()) }
                }
            })
        }
    }
}
