package com.pittech

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object FeedbackApi {
    private const val ENDPOINT =
        "https://pittech-feedback-relay-production.up.railway.app/v1/feedback"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun submitAsync(
        request: FeedbackRequest,
        onResult: (FeedbackSubmitResult) -> Unit,
    ) {
        Thread {
            val result = runCatching { submit(request) }
                .getOrElse {
                    FeedbackSubmitResult.Failure(
                        "Couldn't submit feedback right now. Check your connection and try again.",
                    )
                }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.apply {
            name = "pittech-feedback"
            isDaemon = true
        }.start()
    }

    private fun submit(request: FeedbackRequest): FeedbackSubmitResult {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PitTech-Android/" + request.appVersion)
        }

        return try {
            val payload = request.toJson().toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            val responseBody = readResponseBody(connection, status)
            when (status) {
                in 200..299 -> FeedbackSubmitResult.Success(
                    Regex("\\"issueNumber\\"\\s*:\\s*(\\d+)")
                        .find(responseBody)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull(),
                )
                429 -> FeedbackSubmitResult.Failure(
                    "Too many reports were submitted recently. Try again later.",
                )
                400, 413 -> FeedbackSubmitResult.Failure(
                    "That report couldn't be submitted. Shorten it and try again.",
                )
                503 -> FeedbackSubmitResult.Failure(
                    "Feedback service is temporarily unavailable. Try again later.",
                )
                else -> FeedbackSubmitResult.Failure(
                    "Couldn't submit feedback right now. Try again later.",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponseBody(
        connection: HttpURLConnection,
        status: Int,
    ): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return try {
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (_: IOException) {
            ""
        }
    }
}
