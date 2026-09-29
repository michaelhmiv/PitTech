package com.pittech.devices

import org.json.JSONObject

internal enum class MongooseRpcResponseKind {
    SUCCESS,
    RPC_ERROR,
    MALFORMED,
    STALE_RESPONSE,
}

internal data class MongooseRpcResponse(
    val kind: MongooseRpcResponseKind,
    val id: Int? = null,
    val result: Any? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val failureReason: String? = null,
) {
    fun isMethodUnavailable(): Boolean = kind == MongooseRpcResponseKind.RPC_ERROR &&
        (errorCode == 404 || errorMessage.orEmpty().lowercase().let { message ->
            "method not found" in message || "unknown method" in message || "no such method" in message
        })

    val resultJson: String
        get() = when (result) {
            null, JSONObject.NULL -> "null"
            else -> result.toString()
        }
}

/** Decodes one complete JSON-RPC response after transport framing has succeeded. */
internal object MongooseRpcResponseParser {
    fun parse(raw: String, expectedId: Int): MongooseRpcResponse {
        val response = try {
            JSONObject(raw)
        } catch (error: Exception) {
            return MongooseRpcResponse(
                kind = MongooseRpcResponseKind.MALFORMED,
                failureReason = "Unparseable JSON response (${raw.length} chars): ${error.javaClass.simpleName}",
            )
        }

        if (!response.has("id")) {
            return MongooseRpcResponse(
                kind = MongooseRpcResponseKind.MALFORMED,
                failureReason = "RPC response did not include a request id.",
            )
        }
        val id = response.optInt("id", Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) {
            return MongooseRpcResponse(
                kind = MongooseRpcResponseKind.MALFORMED,
                failureReason = "RPC response request id was not an integer.",
            )
        }
        if (id != expectedId) {
            return MongooseRpcResponse(
                kind = MongooseRpcResponseKind.STALE_RESPONSE,
                id = id,
                failureReason = "Received response id $id while waiting for id $expectedId.",
            )
        }

        val error = response.optJSONObject("error")
        if (error != null) {
            return MongooseRpcResponse(
                kind = MongooseRpcResponseKind.RPC_ERROR,
                id = id,
                errorCode = error.optInt("code", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                errorMessage = error.optString("message", "RPC error"),
            )
        }
        if (!response.has("result")) {
            return MongooseRpcResponse(
                kind = MongooseRpcResponseKind.MALFORMED,
                id = id,
                failureReason = "RPC response included neither result nor error.",
            )
        }
        return MongooseRpcResponse(
            kind = MongooseRpcResponseKind.SUCCESS,
            id = id,
            result = response.opt("result"),
        )
    }
}
