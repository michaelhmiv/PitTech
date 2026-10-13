package com.pittech.devices

import org.json.JSONObject

/** Pure helpers for Mongoose OS RPC-over-GATT framing. */
internal object MongooseRpcFraming {
    const val MAX_RESPONSE_BYTES = 64 * 1024
    const val DEFAULT_CHUNK_BYTES = 20

    fun encodeLength(length: Int): ByteArray {
        require(length >= 0) { "Frame length must not be negative." }
        return byteArrayOf(
            ((length ushr 24) and 0xff).toByte(),
            ((length ushr 16) and 0xff).toByte(),
            ((length ushr 8) and 0xff).toByte(),
            (length and 0xff).toByte(),
        )
    }

    /** Decodes an unsigned 32-bit big-endian length and enforces the read cap. */
    fun decodeResponseLength(bytes: ByteArray): Int {
        require(bytes.size == 4) { "A Mongoose RPC length must contain exactly four bytes." }
        val length = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xffL) }
        require(length <= MAX_RESPONSE_BYTES) {
            "Mongoose RPC response length $length exceeds the $MAX_RESPONSE_BYTES byte limit."
        }
        return length.toInt()
    }

    fun chunkPayload(payload: ByteArray, chunkSize: Int = DEFAULT_CHUNK_BYTES): List<ByteArray> {
        require(chunkSize > 0) { "Chunk size must be positive." }
        if (payload.isEmpty()) return emptyList()
        return buildList((payload.size + chunkSize - 1) / chunkSize) {
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(payload.size, offset + chunkSize)
                add(payload.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    fun encodeRequest(requestId: Int, method: String, params: Map<String, String> = emptyMap()): ByteArray {
        require(requestId >= 0) { "RPC request id must not be negative." }
        require(method.isNotBlank()) { "RPC method must not be blank." }
        return JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("params", JSONObject().apply { params.forEach { (key, value) -> put(key, value) } })
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}

/** Collects exactly one announced response frame and rejects empty/overflow reads. */
internal class MongooseRpcResponseAssembler(
    val expectedLength: Int,
    private val maximumLength: Int = MongooseRpcFraming.MAX_RESPONSE_BYTES,
) {
    private val bytes: ByteArray
    var receivedLength: Int = 0
        private set

    init {
        require(maximumLength in 0..MongooseRpcFraming.MAX_RESPONSE_BYTES) {
            "Mongoose RPC maximum response length $maximumLength exceeds the transport limit."
        }
        require(expectedLength in 0..maximumLength) {
            "Mongoose RPC response length $expectedLength is outside 0..$maximumLength."
        }
        bytes = ByteArray(expectedLength)
    }

    fun append(chunk: ByteArray): Boolean {
        require(chunk.isNotEmpty()) { "Mongoose RPC response ended before the announced length." }
        require(receivedLength + chunk.size <= expectedLength) {
            "Mongoose RPC response exceeded its announced length."
        }
        chunk.copyInto(bytes, destinationOffset = receivedLength)
        receivedLength += chunk.size
        return receivedLength == expectedLength
    }

    fun finish(): ByteArray {
        check(receivedLength == expectedLength) {
            "Mongoose RPC response was truncated: received $receivedLength of $expectedLength bytes."
        }
        return bytes.copyOf()
    }
}
