package com.pittech.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class PrivateSyncCoordinatorTest {
    @Test fun automaticSyncRequiresExplicitAttachmentOptInAndTheCurrentLibraryAccount() = runTest {
        val library = FakeLibrary(archive("cooks" to cook()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore(); val session = FakeSession(userA)
        val sync = coordinator(store = store, session = session, api = api, library = library, scope = this)
        sync.syncNow(automatic = true); sync.setAutomatic(true)
        assertTrue(api.puts.isEmpty()); assertFalse(store.load(userA).automatic)
        sync.syncNow(); assertEquals(1, api.puts.size)
        sync.syncNow(automatic = true); assertEquals(1, api.puts.size)
        sync.setAutomatic(true); assertTrue(store.load(userA).automatic)
        sync.syncNow(automatic = true); assertTrue(api.puts.size > 1)
        val requests = api.puts.size
        store.save(userB, PrivateSyncState(account = userB, lastSyncedAt = 1000, automatic = true))
        session.user = userB; sync.syncNow(automatic = true)
        assertEquals(requests, api.puts.size); assertEquals(userA, store.libraryAccount())
        sync.detachLibrary(); assertNull(store.libraryAccount()); assertEquals(requests, api.puts.size)
    }
    private val userA = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private val userB = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private val deviceB = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun projection() = PrivateSyncProjection(resource("private-sync-projection-v1"))

    private fun cook(id: String = "cook-1", title: String = "Weeknight cook") = JSONObject()
        .put("id", id).put("title", title).put("status", "active").put("startedAtUtcMillis", 1000).put("startedTimeZoneId", "UTC")
        .put("createdAtUtcMillis", 1000).put("updatedAtUtcMillis", 1000)
    private fun dish(id: String = "dish-1", cookId: String = "cook-1") = JSONObject()
        .put("id", id).put("cookId", cookId).put("name", "Brisket").put("foodType", "beef").put("createdAtUtcMillis", 1000).put("updatedAtUtcMillis", 1000)
    private fun note(id: String = "note-1", cookId: String = "cook-1", title: String = "Taste check") = JSONObject()
        .put("id", id).put("cookId", cookId).put("eventType", "note").put("title", title).put("occurredAtUtcMillis", 1000)
        .put("recordedAtUtcMillis", 1000).put("timeZoneId", "UTC").put("source", "manual").put("createdAtUtcMillis", 1000).put("updatedAtUtcMillis", 1000)
    private fun photoRow(id: String = "photo-1", cookId: String = "cook-1") = JSONObject()
        .put("id", id).put("cookId", cookId).put("archivePath", "attachments/$id.jpg").put("originalFileName", "$id.jpg")
        .put("mimeType", "image/jpeg").put("addedAtUtcMillis", 1000)
    private fun readingRow(id: String = "reading-1", value: Double = 74.0) = JSONObject()
        .put("id", id).put("cookId", "cook-1").put("probeName", "Pit").put("measurementType", "temperature").put("value", value).put("unit", "°C")
        .put("measuredAtUtcMillis", 1000).put("timeZoneId", "UTC").put("source", "manual").put("qualityStatus", "valid").put("recordedAtUtcMillis", 1000).put("timestampBasis", "measurement").put("samplingIntervalMillis", 15000)
    private fun archive(vararg rows: Pair<String, JSONObject>): JSONObject {
        val root = JSONObject()
        for ((type, row) in rows) {
            val array = root.optJSONArray(type) ?: JSONArray().also { root.put(type, it) }
            array.put(row)
        }
        return root
    }
    private fun versioned(type: String, id: String, counter: Long, client: String, payload: JSONObject?, assets: List<String> = emptyList(), deleted: Boolean = false) = JSONObject()
        .put("recordType", type).put("recordId", id).put("version", JSONObject().put("counter", counter).put("clientId", client))
        .put("deleted", deleted).put("payload", payload ?: JSONObject.NULL).put("assetIds", JSONArray(assets))

    private class FakeSession(var user: String?) : PublicationSession {
        var refreshes = 0
        override suspend fun userId() = user
        override suspend fun access(refresh: Boolean): String {
            val current = user ?: throw AccountFailure(401)
            if (refresh) refreshes++
            return "token-$current".padEnd(43, 'x')
        }
    }

    private class FakeApi : PrivateSyncAPI {
        val puts = mutableListOf<Pair<ByteArray, String>>()
        var snapshot: JSONObject = JSONObject().put("version", 0).put("envelope", JSONObject.NULL)
        var commitHandler: (ByteArray, String) -> JSONObject = { bytes, mutation -> JSONObject().put("version", JSONObject(String(bytes, Charsets.UTF_8)).getLong("baseVersion") + 1).put("mutationId", mutation).put("sha256", RelayProtocol.hash(bytes)) }
        var failParts: MutableSet<Int> = mutableSetOf()
        val photoState = mutableMapOf<String, JSONObject>()
        val downloads = mutableMapOf<String, ByteArray>()
        var calls = 0
        override suspend fun snapshot(access: String): JSONObject { calls++; return JSONObject(snapshot.toString()) }
        override suspend fun commit(body: ByteArray, mutationId: String, access: String): JSONObject { calls++; puts += body.copyOf() to mutationId; return commitHandler(body, mutationId) }
        override suspend fun beginPhoto(assetId: String, bytes: Long, mime: String, sha: String, access: String): JSONObject {
            calls++; photoState[assetId] = JSONObject().put("assetId", assetId).put("bytes", bytes).put("receivedParts", JSONArray()).put("complete", false); return JSONObject(photoState.getValue(assetId).toString())
        }
        override suspend fun photoStatus(assetId: String, access: String): JSONObject {
            calls++; return JSONObject((photoState[assetId] ?: throw PrivateSyncHttpError(404, "photo_not_found")).toString())
        }
        override suspend fun putPart(assetId: String, index: Int, chunk: ByteArray, access: String): JSONObject {
            calls++; if (index in failParts) throw IOException("interrupted")
            val status = photoState.getValue(assetId)
            status.getJSONArray("receivedParts").put(index)
            return JSONObject(status.toString())
        }
        override suspend fun completePhoto(assetId: String, access: String): JSONObject {
            calls++; photoState.getValue(assetId).put("complete", true); return JSONObject(photoState.getValue(assetId).toString())
        }
        override suspend fun abandonPhoto(assetId: String, access: String): JSONObject { calls++; photoState.remove(assetId); return JSONObject().put("abandoned", true) }
        override suspend fun downloadPhoto(assetId: String, to: File, access: String): File {
            calls++; to.writeBytes(downloads[assetId] ?: throw PrivateSyncHttpError(404, "photo_not_found")); return to
        }
    }

    private class FakeLibrary(var current: JSONObject, val photoData: MutableMap<String, ByteArray> = mutableMapOf()) : PrivateSyncLibrary {
        val applies = mutableListOf<PrivateSyncApplyPlan>()
        override suspend fun archive() = JSONObject(current.toString())
        override suspend fun photoBytes(type: String, id: String) = photoData["$type|$id"]?.let { PrivateSyncPhotoSource(it.copyOf(), "image/jpeg") }
        override suspend fun apply(plan: PrivateSyncApplyPlan) {
            applies += plan
            for (delete in plan.deletes) {
                val key = when (delete.type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
                val rows = current.optJSONArray(delete.type) ?: JSONArray()
                val kept = JSONArray()
                for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString(key, "") != delete.id) kept.put(rows.getJSONObject(i))
                current.put(delete.type, kept)
            }
            for (upsert in plan.upserts) {
                val key = when (upsert.type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
                val rows = current.optJSONArray(upsert.type) ?: JSONArray().also { current.put(upsert.type, it) }
                val next = JSONArray(); var replaced = false
                for (i in 0 until rows.length()) {
                    if (rows.getJSONObject(i).optString(key, "") == upsert.id) {
                        val merged = JSONObject(rows.getJSONObject(i).toString())
                        for (field in upsert.payload.keys()) merged.put(field, upsert.payload.get(field))
                        next.put(merged); replaced = true
                    } else next.put(rows.getJSONObject(i))
                }
                if (!replaced) next.put(JSONObject(upsert.payload.toString()))
                current.put(upsert.type, next)
            }
        }
    }

    private fun coordinator(store: PrivateSyncStorage = PrivateSyncMemoryStore(), session: FakeSession = FakeSession(userA), api: PrivateSyncAPI = FakeApi(), library: PrivateSyncLibrary, scope: CoroutineScope) =
        PrivateSyncCoordinator(store, session, scope, api, library, projection(), enabled = true, now = { 1_700_000_000_000L })

    private fun eventRow(root: JSONObject, id: String): JSONObject? {
        val rows = root.optJSONArray("events") ?: return null
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString("id") == id) return rows.getJSONObject(i)
        return null
    }

    @Test fun freshSyncUploadsProjectionOnlyAndAdvancesBase() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        assertEquals(1, api.puts.size)
        val body = JSONObject(String(api.puts.single().first, Charsets.UTF_8))
        assertEquals(0, body.getLong("baseVersion"))
        // Snapshot build is validator output only: every uploaded row projects from the journal.
        val expected = projection().project(library.archive(), emptyMap())
        assertEquals(expected.length(), body.getJSONArray("records").length())
        for (i in 0 until expected.length()) {
            val row = expected.getJSONObject(i)
            val uploaded = (0 until body.getJSONArray("records").length()).map { body.getJSONArray("records").getJSONObject(it) }
                .single { it.getString("recordType") == row.getString("recordType") && it.getString("recordId") == row.getString("recordId") }
            assertEquals(PrivateSyncMerge.stable(row.getJSONObject("payload")), PrivateSyncMerge.stable(uploaded.getJSONObject("payload")))
            assertEquals(1, uploaded.getJSONObject("version").getLong("counter"))
        }
        val state = store.load(userA)
        assertEquals(1, state.serverVersion); assertFalse(state.pending); assertNull(state.mutationId)
        assertEquals(1_700_000_000_000L, state.lastSyncedAt); assertEquals("ok", state.outcome)
        assertTrue(sync.presentation.value.status.startsWith("Last synced "))
        assertTrue(sync.presentation.value.signedIn)
    }

    @Test fun rejectedProjectionBlocksUploadWithoutTouchingLocalData() = runTest {
        val before = archive("cooks" to cook(), "dishes" to dish(), "events" to note()).put("settings", JSONObject().put("theme", "dark"))
        val library = FakeLibrary(before)
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        assertEquals(0, api.calls)
        assertTrue(library.applies.isEmpty())
        assertEquals(PrivateSyncMerge.stable(before), PrivateSyncMerge.stable(library.archive()))
        assertEquals("Retry needed", sync.presentation.value.status)
        assertEquals(0, store.load(userA).serverVersion)
    }

    @Test fun conflict409MergeRetryPreservesBothDivergentEdits() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        val base = JSONArray(store.load(userA).base)
        // Local edit plus a remote edit at a higher counter: the remote wins, the local edit is retained.
        library.current.optJSONArray("events")!!.getJSONObject(0).put("title", "Mine")
        val remoteNote = note(title = "Theirs")
        val remote = JSONArray()
        for (i in 0 until base.length()) {
            val row = base.getJSONObject(i)
            remote.put(if (row.getString("recordType") == "events") versioned("events", "note-1", 3, deviceB, remoteNote) else JSONObject(row.toString()))
        }
        api.snapshot = JSONObject().put("version", 2).put("envelope", JSONObject().put("contractVersion", 1).put("records", remote).put("conflicts", JSONArray()))
        var commits = 0
        api.commitHandler = { bytes, mutation ->
            if (++commits == 1) throw PrivateSyncHttpError(409, "sync_version_changed")
            JSONObject().put("version", JSONObject(String(bytes, Charsets.UTF_8)).getLong("baseVersion") + 1).put("mutationId", mutation).put("sha256", RelayProtocol.hash(bytes))
        }
        val racerNote = note("note-2", title = "Racer")
        val racer = JSONArray()
        for (i in 0 until remote.length()) racer.put(remote.getJSONObject(i))
        racer.put(versioned("events", "note-2", 4, deviceB, racerNote))
        val secondSnapshot = JSONObject().put("version", 3).put("envelope", JSONObject().put("contractVersion", 1).put("records", racer).put("conflicts", JSONArray()))
        var snapshots = 0
        val counting = object : PrivateSyncAPI by api {
            override suspend fun snapshot(access: String): JSONObject { snapshots++; return if (snapshots == 1) api.snapshot(access).also { api.snapshot = secondSnapshot } else api.snapshot(access) }
        }
        val retrying = coordinator(store = store, session = FakeSession(userA), api = counting, library = library, scope = this)
        retrying.syncNow()
        assertEquals(3, api.puts.size)
        assertNotEquals(api.puts[1].second, api.puts[2].second)
        val retry = JSONObject(String(api.puts[2].first, Charsets.UTF_8))
        assertEquals(3, retry.getLong("baseVersion"))
        val records = (0 until retry.getJSONArray("records").length()).map { retry.getJSONArray("records").getJSONObject(it) }
        // Both divergent edits survive: the winner in records, the loser as a retained copy.
        assertEquals("Theirs", records.single { it.getString("recordId") == "note-1" }.getJSONObject("payload").getString("title"))
        assertEquals(deviceB, records.single { it.getString("recordId") == "note-1" }.getJSONObject("version").getString("clientId"))
        assertTrue(records.any { it.getString("recordId") == "note-2" })
        assertEquals(1, retry.getJSONArray("conflicts").length())
        val conflict = retry.getJSONArray("conflicts").getJSONObject(0)
        assertEquals("edit_edit", conflict.getString("reason"))
        assertEquals("Mine", conflict.getJSONObject("loser").getJSONObject("payload").getString("title"))
        assertEquals("Theirs", eventRow(library.archive(), "note-1")!!.getString("title"))
        assertNotNull(eventRow(library.archive(), "note-2"))
        assertEquals("Conflicts to review", retrying.presentation.value.status)
        assertEquals(1, retrying.presentation.value.conflicts.size)
        // Keep mine replays the losing edit under the same identity; the next upload resolves the copy.
        retrying.resolve(conflict.getString("id"), "mine")
        assertEquals("Mine", eventRow(library.archive(), "note-1")!!.getString("title"))
        api.snapshot = JSONObject().put("version", 4).put("envelope", JSONObject().put("contractVersion", 1).put("records", JSONArray(retry.getJSONArray("records").toString())).put("conflicts", JSONArray(retry.getJSONArray("conflicts").toString())))
        retrying.syncNow()
        val resolved = JSONObject(String(api.puts.last().first, Charsets.UTF_8))
        assertEquals(0, resolved.getJSONArray("conflicts").length())
        assertEquals(conflict.getString("id"), resolved.getJSONArray("resolvedConflictIds").getString(0))
        assertTrue(retrying.presentation.value.status.startsWith("Last synced "))
    }

    @Test fun interruptedPhotoUploadResumesFromDurablePartState() = runTest {
        val bytes = ByteArray(2 * 1024 * 1024 + 512) { it.toByte() }
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note(), "photos" to photoRow()), mutableMapOf("photos|photo-1" to bytes))
        val api = FakeApi(); api.failParts = mutableSetOf(1)
        val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        try { sync.syncNow() } catch (_: Exception) { }
        assertEquals(0, api.puts.size)
        val partial = store.load(userA).photos.getValue("photos|photo-1")
        assertFalse(partial.complete); assertEquals(listOf(0), partial.received)
        // A restart (new coordinator, same durable store) resumes with only the missing parts.
        api.failParts = mutableSetOf()
        val resumed = coordinator(store = store, session = FakeSession(userA), api = api, library = library, scope = this)
        val partsBefore = api.photoState.values.sumOf { it.getJSONArray("receivedParts").length() }
        resumed.syncNow()
        assertEquals(1, api.puts.size)
        val entry = store.load(userA).photos.getValue("photos|photo-1")
        assertTrue(entry.complete); assertEquals(listOf(0, 1, 2), entry.received)
        assertEquals(entry.assetId, api.photoState.keys.single())
        assertTrue(api.photoState.getValue(entry.assetId).getBoolean("complete"))
        assertTrue(partsBefore >= 1)
    }

    @Test fun accountSwitchCancelsOutboxAndNeverAttachesToAnotherAccount() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val session = FakeSession(userA)
        val sync = coordinator(store = store, session = session, api = api, library = library, scope = this)
        sync.syncNow()
        assertEquals(1, api.puts.size)
        // An ambiguous outcome retains the exact retry bytes for the same account.
        api.commitHandler = { _, _ -> throw IOException("interrupted") }
        library.current.optJSONArray("events")!!.getJSONObject(0).put("title", "Edited offline")
        sync.syncNow()
        val pending = store.load(userA)
        assertNotNull(pending.mutationId); assertTrue(pending.pending); assertEquals("Waiting for connection", sync.presentation.value.status)
        session.user = userB
        val callsBefore = api.calls
        sync.refresh()
        // The switch cancels the old account's outbox on a background dispatcher and
        // refresh() does not suspend for it: await the cancellation instead of racing
        // it with a fixed delay.
        withTimeout(10_000) { while (store.load(userA).mutationId != null) delay(10) }
        // Nothing syncs silently on switch; the new account starts clean.
        assertEquals(callsBefore, api.calls)
        val cancelled = store.load(userA)
        assertNull(cancelled.mutationId); assertNull(cancelled.mutationSha)
        assertEquals(3, JSONArray(cancelled.base).length())
        try { store.readBody(userA, pending.mutationId!!); fail() } catch (_: Exception) { }
        api.commitHandler = { bytes, mutation -> JSONObject().put("version", 1).put("mutationId", mutation).put("sha256", RelayProtocol.hash(bytes)) }
        sync.syncNow()
        assertEquals(3, api.puts.size)
        val other = JSONObject(String(api.puts[2].first, Charsets.UTF_8))
        // The new account starts its own backup from scratch on explicit request: fresh
        // base version, no resolutions, none of the prior account's journal attached.
        assertEquals(0, other.getLong("baseVersion"))
        assertFalse(other.has("resolvedConflictIds"))
        assertEquals(0, other.getJSONArray("conflicts").length())
        assertEquals(1, store.load(userB).serverVersion)
        assertEquals(1, store.load(userA).serverVersion)
        assertEquals(3, JSONArray(store.load(userA).base).length())
    }

    @Test fun tombstoneResolutionRestoresLosingEditAsNewIdentity() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        val base = JSONArray(store.load(userA).base)
        library.current.optJSONArray("events")!!.getJSONObject(0).put("title", "Edited offline")
        val remote = JSONArray()
        for (i in 0 until base.length()) {
            val row = base.getJSONObject(i)
            remote.put(if (row.getString("recordType") == "events") versioned("events", "note-1", 2, deviceB, null, deleted = true) else JSONObject(row.toString()))
        }
        api.snapshot = JSONObject().put("version", 2).put("envelope", JSONObject().put("contractVersion", 1).put("records", remote).put("conflicts", JSONArray()))
        sync.syncNow()
        assertNull(eventRow(library.archive(), "note-1"))
        assertEquals("Conflicts to review", sync.presentation.value.status)
        val conflict = JSONObject(JSONArray(store.load(userA).conflicts).getJSONObject(0).toString())
        assertEquals("delete_edit", conflict.getString("reason"))
        sync.resolve(conflict.getString("id"), "both")
        val restored = library.archive().optJSONArray("events")!!
        assertEquals(1, restored.length())
        assertNotEquals("note-1", restored.getJSONObject(0).getString("id"))
        assertEquals("Edited offline", restored.getJSONObject(0).getString("title"))
        val committed = JSONObject(String(api.puts.last().first, Charsets.UTF_8))
        api.snapshot = JSONObject().put("version", 3).put("envelope", JSONObject().put("contractVersion", 1).put("records", JSONArray(committed.getJSONArray("records").toString())).put("conflicts", JSONArray(committed.getJSONArray("conflicts").toString())))
        sync.syncNow()
        val body = JSONObject(String(api.puts.last().first, Charsets.UTF_8))
        assertEquals(conflict.getString("id"), body.getJSONArray("resolvedConflictIds").getString(0))
        val records = (0 until body.getJSONArray("records").length()).map { body.getJSONArray("records").getJSONObject(it) }
        assertTrue(records.single { it.getString("recordId") == "note-1" }.getBoolean("deleted"))
        assertEquals(1, records.count { !it.getBoolean("deleted") && it.getString("recordType") == "events" })
    }

    @Test fun immutableRewriteBlocksCommitWhilePreservingBothVersions() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "readings" to readingRow().put("source", "controller_cloud")))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        assertEquals(1, api.puts.size)
        library.current.optJSONArray("readings")!!.getJSONObject(0).put("value", 75.0)
        api.snapshot = JSONObject().put("version", 1).put("envelope", JSONObject().put("contractVersion", 1).put("records", JSONArray(store.load(userA).base)).put("conflicts", JSONArray()))
        sync.syncNow()
        assertEquals(1, api.puts.size)
        assertEquals("Conflicts to review", sync.presentation.value.status)
        // The local rewrite stays in the journal; the acknowledged base still holds the original.
        assertEquals(75.0, library.archive().optJSONArray("readings")!!.getJSONObject(0).getDouble("value"), 0.0)
        val baseReading = (0 until JSONArray(store.load(userA).base).length()).map { JSONArray(store.load(userA).base).getJSONObject(it) }.single { it.getString("recordType") == "readings" }
        assertEquals(74.0, baseReading.getJSONObject("payload").getDouble("value"), 0.0)
    }

    @Test fun ambiguousUploadRetriesExactBytesAndNeverClaimsUnacknowledgedSuccess() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        api.commitHandler = { _, _ -> throw IOException("interrupted") }
        sync.syncNow()
        assertEquals("Waiting for connection", sync.presentation.value.status)
        assertEquals(0, store.load(userA).lastSyncedAt)
        assertEquals(0, store.load(userA).serverVersion)
        val first = api.puts.single()
        api.commitHandler = { bytes, mutation -> JSONObject().put("version", 1).put("mutationId", mutation).put("sha256", RelayProtocol.hash(bytes)) }
        sync.syncNow()
        assertEquals(2, api.puts.size)
        assertEquals(first.second, api.puts[1].second)
        assertArrayEquals(first.first, api.puts[1].first)
        assertTrue(sync.presentation.value.status.startsWith("Last synced "))
    }

    @Test fun concurrentWriteOnAnAffectedRowAbortsSoTheNextPassMergesIt() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        assertEquals(1, api.puts.size)
        val base = JSONArray(store.load(userA).base)
        val remote = JSONArray()
        for (i in 0 until base.length()) {
            val row = base.getJSONObject(i)
            remote.put(if (row.getString("recordType") == "events") versioned("events", "note-1", 2, deviceB, note(title = "Remote")) else JSONObject(row.toString()))
        }
        api.snapshot = JSONObject().put("version", 2).put("envelope", JSONObject().put("contractVersion", 1).put("records", remote).put("conflicts", JSONArray()))
        val racing = object : PrivateSyncLibrary by library {
            var reads = 0
            override suspend fun archive(): JSONObject {
                if (++reads == 2) library.current.optJSONArray("events")!!.getJSONObject(0).put("title", "Raced ahead")
                return library.archive()
            }
        }
        val racer = coordinator(store = store, session = FakeSession(userA), api = api, library = racing, scope = this)
        racer.syncNow()
        // The raced row is left for the next pass; the unrelated remote rows still applied.
        assertEquals(1, api.puts.size)
        assertEquals("Retry needed", racer.presentation.value.status)
        assertTrue(library.applies.isEmpty())
        assertEquals("Raced ahead", eventRow(library.archive(), "note-1")!!.getString("title"))
        racer.syncNow()
        assertEquals(2, api.puts.size)
        val retry = JSONObject(String(api.puts[1].first, Charsets.UTF_8))
        assertEquals(1, retry.getJSONArray("conflicts").length())
        assertEquals("Raced ahead", retry.getJSONArray("conflicts").getJSONObject(0).getJSONObject("winner").getJSONObject("payload").getString("title"))
        assertEquals("Remote", retry.getJSONArray("conflicts").getJSONObject(0).getJSONObject("loser").getJSONObject("payload").getString("title"))
    }

    @Test fun mismatchedReceiptNeverAdvancesBase() = runTest {
        val library = FakeLibrary(archive("cooks" to cook(), "dishes" to dish(), "events" to note()))
        val api = FakeApi(); val store = PrivateSyncMemoryStore()
        api.commitHandler = { bytes, _ -> JSONObject().put("version", 1).put("mutationId", "ffffffff-ffff-4fff-8fff-ffffffffffff").put("sha256", RelayProtocol.hash(bytes)) }
        val sync = coordinator(store = store, api = api, library = library, scope = this)
        sync.syncNow()
        assertEquals("Retry needed", sync.presentation.value.status)
        assertEquals(0, store.load(userA).serverVersion)
        assertEquals(0, JSONArray(store.load(userA).base).length())
        assertNotNull(store.load(userA).mutationId)
    }
}
