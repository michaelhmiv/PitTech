package com.pittech.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MongooseRpcResponseParserTest {
    @Test
    fun successfulResponseRetainsTheCompleteInventoryResult() {
        val parsed = MongooseRpcResponseParser.parse(
            """{"id":9,"result":{"methods":[{"name":"RPC.Ping"},{"name":"PB.GetState"}]}}""",
            expectedId = 9,
        )

        assertEquals(MongooseRpcResponseKind.SUCCESS, parsed.kind)
        assertTrue(parsed.resultJson.contains("PB.GetState"))
    }

    @Test
    fun methodNotFoundAndOtherRpcErrorsAreDistinguishedFromTransportFailures() {
        val missing = MongooseRpcResponseParser.parse(
            """{"id":1,"error":{"code":404,"message":"method not found"}}""",
            expectedId = 1,
        )
        val rejected = MongooseRpcResponseParser.parse(
            """{"id":2,"error":{"code":401,"message":"authentication required"}}""",
            expectedId = 2,
        )

        assertEquals(MongooseRpcResponseKind.RPC_ERROR, missing.kind)
        assertEquals(404, missing.errorCode)
        assertTrue(missing.isMethodUnavailable())
        assertEquals(401, rejected.errorCode)
        assertTrue(!rejected.isMethodUnavailable())
        assertEquals("authentication required", rejected.errorMessage)
    }

    @Test
    fun listFallbackRecognizesMethodUnavailableByMessageWithoutKnownErrorCode() {
        val missing = MongooseRpcResponseParser.parse(
            """{"id":3,"error":{"code":-32601,"message":"Unknown method RPC.List"}}""",
            expectedId = 3,
        )

        assertTrue(missing.isMethodUnavailable())
    }

    @Test
    fun malformedJsonMissingIdAndTruncatedShapeAreReportedAsProtocolErrors() {
        assertEquals(
            MongooseRpcResponseKind.MALFORMED,
            MongooseRpcResponseParser.parse("{not-json", expectedId = 1).kind,
        )
        assertEquals(
            MongooseRpcResponseKind.MALFORMED,
            MongooseRpcResponseParser.parse("""{"result":{}}""", expectedId = 1).kind,
        )
        assertEquals(
            MongooseRpcResponseKind.MALFORMED,
            MongooseRpcResponseParser.parse("""{"id":1}""", expectedId = 1).kind,
        )
    }

    @Test
    fun staleResponseIsIgnoredWithoutBecomingAnRpcError() {
        val parsed = MongooseRpcResponseParser.parse(
            """{"id":7,"result":{}}""",
            expectedId = 8,
        )

        assertEquals(MongooseRpcResponseKind.STALE_RESPONSE, parsed.kind)
        assertEquals(7, parsed.id)
    }
}
