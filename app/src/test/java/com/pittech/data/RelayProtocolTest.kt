package com.pittech.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayProtocolTest {
    private fun fixture() = JSONObject(javaClass.classLoader!!.getResourceAsStream("relay-links-v1.json")!!.bufferedReader().use { it.readText() })
    @Test fun sharedGoldenCasesNormalizeOnlyApprovedOriginsAndPaths() {
        val input = fixture()
        val canonical = input.getString("canonical")
        val valid = input.getJSONArray("valid"); for (i in 0 until valid.length()) assertEquals(canonical, RelayProtocol.code(valid.getString(i)))
        val invalid = input.getJSONArray("invalid"); for (i in 0 until invalid.length()) assertThrows(invalid.getString(i), Exception::class.java) { RelayProtocol.code(invalid.getString(i)) }
        assertEquals("https://pittech.app/s/$canonical", RelayProtocol.link(canonical))
    }
    @Test fun receiptsRequireBoundedBytesExactIdentityAndValidExpiry() {
        val input = fixture().getJSONObject("receipt"); val accepted = RelayReceipt.parse(input)
        assertEquals(2, accepted.dishes); assertEquals("collection", accepted.kind)
        for ((key, value) in listOf("bytes" to 0, "bytes" to RelayProtocol.MAX_BYTES + 1, "dishes" to 21, "sha256" to "unverified", "expiresAt" to "not-a-date", "code" to "EMBER-OAK-7")) {
            assertThrows(Exception::class.java) { RelayReceipt.parse(JSONObject(input.toString()).put(key, value)) }
        }
    }
}
