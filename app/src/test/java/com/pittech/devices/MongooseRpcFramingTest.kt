package com.pittech.devices

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MongooseRpcFramingTest {
    @Test
    fun requestAndResponseLengthsUseFourByteBigEndianEncoding() {
        val requestLength = MongooseRpcFraming.encodeLength(0x01020304)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), requestLength)

        val responseLength = MongooseRpcFraming.encodeLength(0x00000102)
        assertEquals(0x00000102, MongooseRpcFraming.decodeResponseLength(responseLength))
    }

    @Test
    fun payloadIsChunkedAtConservativeTwentyByteBoundaries() {
        val payload = ByteArray(45) { it.toByte() }
        val chunks = MongooseRpcFraming.chunkPayload(payload)
        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
        assertArrayEquals(payload, chunks.fold(byteArrayOf()) { result, bytes -> result + bytes })
    }

    @Test
    fun responseAssemblerReassemblesExactLengthAcrossMultipleReads() {
        val assembler = MongooseRpcResponseAssembler(5)
        assertFalse(assembler.append(byteArrayOf(1, 2)))
        assertFalse(assembler.append(byteArrayOf(3)))
        assertTrue(assembler.append(byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), assembler.finish())
    }

    @Test
    fun oversizedOrMalformedResponseLengthIsRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            MongooseRpcFraming.decodeResponseLength(byteArrayOf(0, 1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MongooseRpcFraming.decodeResponseLength(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MongooseRpcResponseAssembler(MongooseRpcFraming.MAX_RESPONSE_BYTES + 1)
        }
    }

    @Test
    fun emptyOverflowAndTruncatedResponseFramesAreRejected() {
        val truncated = MongooseRpcResponseAssembler(4)
        assertThrows(IllegalArgumentException::class.java) { truncated.append(byteArrayOf()) }
        truncated.append(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { truncated.finish() }

        val overflow = MongooseRpcResponseAssembler(2)
        assertThrows(IllegalArgumentException::class.java) { overflow.append(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun negativeRequestLengthsAndInvalidChunkSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MongooseRpcFraming.encodeLength(-1) }
        assertThrows(IllegalArgumentException::class.java) { MongooseRpcFraming.chunkPayload(byteArrayOf(1), 0) }
    }
}
