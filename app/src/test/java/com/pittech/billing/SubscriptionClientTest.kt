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

class SubscriptionClientTest {
    @Test
    fun boundedTransportRedactsFailuresAndDoesNotFollowRedirectsOrSetCookies(): Unit = runBlocking {
        for (status in listOf(200, 302, 401, 429)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("https://api.pittech.app/v1/me/entitlement", request.url.toString())
                assertEquals("Bearer " + "A".repeat(43), request.header("Authorization"))
                assertNull(request.header("Cookie"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                    .header("Location", "https://attacker.invalid/").header("Set-Cookie", "sensitive=discard")
                    .body((if (status == 200) "{}" else "private-provider-diagnostic").toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                SubscriptionClient(client).call("/v1/me/entitlement", null, "A".repeat(43))
                assertEquals(200, status)
            } catch (e: SubscriptionFailure) {
                assertEquals(status, e.status)
                assertFalse(e.message!!.contains("private-provider-diagnostic"))
            }
            assertEquals(1, calls)
        }
        val huge = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("x".repeat(65537).toResponseBody()).build()
        }.build()
        try {
            SubscriptionClient(huge).call("/v1/me/entitlement", null, "A".repeat(43))
            fail("Oversized response accepted")
        } catch (_: SubscriptionFailure) { }
    }

    @Test
    fun billingPathsAreAllowlistedAndBearerIsRequired(): Unit = runBlocking {
        val client = SubscriptionClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build(),
        )
        try {
            client.call("/v1/me", null, "A".repeat(43))
            fail("Non-billing path accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            client.call("/v1/me/entitlement", null, null)
            fail("Missing bearer accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            client.call("/v1/me/entitlement", JSONObject(), "A".repeat(43))
            fail("GET with body accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun onlyTheExactSandboxReadQueryIsAllowedAndItStaysAnAuthenticatedGet(): Unit = runBlocking {
        var calls = 0
        val client = SubscriptionClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("https://api.pittech.app/v1/me/entitlement?environment=sandbox", request.url.toString())
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals("Bearer " + "A".repeat(43), request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("{}".toResponseBody()).build()
        }.build())
        client.call("/v1/me/entitlement?environment=sandbox", null, "A".repeat(43))
        for (path in listOf("/v1/me/entitlement?environment=production", "/v1/me/entitlement?environment=sandbox&environment=production",
            "/v1/me/entitlement?environment=sandbox#fragment", "/v1/me/purchases/verify?environment=sandbox")) {
            try { client.call(path, null, "A".repeat(43)); fail("Unapproved query accepted") } catch (_: IllegalArgumentException) { }
        }
        try { client.call("/v1/me/entitlement?environment=sandbox", JSONObject(), "A".repeat(43)); fail("GET body accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
    }
}
