package com.pittech.billing

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CommunityBenchmarksClientTest {
    @Test
    fun boundedTransportRedactsFailuresAndDoesNotFollowRedirectsOrSetCookies(): Unit = runBlocking {
        for (status in listOf(200, 302, 401, 403, 404, 429)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("https://api.pittech.app/v1/community/aggregates", request.url.toString())
                assertEquals("Bearer " + "A".repeat(43), request.header("Authorization"))
                assertNull(request.header("Cookie"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                    .header("Location", "https://attacker.invalid/").header("Set-Cookie", "sensitive=discard")
                    .body((if (status == 200) "{}" else "private-provider-diagnostic").toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                CommunityBenchmarksClient(client).aggregates(null, null, null, "A".repeat(43))
                assertEquals(200, status)
            } catch (e: CommunityBenchmarksFailure) {
                assertEquals(status, e.status)
                assertFalse(e.message!!.contains("private-provider-diagnostic"))
            }
            assertEquals(1, calls)
        }
        val huge = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("x".repeat(262_145).toResponseBody()).build()
        }.build()
        try {
            CommunityBenchmarksClient(huge).aggregates(null, null, null, "A".repeat(43))
            fail("Oversized response accepted")
        } catch (_: CommunityBenchmarksFailure) { }
    }

    @Test
    fun communityFiltersAreAllowlistedAndBearerIsRequired(): Unit = runBlocking {
        val client = CommunityBenchmarksClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("pork-butt-shoulder|medium|method:smoke", request.url.queryParameter("bucket"))
                assertEquals("median-heating-time-v1", request.url.queryParameter("metric"))
                assertEquals("12345678-1234-4234-8234-123456789abc", request.url.queryParameter("runId"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("""{"run":null,"figures":[]}""".toResponseBody("application/json".toMediaType())).build()
            }.build(),
        )
        val filtered = client.aggregates(
            "pork-butt-shoulder|medium|method:smoke",
            "median-heating-time-v1",
            "12345678-1234-4234-8234-123456789abc",
            "A".repeat(43),
        )
        assertEquals(0, filtered.getJSONArray("figures").length())
        try {
            client.aggregates("evil", null, null, "A".repeat(43))
            fail("Invalid bucket accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            client.aggregates(null, "stub-heating-presence", null, "A".repeat(43))
            fail("Invalid metric accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            client.aggregates(null, null, null, null)
            fail("Missing Bearer [REDACTED]")
        } catch (_: IllegalArgumentException) { }
        try {
            client.jobRuns(0, null, "A".repeat(43))
            fail("Invalid limit accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            client.jobRuns(null, "bad", "A".repeat(43))
            fail("Invalid cursor accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
