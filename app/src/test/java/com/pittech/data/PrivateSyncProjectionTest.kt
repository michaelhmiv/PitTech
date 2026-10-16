package com.pittech.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivateSyncProjectionTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    @Test fun sharedProjectionRejectsActivationCredentialsAndBrokenOwnership() {
        val projection = PrivateSyncProjection(resource("private-sync-projection-v1"))
        val fixture = JSONObject(resource("private-sync-projection-cases-v1"))
        val cases = fixture.getJSONArray("cases"); assertEquals(20, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getBoolean("rejected")) assertThrows(c.getString("name"), Exception::class.java) { projection.validateRows(c.getJSONArray("rows")) }
            else projection.validateRows(c.getJSONArray("rows"))
        }
        val p = fixture.getJSONObject("projection"); val archive = p.getJSONObject("archive"); val before = archive.toString()
        val assets = p.getJSONObject("assets").let { a -> a.keys().asSequence().associateWith { a.getString(it) } }
        assertEquals(PrivateSyncMerge.stable(p.getJSONArray("expected")), PrivateSyncMerge.stable(projection.project(archive, assets)))
        assertEquals(before, archive.toString())
        assertThrows(Exception::class.java) { projection.project(archive) }
        assertThrows(Exception::class.java) { projection.project(JSONObject(archive.toString()).put("unknownCredentialStore", JSONObject()), assets) }
        assertFalse(JSONObject(resource("private-sync-projection-v1")).getBoolean("releaseReady"))
    }
}
