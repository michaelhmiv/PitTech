package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PrivateSyncProtocolTest {
    private val deviceA = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private val deviceB = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private fun row(type: String, id: String, counter: Long, client: String, payload: JSONObject?, assets: List<String> = emptyList(), deleted: Boolean = false) = JSONObject()
        .put("recordType", type).put("recordId", id).put("version", JSONObject().put("counter", counter).put("clientId", client))
        .put("deleted", deleted).put("payload", payload ?: JSONObject.NULL).put("assetIds", JSONArray(assets))
    private fun projected(type: String, id: String, payload: JSONObject, assets: List<String> = emptyList()) = JSONObject()
        .put("recordType", type).put("recordId", id).put("payload", payload).put("assetIds", JSONArray(assets))
    private fun notePayload(title: String) = JSONObject().put("id", "note-1").put("cookId", "cook-1").put("title", title)

    @Test fun stampingKeepsBaseVersionsAdvancesChangesAndTombstonesDeletions() {
        val base = JSONArray().put(row("events", "note-1", 1, deviceA, notePayload("Same"))).put(row("events", "note-2", 1, deviceA, notePayload("Gone").put("id", "note-2")))
        val projected = JSONArray().put(projected("events", "note-1", notePayload("Same"))).put(projected("events", "note-3", notePayload("New").put("id", "note-3")))
        val stamped = PrivateSyncProtocol.stampLocal(projected, base, deviceA, 2)
        assertEquals(3, stamped.length())
        val kept = (0 until stamped.length()).map { stamped.getJSONObject(it) }.first { it.getString("recordId") == "note-1" }
        assertEquals(1, kept.getJSONObject("version").getLong("counter"))
        val added = (0 until stamped.length()).map { stamped.getJSONObject(it) }.first { it.getString("recordId") == "note-3" }
        assertEquals(2, added.getJSONObject("version").getLong("counter")); assertEquals(deviceA, added.getJSONObject("version").getString("clientId")); assertFalse(added.getBoolean("deleted"))
        val tombstone = (0 until stamped.length()).map { stamped.getJSONObject(it) }.first { it.getString("recordId") == "note-2" }
        assertTrue(tombstone.getBoolean("deleted")); assertTrue(tombstone.isNull("payload")); assertEquals(0, tombstone.getJSONArray("assetIds").length())
        assertEquals(2, tombstone.getJSONObject("version").getLong("counter")); assertEquals(deviceA, tombstone.getJSONObject("version").getString("clientId"))
        // Re-stamping an unchanged projection is stable: no version churn.
        val again = PrivateSyncProtocol.stampLocal(projected, stamped, deviceA, 3)
        assertEquals(PrivateSyncMerge.stable(stamped), PrivateSyncMerge.stable(again))
    }

    @Test fun putBodyMergesConflictsAndSendsOnlyServerKnownResolutions() {
        val records = JSONArray().put(row("events", "note-1", 2, deviceA, notePayload("Mine")))
        val winner = row("events", "note-2", 2, deviceB, notePayload("Theirs").put("id", "note-2"))
        val loser = row("events", "note-2", 2, deviceA, notePayload("Mine").put("id", "note-2"))
        val merged = PrivateSyncMerge.merge(JSONArray().put(row("events", "note-2", 1, deviceA, notePayload("Base").put("id", "note-2"))), JSONArray().put(loser), JSONArray().put(winner))
        val conflict = merged.getJSONArray("conflicts").getJSONObject(0)
        assertEquals("edit_edit", conflict.getString("reason"))
        val body = PrivateSyncProtocol.putBody(4, UUID.randomUUID().toString(), deviceA, records, JSONArray().put(conflict), JSONArray(), listOf(conflict.getString("id"), "0".repeat(64)), setOf(conflict.getString("id")))
        assertEquals(1, body.getInt("contractVersion")); assertEquals(4, body.getLong("baseVersion"))
        assertEquals(0, body.getJSONArray("conflicts").length())
        assertEquals(1, body.getJSONArray("resolvedConflictIds").length())
        assertEquals(conflict.getString("id"), body.getJSONArray("resolvedConflictIds").getString(0))
        // A locally generated conflict the server never saw drops without an unknown resolution ID.
        val fresh = PrivateSyncProtocol.putBody(4, UUID.randomUUID().toString(), deviceA, records, JSONArray(), JSONArray().put(conflict), listOf(conflict.getString("id")), emptySet())
        assertEquals(0, fresh.getJSONArray("conflicts").length()); assertFalse(fresh.has("resolvedConflictIds"))
    }

    @Test fun parseSnapshotGetValidatesVersionAndEnvelope() {
        val (emptyVersion, emptyEnvelope) = PrivateSyncProtocol.parseSnapshotGet(JSONObject().put("version", 0).put("envelope", JSONObject.NULL))
        assertEquals(0, emptyVersion); assertNull(emptyEnvelope)
        val records = JSONArray().put(row("events", "note-1", 1, deviceA, notePayload("Base")))
        val reply = JSONObject().put("version", 7).put("envelope", JSONObject().put("contractVersion", 1).put("records", records).put("conflicts", JSONArray()))
        val (version, envelope) = PrivateSyncProtocol.parseSnapshotGet(reply)
        assertEquals(7, version); assertEquals(1, envelope!!.getJSONArray("records").length())
        assertThrows(Exception::class.java) { PrivateSyncProtocol.parseSnapshotGet(JSONObject().put("version", -1).put("envelope", JSONObject.NULL)) }
        assertThrows(Exception::class.java) { PrivateSyncProtocol.parseSnapshotGet(JSONObject().put("version", 1).put("envelope", JSONObject().put("contractVersion", 2).put("records", JSONArray()).put("conflicts", JSONArray()))) }
    }

    @Test fun sharedMergeVectorsProduceUploadableEnvelopesWithValidConflictIds() {
        val fixture = JSONObject(checkNotNull(javaClass.getResourceAsStream("/sync-merge-v1.json")).bufferedReader().use { it.readText() })
        val cases = fixture.getJSONArray("cases"); assertEquals(11, cases.length())
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            if (vector.optBoolean("rejected")) continue
            val merged = PrivateSyncMerge.merge(vector.getJSONArray("base"), vector.getJSONArray("local"), vector.getJSONArray("remote"))
            val body = PrivateSyncProtocol.putBody(0, UUID.randomUUID().toString(), deviceA, merged.getJSONArray("records"), JSONArray(), merged.getJSONArray("conflicts"), emptyList(), emptySet())
            assertEquals(vector.getString("name"), merged.getJSONArray("records").length(), body.getJSONArray("records").length())
            assertEquals(vector.getString("name"), merged.getJSONArray("conflicts").length(), body.getJSONArray("conflicts").length())
            for (j in 0 until merged.getJSONArray("conflicts").length()) {
                val view = PrivateSyncProtocol.conflictView(merged.getJSONArray("conflicts").getJSONObject(j), deviceA)
                assertTrue(vector.getString("name"), view.mineSummary.isNotBlank() && view.theirsSummary.isNotBlank() && view.reason.isNotBlank())
            }
        }
    }

    @Test fun keepMineTheirsAndBothMatchMergeSemantics() {
        val base = JSONArray().put(row("events", "note-1", 1, deviceA, notePayload("Base")))
        val mine = row("events", "note-1", 2, deviceA, notePayload("Mine"))
        val theirs = row("events", "note-1", 2, deviceB, notePayload("Theirs"))
        val conflict = PrivateSyncMerge.merge(base, JSONArray().put(mine), JSONArray().put(theirs)).getJSONArray("conflicts").getJSONObject(0)
        assertEquals(deviceB, conflict.getJSONObject("winner").getJSONObject("version").getString("clientId"))
        val keepTheirs = PrivateSyncProtocol.resolve(conflict, "theirs", deviceA)
        assertEquals(conflict.getString("id"), keepTheirs.resolvedId); assertNull(keepTheirs.restoreType)
        val keepMine = PrivateSyncProtocol.resolve(conflict, "mine", deviceA)
        assertEquals("note-1", keepMine.restoreId); assertFalse(keepMine.newIdentity)
        assertEquals("Mine", keepMine.restorePayload!!.getString("title"))
        val both = PrivateSyncProtocol.resolve(conflict, "both", deviceA)
        assertTrue(both.newIdentity); assertNotEquals("note-1", both.restoreId)
        assertEquals(both.restoreId, both.restorePayload!!.getString("id")); assertEquals("Mine", both.restorePayload.getString("title"))
        // Tombstones win concurrent edits; the losing edit restores only as a new identity.
        val deleted = row("events", "note-1", 2, deviceB, null, deleted = true)
        val tombstoneConflict = PrivateSyncMerge.merge(base, JSONArray().put(mine), JSONArray().put(deleted)).getJSONArray("conflicts").getJSONObject(0)
        assertEquals("delete_edit", tombstoneConflict.getString("reason"))
        assertNull(PrivateSyncProtocol.resolve(tombstoneConflict, "theirs", deviceA).restoreType)
        val resurrected = PrivateSyncProtocol.resolve(tombstoneConflict, "mine", deviceA)
        assertTrue(resurrected.newIdentity); assertEquals(resurrected.restoreId, resurrected.restorePayload!!.getString("id"))
        assertEquals("Mine", resurrected.restorePayload.getString("title"))
        // Immutable rewrites never overwrite under the same identity.
        val reading = JSONObject().put("id", "reading-1").put("value", 74.0)
        val immutable = PrivateSyncMerge.merge(JSONArray().put(row("readings", "reading-1", 1, deviceA, reading)),
            JSONArray().put(row("readings", "reading-1", 2, deviceA, JSONObject().put("id", "reading-1").put("value", 75.0))),
            JSONArray().put(row("readings", "reading-1", 1, deviceA, reading))).getJSONArray("conflicts").getJSONObject(0)
        assertEquals("immutable_identity", immutable.getString("reason"))
        val kept = PrivateSyncProtocol.resolve(immutable, "theirs", deviceA)
        assertTrue(kept.newIdentity); assertEquals(75.0, kept.restorePayload!!.getDouble("value"), 0.0)
        assertNull(PrivateSyncProtocol.resolve(immutable, "mine", deviceA).restoreType)
    }

    @Test fun summariesDescribeCompetingVersionsAndKeepBothLimits() {
        val base = JSONArray().put(row("lifecycles", "cook-1", 1, deviceA, JSONObject().put("cookId", "cook-1").put("timingMode", "legacy_preserved")))
        val mine = row("lifecycles", "cook-1", 2, deviceA, JSONObject().put("cookId", "cook-1").put("timingMode", "explicit_phases"))
        val theirs = row("lifecycles", "cook-1", 2, deviceB, JSONObject().put("cookId", "cook-1").put("timingMode", "legacy_preserved"))
        val conflict = PrivateSyncMerge.merge(base, JSONArray().put(mine), JSONArray().put(theirs)).getJSONArray("conflicts").getJSONObject(0)
        val view = PrivateSyncProtocol.conflictView(conflict, deviceA)
        assertEquals("Edited on two devices", view.reason); assertFalse(view.canKeepBoth)
        assertThrows(Exception::class.java) { PrivateSyncProtocol.resolve(conflict, "both", deviceA) }
        assertEquals("Meal \"Weeknight cook\"", PrivateSyncProtocol.summarize(row("cooks", "cook-9", 1, deviceA, JSONObject().put("title", "Weeknight cook"))))
        assertEquals("Reading 74.1 °C", PrivateSyncProtocol.summarize(row("readings", "r-1", 1, deviceA, JSONObject().put("value", 74.1).put("unit", "°C"))))
        assertTrue(PrivateSyncProtocol.summarize(row("events", "note-9", 1, deviceA, null, deleted = true)).endsWith("deleted"))
    }
}
