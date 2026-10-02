package com.pittech.devices

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fixed vendor endpoints. STATUS requests only command 90; no arbitrary command body. */
internal enum class TraegerHttpOperation {
    LOGIN, DEVICES, MQTT_CONNECTION, STATUS, REFRESH;
    fun url(thing: String? = null): String = when (this) {
        LOGIN -> "https://auth-api.iot.traegergrills.io/tokens"
        DEVICES -> "https://mobile-iot-api.iot.traegergrills.io/users/self"
        MQTT_CONNECTION -> "https://mobile-iot-api.iot.traegergrills.io/mqtt-connections"
        STATUS -> {
            require(thing != null && Regex("[A-Za-z0-9_-]{1,160}").matches(thing))
            "https://mobile-iot-api.iot.traegergrills.io/things/" + thing + "/commands"
        }
        REFRESH -> "https://cognito-idp.us-west-2.amazonaws.com/"
    }
}
internal fun interface TraegerHttpTransport {
    suspend fun request(operation: TraegerHttpOperation, body: String?, token: String?, thing: String?): PolarisHttpResponse
}
internal class OkHttpTraegerTransport : TraegerHttpTransport {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    companion object {
        fun buildRequest(operation: TraegerHttpOperation, body: String?, token: String?, thing: String?): Request {
        val contentType = if (operation == TraegerHttpOperation.REFRESH) "application/x-amz-json-1.1" else "application/json"
        return Request.Builder().url(operation.url(thing))
                .header("Accept", "application/json").header("User-Agent", "Traeger/11 CFNetwork/1209 Darwin/20.2.0")
                .apply {
                    if (token != null) header("Authorization", token)
                    if (operation == TraegerHttpOperation.REFRESH) header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
                    if (operation == TraegerHttpOperation.DEVICES) get() else {
                        val outbound = if (operation == TraegerHttpOperation.STATUS) """{"command":"90"}""" else body ?: "{}"
                        post(outbound.toRequestBody(contentType.toMediaType()))
                    }
                }.build()
        }
    }

    override suspend fun request(operation: TraegerHttpOperation, body: String?, token: String?, thing: String?): PolarisHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val request = buildRequest(operation, body, token, thing)
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(PolarisFailure(PolarisFailureKind.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val bytes = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            val stream = it.body.byteStream()
                            while (true) {
                                val count = stream.read(chunk)
                                if (count < 0) break
                                if (bytes.size() + count > 512 * 1024) throw PolarisFailure(PolarisFailureKind.SCHEMA, it.code)
                                bytes.write(chunk, 0, count)
                            }
                            PolarisHttpResponse(it.code, bytes.toString("UTF-8"),
                                it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300)?.times(1000))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? PolarisFailure ?: PolarisFailure(PolarisFailureKind.NETWORK))
                    }
                }
            })
        }
}

internal sealed interface GrillSocketFrame {
    data class Text(val value: String) : GrillSocketFrame
    data class Binary(val value: ByteArray) : GrillSocketFrame
}
internal interface GrillSocket {
    suspend fun awaitOpen()
    suspend fun receive(): GrillSocketFrame
    fun text(value: String)
    fun binary(value: ByteArray)
    fun close()
}
internal fun interface GrillSocketFactory {
    fun open(url: String, subprotocol: String?): GrillSocket
}
internal class OkHttpGrillSocketFactory : GrillSocketFactory {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    override fun open(url: String, subprotocol: String?): GrillSocket = object : GrillSocket {
        private val opened = CompletableDeferred<Unit>()
        private val frames = Channel<GrillSocketFrame>(64)
        @Volatile private var closed = false
        private val socket = client.newWebSocket(
            Request.Builder().url(url).apply { if (subprotocol != null) header("Sec-WebSocket-Protocol", subprotocol) }.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed) webSocket.cancel() else if (subprotocol != null && response.header("Sec-WebSocket-Protocol") != subprotocol) {
                        fail(webSocket, PolarisFailure(PolarisFailureKind.SCHEMA))
                    } else opened.complete(Unit)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 256 * 1024 || frames.trySend(GrillSocketFrame.Text(text)).isFailure)
                        fail(webSocket, PolarisFailure(PolarisFailureKind.SCHEMA))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > 256 * 1024 || frames.trySend(GrillSocketFrame.Binary(bytes.toByteArray())).isFailure)
                        fail(webSocket, PolarisFailure(PolarisFailureKind.SCHEMA))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val failure = PolarisFailure(if (response?.code == 401 || response?.code == 403) PolarisFailureKind.AUTH else PolarisFailureKind.NETWORK, response?.code)
                    response?.close()
                    fail(webSocket, failure)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    fail(webSocket, PolarisFailure(PolarisFailureKind.NETWORK))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    fail(webSocket, PolarisFailure(PolarisFailureKind.NETWORK))
                }
                private fun fail(webSocket: WebSocket, failure: PolarisFailure) {
                    opened.completeExceptionally(failure)
                    frames.close(failure)
                    webSocket.cancel()
                }
            },
        )
        override suspend fun awaitOpen() { withTimeout(12_000) { opened.await() } }
        override suspend fun receive(): GrillSocketFrame = frames.receive()
        override fun text(value: String) { if (closed || !socket.send(value)) throw PolarisFailure(PolarisFailureKind.NETWORK) }
        override fun binary(value: ByteArray) { if (closed || !socket.send(value.toByteString())) throw PolarisFailure(PolarisFailureKind.NETWORK) }
        override fun close() {
            closed = true
            opened.cancel()
            frames.cancel()
            socket.cancel()
        }
    }
}

