package com.pittech.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class RelayClient(private val base: String = RelayProtocol.API, private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()) {
    private suspend fun <T> perform(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("The relay could not be reached. Your saved draft and retry are unchanged.")) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use { if (!it.isSuccessful) throw IOException(message(it.code)); val result = read(it); if (continuation.isActive) continuation.resume(result) } }
                catch (e: Throwable) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private fun request(path: String): Request.Builder {
        val origin = java.net.URI(base)
        require(origin.scheme == "https" && origin.host == "api.pittech.app" && origin.port == -1 && origin.rawUserInfo == null && origin.path.isNullOrEmpty() && origin.rawQuery == null && origin.rawFragment == null)
        return Request.Builder().url(base + path).header("Accept", "application/json").header("Cache-Control", "no-store")
    }
    suspend fun upload(value: RelayOwnedShare, source: File, progress: (Long, Long) -> Unit): RelayReceipt {
        require(source.length() in 1..RelayProtocol.MAX_BYTES && RelayProtocol.hash(source) == value.inputSha) { "This upload's saved bytes changed. Your local recipe is unchanged." }
        val size = source.length()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536); var sent = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n); sent += n; progress(sent, size) }
                }
            }
        }
        return perform(request("/packs").header("Authorization", "Bearer " + value.owner).header("Idempotency-Key", value.operation).post(body).build()) { receipt(it) }
    }
    suspend fun download(code: String, destination: File, progress: (Long, Long) -> Unit): File {
        try {
            return perform(request("/packs/" + RelayProtocol.code(code)).header("Accept", "application/octet-stream").get().build()) { response ->
                val body = response.body ?: error("The pack is unavailable.")
                val length = body.contentLength(); require(length in 1..RelayProtocol.MAX_BYTES) { "The downloaded pack exceeds the supported size." }
                val expected = response.header("X-Content-SHA256").orEmpty(); require(expected.matches(Regex("[a-f0-9]{64}"))) { "The pack checksum is unavailable." }
                var received = 0L
                body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; received += n; require(received <= length && received <= RelayProtocol.MAX_BYTES); output.write(buffer, 0, n); progress(received, length) }
                } }
                require(received == length && RelayProtocol.hash(destination) == expected) { "The download was incomplete. Retry before importing." }
                destination
            }
        } catch (e: Throwable) { destination.delete(); throw e }
    }
    suspend fun renew(value: RelayOwnedShare): RelayReceipt = perform(request("/packs/${value.receipt!!.code}/renew").header("Authorization", "Bearer " + value.owner).post(object : RequestBody() { override fun contentType() = null; override fun writeTo(sink: BufferedSink) {} }).build()) { receipt(it) }
    suspend fun revoke(value: RelayOwnedShare) = perform(request("/packs/${value.receipt!!.code}").header("Authorization", "Bearer " + value.owner).delete().build()) { Unit }
    private fun receipt(response: Response): RelayReceipt {
        val source = response.body?.source() ?: error("Receipt unavailable.")
        val buffer = okio.Buffer()
        while (buffer.size < 65537 && source.read(buffer, minOf(8192L, 65537L - buffer.size)) != -1L) { }
        require(buffer.size <= 65536) { "The relay returned an invalid receipt." }
        return RelayReceipt.parse(JSONObject(String(buffer.readByteArray(), Charsets.UTF_8)))
    }
    private fun message(status: Int) = when (status) {
        404 -> "This code is unavailable, expired or revoked. Check it or ask the sender for a new share."
        413 -> "Choose fewer dishes or photos, then try again."
        429 -> "Too many attempts. Wait a minute before retrying."
        409 -> "This retry contains different content. Keep the saved retry or start a new share."
        503 -> "The relay is busy or new uploads are paused. Retry later or share a Cook Pack file."
        else -> "The relay could not complete this request. Your local draft is unchanged."
    }
}
