package com.pittech.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrivateSyncClientTest {
    private val access = "A".repeat(43)
    private val mutation = "11111111-2222-4333-8444-555555555555"
    private val asset = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private data class Recorded(val request: Request, val body: ByteArray)
    private fun client(handler: (Request) -> Response): Pair<PrivateSyncClient, MutableList<Recorded>> {
        val recorded = mutableListOf<Recorded>()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor(Interceptor { chain ->
            val body = chain.request().body?.let { source -> val buffer = Buffer(); source.writeTo(buffer); buffer.readByteArray() } ?: ByteArray(0)
            recorded += Recorded(chain.request(), body)
            handler(chain.request())
        }).build()
        return PrivateSyncClient(http) to recorded
    }
    private fun response(request: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.body(body.toByteArray().toResponseBody("application/json".toMediaType())).build()
    }

    @Test fun snapshotUsesGetWithBearerAndNoStore() = runTest {
        val (api, recorded) = client { request -> response(request, 200, JSONObject().put("version", 0).put("envelope", JSONObject.NULL).toString()) }
        val reply = api.snapshot(access)
        assertEquals(0, reply.getLong("version"))
        val request = recorded.single().request
        assertEquals("GET", request.method); assertEquals("/v1/sync", request.url.encodedPath)
        assertEquals("Bearer $access", request.header("Authorization")); assertEquals("no-store", request.header("Cache-Control"))
    }

    @Test fun commitCarriesBaseVersionAndIdempotencyIdentity() = runTest {
        val body = JSONObject().put("contractVersion", 1).put("baseVersion", 3).put("mutationId", mutation).toString().toByteArray()
        val (api, recorded) = client { request -> response(request, 200, JSONObject().put("version", 4).put("mutationId", mutation).put("sha256", RelayProtocol.hash(body)).toString()) }
        val receipt = api.commit(body, mutation, access)
        assertEquals(4, receipt.getLong("version"))
        val request = recorded.single().request
        assertEquals("PUT", request.method); assertEquals("/v1/sync", request.url.encodedPath)
        assertEquals(mutation, request.header("Idempotency-Key"))
        assertArrayEquals(body, recorded.single().body)
    }

    @Test fun conflictUnauthorizedAndRedirectMapHonestly() = runTest {
        val (conflictApi, _) = client { request -> response(request, 409, JSONObject().put("error", JSONObject().put("code", "sync_version_changed").put("message", "stale")).toString()) }
        try { conflictApi.commit("{}".toByteArray(), mutation, access); fail() } catch (e: PrivateSyncHttpError) { assertTrue(e.conflict); assertEquals("sync_version_changed", e.code) }
        val (deniedApi, _) = client { request -> response(request, 401, "{}") }
        try { deniedApi.snapshot(access); fail() } catch (e: PrivateSyncHttpError) { assertTrue(e.unauthorized) }
        val (redirectApi, _) = client { request -> response(request, 302, "{}", mapOf("Location" to "https://evil.invalid/")) }
        try { redirectApi.snapshot(access); fail() } catch (e: PrivateSyncHttpError) { assertEquals("redirect_rejected", e.code) }
    }

    @Test fun oversizedSnapshotResponsesAreRejected() = runTest {
        val (api, _) = client { request -> response(request, 200, "\"" + "x".repeat(PrivateSyncProtocol.MAX_SNAPSHOT_BYTES + 2048) + "\"") }
        try { api.snapshot(access); fail() } catch (_: Exception) { }
    }

    @Test fun photoRoutesUseOneMebibyteChunksWithPartIndex() = runTest {
        val chunk = ByteArray(PrivateSyncProtocol.CHUNK_BYTES) { it.toByte() }
        val (api, recorded) = client { request ->
            val status = JSONObject().put("assetId", asset).put("bytes", chunk.size).put("receivedParts", org.json.JSONArray()).put("complete", false)
            response(request, 200, status.toString())
        }
        api.beginPhoto(asset, chunk.size.toLong(), "image/jpeg", "0".repeat(64), access)
        api.photoStatus(asset, access)
        api.putPart(asset, 0, chunk, access)
        api.completePhoto(asset, access)
        assertEquals(listOf("POST", "GET", "PUT", "POST"), recorded.map { it.request.method })
        assertEquals("/v1/sync/photos", recorded[0].request.url.encodedPath)
        assertEquals(asset, recorded[0].request.header("Idempotency-Key"))
        assertEquals("/v1/sync/photos/$asset/status", recorded[1].request.url.encodedPath)
        assertEquals("/v1/sync/photos/$asset/parts/0", recorded[2].request.url.encodedPath)
        assertEquals(chunk.size, recorded[2].body.size)
        assertEquals("/v1/sync/photos/$asset/complete", recorded[3].request.url.encodedPath)
    }

    @Test fun abandonRequiresExplicitConfirmation() = runTest {
        val (api, recorded) = client { request -> response(request, 200, JSONObject().put("abandoned", true).toString()) }
        api.abandonPhoto(asset, access)
        val request = recorded.single().request
        assertEquals("DELETE", request.method); assertEquals("/v1/sync/photos/$asset", request.url.encodedPath)
        assertEquals("abandon-private-photo", JSONObject(String(recorded.single().body, Charsets.UTF_8)).getString("confirm"))
    }

    /**
     * Flowing Slice 2 (Slice 0 audit): every server answer class must say
     * what it is. The reachability sentence is reserved for transport
     * failure; no HTTP status may fall through to it.
     */
    @Test fun serverAnswersNeverClaimReachability() {
        val reachability = "could not reach PitTech"
        for (status in listOf(300, 301, 302, 400, 401, 403, 404, 409, 413, 415, 422, 429, 500, 502, 503, 504)) {
            val message = PrivateSyncHttpError(status, "").message.orEmpty()
            assertFalse("status $status must not read as a reachability failure: $message", message.contains(reachability))
        }
        // The enumerated statuses keep their established copy.
        assertEquals("Sign in again to reconnect private sync. Your local journal is unchanged.", PrivateSyncHttpError(401, "").message)
        assertEquals("Another device changed the backup first. Fetch, merge and retry.", PrivateSyncHttpError(409, "sync_version_changed").message)
        assertEquals("This library exceeds the sync size limit.", PrivateSyncHttpError(413, "").message)
        assertEquals("This library could not be applied safely. Your journal is unchanged.", PrivateSyncHttpError(422, "").message)
        assertEquals("Private sync is busy. Retry later.", PrivateSyncHttpError(429, "").message)
        // 503 sync_busy gets busy copy of its own, not the reachability line.
        assertEquals("PitTech's sync service is busy right now. Retry later — your local journal is unchanged.", PrivateSyncHttpError(503, "sync_busy").message)
        // Other 5xx answers name the status as a service error.
        assertEquals("PitTech's sync service returned an error (500). Retry later — your local journal is unchanged.", PrivateSyncHttpError(500, "").message)
        // Contract rejections and unmounted routes: the server answered; say so, with the status.
        assertEquals("The server could not accept this sync (400). Your local journal is unchanged.", PrivateSyncHttpError(400, "").message)
        assertEquals("The server could not accept this sync (404). Your local journal is unchanged.", PrivateSyncHttpError(404, "").message)
        assertEquals("The server could not accept this sync (415). Your local journal is unchanged.", PrivateSyncHttpError(415, "").message)
        // Redirects are refused, by status or by the client's own code.
        assertEquals("Private sync was redirected, which it does not follow. Your local journal is unchanged.", PrivateSyncHttpError(302, "").message)
        assertEquals("Private sync was redirected, which it does not follow. Your local journal is unchanged.", PrivateSyncHttpError(200, "redirect_rejected").message)
    }

    /** End-to-end through the client: a live 503 surfaces the busy copy, not "could not reach". */
    @Test fun busyServerSurfacesBusyCopyThroughFetchAndCommit() = runTest {
        val (api, _) = client { request -> response(request, 503, JSONObject().put("error", JSONObject().put("code", "sync_busy")).toString()) }
        try { api.snapshot(access); fail() } catch (e: PrivateSyncHttpError) {
            assertEquals(503, e.status); assertEquals("sync_busy", e.code)
            assertEquals("PitTech's sync service is busy right now. Retry later — your local journal is unchanged.", e.message)
        }
        try { api.commit("{}".toByteArray(), mutation, access); fail() } catch (e: PrivateSyncHttpError) {
            assertEquals("PitTech's sync service is busy right now. Retry later — your local journal is unchanged.", e.message)
        }
        val (rejectedApi, _) = client { request -> response(request, 400, JSONObject().put("error", JSONObject().put("code", "bad_request")).toString()) }
        try { rejectedApi.snapshot(access); fail() } catch (e: PrivateSyncHttpError) {
            assertEquals("The server could not accept this sync (400). Your local journal is unchanged.", e.message)
        }
    }

    @Test fun downloadVerifiesChecksumLengthAndBound() = runTest {
        val bytes = "photo-bytes".toByteArray()
        val sha = RelayProtocol.hash(bytes)
        val target = File.createTempFile("sync-download-", ".bin"); target.delete()
        val (api, recorded) = client { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("")
                .header("X-Content-SHA256", sha).body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }
        api.downloadPhoto(asset, target, access)
        assertArrayEquals(bytes, target.readBytes())
        assertEquals("/v1/sync/photos/$asset", recorded.single().request.url.encodedPath)
        target.delete()
        val (tamperedApi, _) = client { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("")
                .header("X-Content-SHA256", "0".repeat(64)).body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }
        val failed = File.createTempFile("sync-download-", ".bin"); failed.delete()
        try { tamperedApi.downloadPhoto(asset, failed, access); fail() } catch (_: Exception) { }
        assertFalse(failed.exists())
    }
}
