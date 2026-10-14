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

enum class PitBossCloudStage {
    DISCONNECTED,
    CONNECTING,
    RELAY_CONNECTED,
    RPC_RESPONDED,
    ERROR,
}

data class PitBossCloudLogEntry(
    val title: String,
    val safeJson: String,
)

data class PitBossCloudUiState(
    val stage: PitBossCloudStage = PitBossCloudStage.DISCONNECTED,
    val statusMessage: String = "Not connected",
    val errorMessage: String? = null,
    val controllerStatusSeen: Boolean = false,
    val recentMessages: List<PitBossCloudLogEntry> = emptyList(),
)

class PitBossCloudTransport : ControllerTransport {
    private val generation = AtomicLong(0L)
    private val _uiState = MutableStateFlow(PitBossCloudUiState())
    val uiState: StateFlow<PitBossCloudUiState> = _uiState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    override fun connect(identifier: String) {
        val grillId = identifier
        val sessionGeneration = generation.incrementAndGet()
        webSocket?.cancel()
        webSocket = null

        val socketUrl = try {
            PitBossCloudProtocol.webSocketUrl(grillId)
        } catch (error: IllegalArgumentException) {
            _uiState.value = PitBossCloudUiState(
                stage = PitBossCloudStage.ERROR,
                statusMessage = error.message ?: "Enter a valid grill ID.",
            )
            return
        }

        val appId = UUID.randomUUID().toString().substringAfterLast('-')
        _uiState.value = PitBossCloudUiState(
            stage = PitBossCloudStage.CONNECTING,
            statusMessage = "Connecting to the optional Pit Boss cloud transport…",
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
                        stage = PitBossCloudStage.RELAY_CONNECTED,
                        statusMessage = "Relay connected (HTTP " + response.code + "); sending the read-only RPC.Ping check.",
                        errorMessage = null,
                    )
                    if (!webSocket.send(PitBossCloudProtocol.pingPayload(appId))) {
                        setFailure(sessionGeneration, "Could not send the Ping check.")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(sessionGeneration)) return
                    val message = PitBossCloudProtocol.inspectMessage(text)
                    val current = _uiState.value
                    val sawControllerStatus =
                        current.controllerStatusSeen ||
                            message.kind == PitBossCloudMessageKind.CONTROLLER_STATUS
                    val pingResponded =
                        message.kind == PitBossCloudMessageKind.RPC_RESPONSE

                    _uiState.value = current.copy(
                        stage = if (pingResponded || sawControllerStatus) {
                            PitBossCloudStage.RPC_RESPONDED
                        } else {
                            current.stage
                        },
                        statusMessage = when {
                            sawControllerStatus -> "Controller status received through the vendor relay."
                            pingResponded -> "The relay returned an RPC response."
                            else -> current.statusMessage
                        },
                        errorMessage = if (message.kind == PitBossCloudMessageKind.RPC_ERROR) {
                            "The RPC returned an error. See the redacted frame below."
                        } else {
                            current.errorMessage
                        },
                        controllerStatusSeen = sawControllerStatus,
                        recentMessages = (listOf(
                            PitBossCloudLogEntry(message.kind.title, message.safeJson),
                        ) + current.recentMessages).take(MAX_RECENT_MESSAGES),
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (isCurrent(sessionGeneration)) {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Relay closing (code " + code + "; " + safeDiagnostic(reason, 120) + ").",
                        )
                    }
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(sessionGeneration)) return
                    this@PitBossCloudTransport.webSocket = null
                    _uiState.value = _uiState.value.copy(
                        stage = PitBossCloudStage.DISCONNECTED,
                        statusMessage = "Relay closed (code " + code + "; " + safeDiagnostic(reason, 120) + ").",
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@PitBossCloudTransport.webSocket === webSocket) {
                        this@PitBossCloudTransport.webSocket = null
                    }
                    setFailure(
                        sessionGeneration,
                        "Could not connect or the relay closed the session. Check the Bluetooth selection, Internet connection, and controller status. " +
                            "Transport exception=" + t.javaClass.name +
                            "; message=" + safeDiagnostic(t.message.orEmpty(), 220) +
                            "; HTTP response=" + (response?.code?.toString() ?: "(none)") +
                            (response?.message?.takeIf { it.isNotBlank() }?.let { "; response message=" + safeDiagnostic(it, 120) } ?: ""),
                    )
                }
            },
        )
    }

    override fun disconnect() {
        generation.incrementAndGet()
        webSocket?.close(1000, "PitTech test disconnected")
        webSocket = null
        _uiState.value = PitBossCloudUiState(
            stage = PitBossCloudStage.DISCONNECTED,
            statusMessage = "Disconnected",
        )
    }

    private fun safeDiagnostic(value: String, maxChars: Int): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(maxChars)

    private fun isCurrent(sessionGeneration: Long): Boolean =
        generation.get() == sessionGeneration

    private fun setFailure(sessionGeneration: Long, message: String) {
        if (!isCurrent(sessionGeneration)) return
        _uiState.value = _uiState.value.copy(
            stage = PitBossCloudStage.ERROR,
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
