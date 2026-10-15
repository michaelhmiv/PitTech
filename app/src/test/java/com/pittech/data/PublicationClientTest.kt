package com.pittech.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PublicationClientTest {
    @Test fun archiveUpdateStreamsExactBytesAndPinsRevisionWithoutRedirectsCookiesOrDiagnosticLeaks() = runBlocking {
        val file=File.createTempFile("publication-client-",".zip"); val bytes="selected public bytes".toByteArray(); file.writeBytes(bytes)
        val op=PublicationOperation("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa","bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb","PUT","/v1/recipes/cccccccc-cccc-4ccc-cccc-cccccccccccc","application/zip",RelayProtocol.hash(bytes),bytes.size,"dddddddd-dddd-4ddd-dddd-dddddddddddd")
        try {
            for(status in listOf(200,302,401,409)) {
                var calls=0
                val client=OkHttpClient.Builder().addInterceptor { chain ->
                    calls++; val request=chain.request(); assertEquals("https://api.pittech.app"+op.path,request.url.toString()); assertEquals("PUT",request.method)
                    assertEquals(op.id,request.header("Idempotency-Key")); assertEquals(op.expectedRevision,request.header("If-Match")); assertEquals("Bearer "+"A".repeat(43),request.header("Authorization")); assertNull(request.header("Cookie"))
                    val body=checkNotNull(request.body); assertEquals("application/zip",body.contentType().toString()); val buffer=Buffer(); body.writeTo(buffer); assertArrayEquals(bytes,buffer.readByteArray())
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").header("Location","https://attacker.invalid/").header("Set-Cookie","sensitive=discard").body((if(status==200)"{}" else "private-provider-diagnostic").toResponseBody()).build()
                }.build()
                try { PublicationClient(client).write(op,file,"A".repeat(43)); assertEquals(200,status) }
                catch(e: AccountFailure) { assertNotEquals(200,status); assertEquals(status,e.status); assertFalse(e.message!!.contains("private-provider-diagnostic")) }
                assertEquals(1,calls)
            }
            val huge=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body("x".repeat(8193).toResponseBody()).build() }.build()
            try { PublicationClient(huge).write(op,file,"A".repeat(43)); fail("Oversized response accepted") } catch(_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
}
