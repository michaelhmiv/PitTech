package com.pittech.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class PitBossRelayStage {
    DISCONNECTED,
    CONNECTING,
    RELAY_CONNECTED,
    RPC_RESPONDED,
    ERROR,
}

data class PitBossRelayLogEntry(
    val title: String,
    val safeJson: String,
)

data class PitBossRelayUiState(
    val stage: PitBossRelayStage = PitBossRelayStage.DISCONNECTED,
    val statusMessage: String = "Not connected",
    val errorMessage: String? = null,
    val controllerStatusSeen: Boolean = false,
    val recentMessages: List<PitBossRelayLogEntry> = emptyList(),
)

class PitBossRelayClient : ControllerTransport {
    private val generation = AtomicLong(0L)
    private val _uiState = MutableStateFlow(PitBossRelayUiState())
    val uiState: StateFlow<PitBossRelayUiState> = _uiState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    override fun connect(identifier: String) {
        val grillId = identifier
        val sessionGeneration = generation.incrementAndGet()
        webSocket?.cancel()
        webSocket = null

        val socketUrl = try {
            PitBossRelayProtocol.webSocketUrl(grillId)
        } catch (error: IllegalArgumentException) {
            _uiState.value = PitBossRelayUiState(
                stage = PitBossRelayStage.ERROR,
                statusMessage = error.message ?: "Enter a valid grill ID.",
            )
            return
        }

        val appId = UUID.randomUUID().toString().substringAfterLast('-')
        _uiState.value = PitBossRelayUiState(
            stage = PitBossRelayStage.CONNECTING,
            statusMessage = "Connecting to the Pit Boss relay…",
        )

        val request = Request.Builder()
            .url(socketUrl)
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(sessionGeneration)) {
                        webSocket.close(1000, "Stale test session")
                        return
                    }
                    _uiState.value = _uiState.value.copy(
                        stage = PitBossRelayStage.RELAY_CONNECTED,
                        statusMessage = "Relay connected; sending the read-only RPC.Ping check.",
                        errorMessage = null,
                    )
                    if (!webSocket.send(PitBossRelayProtocol.pingPayload(appId))) {
                        setFailure(sessionGeneration, "Could not send the Ping check.")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(sessionGeneration)) return
                    val message = PitBossRelayProtocol.inspectMessage(text)
                    val current = _uiState.value
                    val sawControllerStatus =
                        current.controllerStatusSeen ||
                            message.kind == PitBossRelayMessageKind.CONTROLLER_STATUS
                    val pingResponded =
                        message.kind == PitBossRelayMessageKind.RPC_RESPONSE

                    _uiState.value = current.copy(
                        stage = if (pingResponded || sawControllerStatus) {
                            PitBossRelayStage.RPC_RESPONDED
                        } else {
                            current.stage
                        },
                        statusMessage = when {
                            sawControllerStatus -> "Controller status received through the vendor relay."
                            pingResponded -> "The relay returned an RPC response."
                            else -> current.statusMessage
                        },
                        errorMessage = if (message.kind == PitBossRelayMessageKind.RPC_ERROR) {
                            "The RPC returned an error. See the redacted frame below."
                        } else {
                            current.errorMessage
                        },
                        controllerStatusSeen = sawControllerStatus,
                        recentMessages = (listOf(
                            PitBossRelayLogEntry(message.kind.title, message.safeJson),
                        ) + current.recentMessages).take(MAX_RECENT_MESSAGES),
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(sessionGeneration)) return
                    this@PitBossRelayClient.webSocket = null
                    _uiState.value = _uiState.value.copy(
                        stage = PitBossRelayStage.DISCONNECTED,
                        statusMessage = "The relay closed the connection.",
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@PitBossRelayClient.webSocket === webSocket) {
                        this@PitBossRelayClient.webSocket = null
                    }
                    setFailure(
                        sessionGeneration,
                        "Could not connect or the relay closed the session. Check the Bluetooth selection, Internet connection, and controller status.",
                    )
                }
            },
        )
    }

    override fun disconnect() {
        generation.incrementAndGet()
        webSocket?.close(1000, "PitTech test disconnected")
        webSocket = null
        _uiState.value = PitBossRelayUiState(
            stage = PitBossRelayStage.DISCONNECTED,
            statusMessage = "Disconnected",
        )
    }

    private fun isCurrent(sessionGeneration: Long): Boolean =
        generation.get() == sessionGeneration

    private fun setFailure(sessionGeneration: Long, message: String) {
        if (!isCurrent(sessionGeneration)) return
        _uiState.value = _uiState.value.copy(
            stage = PitBossRelayStage.ERROR,
            statusMessage = "Connection test failed.",
            errorMessage = message,
        )
    }

    private companion object {
        const val MAX_RECENT_MESSAGES = 8
        val httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
