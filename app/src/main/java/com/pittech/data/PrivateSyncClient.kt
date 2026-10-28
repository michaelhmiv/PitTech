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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PrivateSyncHttpError(val status: Int, val code: String) : Exception(messageFor(status, code)) {
    val conflict get() = status == 409
    val unauthorized get() = status == 401

    companion object {
        /**
         * Flowing Slice 2 (Slice 0 audit finding): a server answer is never
         * "could not reach PitTech" — that sentence is reserved for genuine
         * transport failure (the client's IOException path). Every HTTP
         * status class says what actually happened, with the status named,
         * so a busy server, a contract rejection, an unmounted route, or a
         * refused redirect can no longer masquerade as a reachability
         * failure. Both fetch() and commit() publish this message verbatim.
         */
        internal fun messageFor(status: Int, code: String): String = when {
            code == "redirect_rejected" || status in 300..399 ->
                "Private sync was redirected, which it does not follow. Your local journal is unchanged."
            status == 401 -> "Sign in again to reconnect private sync. Your local journal is unchanged."
            status == 409 -> "Another device changed the backup first. Fetch, merge and retry."
            status == 413 -> "This library exceeds the sync size limit."
            status == 422 -> "This library could not be applied safely. Your journal is unchanged."
            status == 429 -> "Private sync is busy. Retry later."
            status == 503 -> "PitTech's sync service is busy right now. Retry later — your local journal is unchanged."
            status in 500..599 -> "PitTech's sync service returned an error ($status). Retry later — your local journal is unchanged."
            else -> "The server could not accept this sync ($status). Your local journal is unchanged."
        }
    }
}

internal interface PrivateSyncAPI {
    suspend fun snapshot(access: String): JSONObject
    suspend fun commit(body: ByteArray, mutationId: String, access: String): JSONObject
    suspend fun beginPhoto(assetId: String, bytes: Long, mime: String, sha: String, access: String): JSONObject
    suspend fun photoStatus(assetId: String, access: String): JSONObject
    suspend fun putPart(assetId: String, index: Int, chunk: ByteArray, access: String): JSONObject
    suspend fun completePhoto(assetId: String, access: String): JSONObject
    suspend fun abandonPhoto(assetId: String, access: String): JSONObject
    suspend fun downloadPhoto(assetId: String, to: File, access: String): File
}

