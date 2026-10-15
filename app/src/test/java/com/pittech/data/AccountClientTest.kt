package com.pittech.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountClientTest {
    @Test fun boundedTransportRedactsFailuresAndDoesNotFollowRedirectsOrSetCookies() = runBlocking {
        for(status in listOf(200,302,401,429)) {
            var calls=0
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                calls++; val request=chain.request()
                assertEquals("https://api.pittech.app/v1/me",request.url.toString())
                assertEquals("Bearer "+"A".repeat(43),request.header("Authorization"))
                assertNull(request.header("Cookie"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                    .header("Location","https://attacker.invalid/").header("Set-Cookie","sensitive=discard")
                    .body((if(status == 200) "{}" else "private-provider-diagnostic").toResponseBody("application/json".toMediaType())).build()
            }.build()
            try { AccountClient(client).call("/v1/me",null,"A".repeat(43)); assertEquals(200,status) }
            catch(e: AccountFailure) { assertNotEquals(200,status); assertFalse(e.message!!.contains("private-provider-diagnostic")); assertEquals(status,e.status) }
            assertEquals(1,calls)
        }
        val huge=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body("x".repeat(8193).toResponseBody()).build() }.build()
        try { AccountClient(huge).call("/v1/me",null,null); fail("Oversized response accepted") } catch(_: AccountFailure) { }
    }
}
