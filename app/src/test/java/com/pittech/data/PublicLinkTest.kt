package com.pittech.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicLinkTest {
    @Test fun canonicalNavigationOnlyLinksMatchTheSharedContract() {
        val data=javaClass.classLoader!!.getResource("public-links-v1.json")!!.readText()
        val fixture=JSONObject(data);val valid=fixture.getJSONArray("valid");val invalid=fixture.getJSONArray("invalid")
        for(i in 0 until valid.length()) { val row=valid.getJSONObject(i);assertEquals(row.getString("destination"),PublicLink.destination(row.getString("input"))) }
        for(i in 0 until invalid.length()) assertTrue(invalid.getString(i),runCatching { PublicLink.destination(invalid.getString(i)) }.isFailure)
    }
}
