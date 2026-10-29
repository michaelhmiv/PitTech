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

/**
 * Issue #255: the lifecycle body guard accepts exactly "application/json".
 * CommunityClient.call used to build its request body from a Kotlin String,
 * which makes OkHttp append "; charset=utf-8" — so every report and block
 * 415'd on the server, and CommunityControls read that as a definite
 * rejection. CommunityControlsTest mocks the CommunityAPI interface, so the
 * real client was never exercised; this suite runs the real CommunityClient
 * through a recording interceptor (the PrivateSyncClientTest idiom), so the
 * recorded request is byte-for-byte the one the server would see.
 */
class CommunityClientTest {
    private val access = "A".repeat(43)
    private val session = "A".repeat(43)
    private data class Recorded(val request: Request, val body: ByteArray)
    private fun client(handler: (Request) -> Response): Pair<CommunityClient, MutableList<Recorded>> {
        val recorded = mutableListOf<Recorded>()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor(Interceptor { chain ->
            val body = chain.request().body?.let { source -> val buffer = Buffer(); source.writeTo(buffer); buffer.readByteArray() } ?: ByteArray(0)
            recorded += Recorded(chain.request(), body)
            handler(chain.request())
        }).build()
        return CommunityClient(http) to recorded
    }
    private fun response(request: Request, code: Int, body: String): Response {
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toByteArray().toResponseBody("application/json".toMediaType())).build()
    }

    @Test fun reportPostsExactBytesWithExactlyApplicationJsonContentType() = runTest {
        val body = JSONObject().put("operationId", "11111111-2222-4333-8444-555555555555").put("itemId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").put("assetId", JSONObject.NULL).put("kind", "content").put("reason", "spam").put("details", "").toString()
        val (api, recorded) = client { request -> response(request, 200, JSONObject().put("reportId", "cccccccc-dddd-4eee-8fff-000000000000").put("status", "received").toString()) }
        val reply = api.call("/v1/reports", body, access, session)
        assertEquals("received", reply.getString("status"))
        val request = recorded.single().request
        assertEquals("POST", request.method)
        assertEquals("/v1/reports", request.url.encodedPath)
        assertEquals("application/json", request.body?.contentType()?.toString())
        assertArrayEquals(body.toByteArray(), recorded.single().body)
    }

    @Test fun blockPostsExactBytesWithExactlyApplicationJsonContentType() = runTest {
        val body = JSONObject().put("operationId", "11111111-2222-4333-8444-555555555555").put("alias", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").put("blocked", true).toString()
        val (api, recorded) = client { request -> response(request, 200, JSONObject().put("alias", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").put("blocked", true).toString()) }
        val reply = api.call("/v1/me/blocks", body, access, session)
        assertTrue(reply.getBoolean("blocked"))
        val request = recorded.single().request
        assertEquals("POST", request.method)
        assertEquals("/v1/me/blocks", request.url.encodedPath)
        assertEquals("application/json", request.body?.contentType()?.toString())
        assertArrayEquals(body.toByteArray(), recorded.single().body)
    }
}