internal class PrivateSyncClient(client: OkHttpClient = OkHttpClient()) : PrivateSyncAPI {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).callTimeout(120, TimeUnit.SECONDS).build()
    private fun request(path: String, access: String): Request.Builder {
        require(AccountProtocol.credential(access))
        return Request.Builder().url("${OnlineConfiguration.current.apiOrigin}$path").header("Authorization", "Bearer $access").header("Accept", "application/json").header("Cache-Control", "no-store")
    }
    private fun photoPath(assetId: String, suffix: String = ""): String {
        require(publicationUuid(assetId) && assetId == assetId.lowercase())
        return "/v1/sync/photos/$assetId$suffix"
    }
    override suspend fun snapshot(access: String): JSONObject = call(request("/v1/sync", access).get().build(), PrivateSyncProtocol.MAX_SNAPSHOT_BYTES + 1024)
    override suspend fun commit(body: ByteArray, mutationId: String, access: String): JSONObject {
        require(publicationUuid(mutationId) && mutationId == mutationId.lowercase() && body.size in 1..PrivateSyncProtocol.MAX_SNAPSHOT_BYTES)
        return call(request("/v1/sync", access).header("Idempotency-Key", mutationId).put(body.toRequestBody("application/json".toMediaType())).build(), 8192)
    }
    override suspend fun beginPhoto(assetId: String, bytes: Long, mime: String, sha: String, access: String): JSONObject {
        require(bytes in 1..PrivateSyncProtocol.MAX_PHOTO_BYTES && mime in setOf("image/jpeg", "image/png", "image/webp") && sha.matches(Regex("[a-f0-9]{64}")))
        val body = JSONObject().put("assetId", assetId).put("bytes", bytes).put("mimeType", mime).put("sha256", sha).toString().toByteArray()
        return call(Request.Builder().url("${OnlineConfiguration.current.apiOrigin}/v1/sync/photos").header("Authorization", "Bearer $access").header("Accept", "application/json").header("Cache-Control", "no-store").header("Idempotency-Key", assetId).post(body.toRequestBody("application/json".toMediaType())).build(), 8192)
    }
    override suspend fun photoStatus(assetId: String, access: String): JSONObject = call(request(photoPath(assetId, "/status"), access).get().build(), 8192)
    override suspend fun putPart(assetId: String, index: Int, chunk: ByteArray, access: String): JSONObject {
        require(index in 0 until 16 && chunk.size in 1..PrivateSyncProtocol.CHUNK_BYTES)
        return call(request(photoPath(assetId, "/parts/$index"), access).put(chunk.toRequestBody("application/octet-stream".toMediaType())).build(), 8192)
    }
    override suspend fun completePhoto(assetId: String, access: String): JSONObject {
        return call(request(photoPath(assetId, "/complete"), access).post("{}".toRequestBody("application/json".toMediaType())).build(), 8192)
    }
    override suspend fun abandonPhoto(assetId: String, access: String): JSONObject {
        val body = JSONObject().put("confirm", "abandon-private-photo").toString().toByteArray()
        return call(request(photoPath(assetId), access).delete(body.toRequestBody("application/json".toMediaType())).build(), 8192)
    }
    override suspend fun downloadPhoto(assetId: String, to: File, access: String): File {
        try {
            return download(request(photoPath(assetId), access).header("Accept", "application/octet-stream").get().build(), to)
        } catch (e: Throwable) { to.delete(); throw e }
    }
    private suspend fun call(request: Request, limit: Int): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Private sync could not reach PitTech. Your local journal is unchanged.")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw httpError(it)
                        val source = it.body?.source(); val buffer = okio.Buffer()
                        if (source != null) while (buffer.size <= limit && source.read(buffer, minOf(65536L, limit + 1L - buffer.size)) != -1L) { }
                        require(buffer.size <= limit) { "The sync response exceeded its supported size." }
                        JSONObject(String(buffer.readByteArray(), Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private suspend fun download(request: Request, to: File): File = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Private sync could not reach PitTech. Your local journal is unchanged.")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw httpError(it)
                        val body = checkNotNull(it.body)
                        val length = body.contentLength()
                        require(length in 1..PrivateSyncProtocol.MAX_PHOTO_BYTES) { "The downloaded photo exceeds the supported size." }
                        val expected = it.header("X-Content-SHA256").orEmpty()
                        require(expected.matches(Regex("[a-f0-9]{64}"))) { "The photo checksum is unavailable." }
                        var received = 0L
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        body.byteStream().use { input -> to.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                received += count; require(received <= length)
                                digest.update(buffer, 0, count); output.write(buffer, 0, count)
                            }
                        } }
                        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                        require(received == length && hash == expected) { "The download was incomplete. Retry before applying." }
                    }
                    if (continuation.isActive) continuation.resume(to) else to.delete()
                } catch (e: Exception) { to.delete(); if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private fun httpError(response: Response): PrivateSyncHttpError {
        val status = response.code
        if (response.header("Location") != null) return PrivateSyncHttpError(status, "redirect_rejected")
        val code = try {
            val source = response.body?.source(); val buffer = okio.Buffer()
            if (source != null) source.read(buffer, 2048L)
            JSONObject(String(buffer.readByteArray(), Charsets.UTF_8)).optJSONObject("error")?.optString("code", "").orEmpty()
        } catch (_: Exception) { "" }
        return PrivateSyncHttpError(status, code)
    }
}
