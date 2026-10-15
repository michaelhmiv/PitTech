package com.pittech.data

import kotlinx.coroutines.runBlocking
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
import java.util.UUID

class RelayClientTest {
    private val json = "application/json".toMediaType()
    private fun response(request: Request, status: Int, body: ByteArray, type: String = "application/json", hash: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
        .body(body.toResponseBody(type.toMediaType())).apply { if (hash != null) header("X-Content-SHA256", hash) }.build()
    @Test fun uploadUsesSavedIdentityAndBytesAndAcceptsCanonicalReceipt() = runBlocking {
        val file = File.createTempFile("relay-unit-", ".pittech").apply { writeText("immutable selected native export") }
        try {
            val value = RelayOwnedShare(UUID.randomUUID().toString(), "draft", 1, "A".repeat(43), RelayProtocol.hash(file), "Selected pork")
            var progress = 0L; var calls = 0
            val receipt = JSONObject().put("code", "ABCD-1234-5678-9EF0").put("sha256", "b".repeat(64)).put("bytes", 1024).put("kind", "recipe").put("dishes", 1).put("expiresAt", "2027-04-13T12:34:56.789Z")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++; val request = chain.request()
                assertEquals("https://api.pittech.app/packs", request.url.toString()); assertEquals("Bearer " + value.owner, request.header("Authorization")); assertEquals(value.operation, request.header("Idempotency-Key"))
                val written = Buffer(); request.body!!.writeTo(written); assertArrayEquals(file.readBytes(), written.readByteArray())
                response(request, 201, receipt.toString().toByteArray())
            }.build()
            val accepted = RelayClient(client = client).upload(value, file) { sent, _ -> progress = sent }
            assertEquals(file.length(), progress); assertEquals("b".repeat(64), accepted.sha256); assertNotEquals(value.inputSha, accepted.sha256); assertEquals(1, calls)
        } finally { file.delete() }
    }
    @Test fun downloadVerifiesChecksumAndDeletesCorruptOrTruncatedBytes() = runBlocking {
        val bytes = "bounded selected pack".toByteArray()
        for (valid in listOf(true, false)) {
            val file = File.createTempFile("relay-unit-download-", ".pittech")
            try {
                val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), 200, bytes, "application/octet-stream", if (valid) RelayProtocol.hash(bytes) else "b".repeat(64)) }.build()
                if (valid) { RelayClient(client = client).download("abcd123456789ef0", file) { _, _ -> }; assertArrayEquals(bytes, file.readBytes()) }
                else { try { RelayClient(client = client).download("abcd123456789ef0", file) { _, _ -> }; fail("Corrupt bytes accepted") } catch (_: IllegalArgumentException) { }; assertFalse(file.exists()) }
            } finally { file.delete() }
        }
    }
    @Test fun redirectsDoNotForwardOwnerCapabilityAndForeignOriginsNeverOpen() = runBlocking {
        val file = File.createTempFile("relay-unit-redirect-", ".pittech").apply { writeText("selected pack") }
        try {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain -> calls++; response(chain.request(), 302, ByteArray(0)).newBuilder().header("Location", "https://attacker.invalid/").build() }.build()
            val value = RelayOwnedShare(UUID.randomUUID().toString(), "draft", 1, "A".repeat(43), RelayProtocol.hash(file), "Selected pork")
            try { RelayClient(client = client).upload(value, file) { _, _ -> }; fail("Redirect followed") } catch (_: java.io.IOException) { }
            assertEquals(1, calls)
            try { RelayClient(base = "https://api.pittech.app.attacker.invalid", client = client).upload(value, file) { _, _ -> }; fail("Foreign origin accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(1, calls)
        } finally { file.delete() }
    }
}
