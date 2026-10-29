package com.pittech.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

internal data class PrivateSyncPresentation(val status: String = "Sync is off", val detail: String? = null, val lastSyncedAt: Long = 0, val conflicts: List<PrivateSyncConflictView> = emptyList(), val busy: Boolean = false, val signedIn: Boolean = false, val automatic: Boolean = false)

/** Device-authoritative private sync. Local writes always complete first; network work is
 * queued here. First attachment is explicit; opted-in foreground opportunities use only the current account,
 * and stays disabled until the accounts/social/sync/deletion phase passes. */
internal class PrivateSyncCoordinator(
    private val store: PrivateSyncStorage,
    private val session: PublicationSession,
    private val scope: CoroutineScope,
    private val api: PrivateSyncAPI = PrivateSyncClient(),
    private val library: PrivateSyncLibrary,
    private val projection: PrivateSyncProjection,
    val enabled: Boolean = AccountProtocol.ENABLED,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var boundUser: String? = null
    private val _presentation = MutableStateFlow(PrivateSyncPresentation())
    val presentation = _presentation.asStateFlow()
    private var automaticJob: kotlinx.coroutines.Job? = null
    private var lastAutomaticAt: Long? = null
    private var automaticForeground = false

    /** First attachment is always explicit. This device/account opt-in is never synced. */
    suspend fun setAutomatic(value: Boolean) = mutex.withLock {
        val user = bind() ?: return@withLock
        if (!enabled) return@withLock
        val state = withContext(Dispatchers.IO) { store.load(user) }
        if (value && (state.lastSyncedAt <= 0 || withContext(Dispatchers.IO) { store.libraryAccount() } != user)) return@withLock
        withContext(Dispatchers.IO) { store.save(user, state.copy(automatic = value)) }
        if (!value) { automaticJob?.cancel(); automaticJob = null }
        publishBind(busy = false)
    }

    /** Coalesce foreground/user-write opportunities; never start an unattended polling loop. */
    fun requestAutomaticSync() {
        if (!enabled || !automaticForeground || _presentation.value.busy || automaticJob?.isActive == true) return
        automaticJob = scope.launch {
            kotlinx.coroutines.delay(1500)
            val wait = lastAutomaticAt?.let { (30_000L - (System.nanoTime() / 1_000_000L - it)).coerceAtLeast(0) } ?: 0L
            kotlinx.coroutines.delay(wait)
            syncNow(automatic = true)
        }
    }
    fun resumeAutomaticSync() { automaticForeground = true; requestAutomaticSync() }
    fun pauseAutomaticSync() { automaticForeground = false; automaticJob?.cancel(); automaticJob = null }
    /** A device-only clear must never become cloud deletion tombstones. */
    suspend fun detachLibrary() = mutex.withLock {
        pauseAutomaticSync()
        withContext(Dispatchers.IO) { store.libraryAccount()?.let { store.purgeAccount(it) } }
        publishBind(busy = false)
    }

    fun refresh() { scope.launch { mutex.withLock { bind(); publishBind(busy = false) } } }

    /** Bind the current session account. A switch or sign-out cancels that account's
     * pending upload; the acknowledged base stays so the same account can resume, and
     * nothing is ever attached to another account. */
    private suspend fun bind(): String? {
        val user = try { session.userId() } catch (_: Exception) { null }
        if (user != boundUser) {
            boundUser?.let { old ->
                val state = withContext(Dispatchers.IO) { store.load(old) }
                state.mutationId?.let { withContext(Dispatchers.IO) { store.bodyFile(old, it).delete() } }
                withContext(Dispatchers.IO) { store.save(old, state.copy(mutationId = null, mutationSha = null)) }
            }
            boundUser = user
        }
        return user
    }

    suspend fun syncNow(automatic: Boolean = false) = mutex.withLock {
        val current = bind()
        if (automatic) {
            val user = current ?: return@withLock
            val state = withContext(Dispatchers.IO) { store.load(user) }
            if (!enabled || !state.automatic || state.lastSyncedAt <= 0 || withContext(Dispatchers.IO) { store.libraryAccount() } != user) return@withLock
            lastAutomaticAt = System.nanoTime() / 1_000_000L
        } else if (enabled && current != null) {
            withContext(Dispatchers.IO) {
                if (store.libraryAccount() != current) store.save(current, store.load(current).copy(automatic = false))
                store.attachLibrary(current)
            }
        }
        publishBind(busy = true)
        try {
            attempt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            boundUser?.let { user ->
                val state = withContext(Dispatchers.IO) { store.load(user) }
                withContext(Dispatchers.IO) { store.save(user, state.copy(outcome = "retry", message = "Private sync needs a retry. Your local journal is unchanged.")) }
            }
            publishBind(busy = false)
        } finally {
            // Lifecycle cancellation also cancels suspendable ledger reads. Release
            // the UI guard directly so a foreground opportunity can retry the outbox.
            _presentation.value = _presentation.value.copy(busy = false)
        }
    }

    suspend fun resolve(id: String, choice: String) = mutex.withLock {
        publishBind(busy = true)
        try {
            val user = bind()
            if (!enabled || user == null) { publishBind(busy = false); return }
            val device = withContext(Dispatchers.IO) { store.deviceId() }
            var state = withContext(Dispatchers.IO) { store.load(user) }
            val retained = JSONArray(state.conflicts)
            val conflict = (0 until retained.length()).map { retained.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
                ?: error("This conflict is no longer pending review.")
            val resolution = PrivateSyncProtocol.resolve(conflict, choice, device)
            if (resolution.restoreType != null) {
                val archive = withContext(Dispatchers.IO) { library.archive() }
                val assets = state.photos.mapValues { it.value.assetId }.toMutableMap()
                val attachments = mutableMapOf<String, ByteArray>()
                if (resolution.restoreAssetId != null) {
                    val bytes = restoreBytes(resolution, state, user)
                    if (bytes == null) {
                        withContext(Dispatchers.IO) { store.save(user, state.copy(outcome = "waiting", message = "Waiting for connection. The conflict is still pending review.")) }
                        publishBind(busy = false); return
                    }
                    attachments[resolution.restoreId!!] = bytes
                    assets[resolution.restoreType + "|" + resolution.restoreId] = resolution.restoreAssetId
                }
                val upsert = PrivateSyncUpsert(resolution.restoreType, resolution.restoreId!!, resolution.restorePayload!!)
                try {
                    projection.project(validatedArchive(archive, listOf(upsert), emptyList()), assets)
                } catch (e: Exception) {
                    withContext(Dispatchers.IO) { store.save(user, state.copy(outcome = "retry", message = "This resolution could not be applied safely. Your journal is unchanged.")) }
                    publishBind(busy = false); return
                }
                withContext(Dispatchers.IO) { library.apply(PrivateSyncApplyPlan(listOf(upsert), emptyList(), attachments)) }
                if (resolution.restoreAssetId != null) {
                    val key = resolution.restoreType + "|" + resolution.restoreId
                    val entry = PrivateSyncPhotoEntry(resolution.restoreAssetId, RelayProtocol.hash(attachments.getValue(resolution.restoreId)), attachments.getValue(resolution.restoreId).size, mimeOf(resolution.restorePayload), (0 until partsFor(attachments.getValue(resolution.restoreId).size)).toList(), true)
                    state = state.copy(photos = state.photos + (key to entry))
                }
            }
            val remaining = JSONArray((0 until retained.length()).map { retained.getJSONObject(it) }.filter { it.getString("id") != id })
            state = state.copy(conflicts = remaining.toString(), resolved = (state.resolved + id).distinct(), pending = true)
            withContext(Dispatchers.IO) { store.save(user, state) }
            publishBind(busy = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publishBind(busy = false)
        } finally {
            _presentation.value = _presentation.value.copy(busy = false)
        }
    }

    private suspend fun restoreBytes(resolution: ConflictResolution, state: PrivateSyncState, user: String): ByteArray? {
        val assetId = resolution.restoreAssetId ?: return null
        val originalId = resolution.restorePayload!!.optString(originalKey(resolution.restoreType!!), "")
        val local = withContext(Dispatchers.IO) { library.photoBytes(resolution.restoreType, originalId) }
        // Keep both of a locally-lost photo reuses the bytes already on this device.
        if (local != null && state.photos[resolution.restoreType + "|" + originalId]?.assetId == assetId) return local.bytes
        val staging = withContext(Dispatchers.IO) { store.stagingDir() }
        val file = java.io.File(staging, "$assetId.download")
        return try {
            authorized(user) { token -> api.downloadPhoto(assetId, file, token) }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            require(bytes.size in 1..PrivateSyncProtocol.MAX_PHOTO_BYTES); bytes
        } catch (_: IOException) { null } catch (_: PrivateSyncHttpError) { null }
        finally { withContext(Dispatchers.IO) { file.delete() } }
    }

    private fun originalKey(type: String) = when (type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
    private fun mimeOf(payload: JSONObject) = payload.optString("mimeType", "image/jpeg").takeIf { it in setOf("image/jpeg", "image/png", "image/webp") } ?: "image/jpeg"
    private fun partsFor(bytes: Int) = (bytes + PrivateSyncProtocol.CHUNK_BYTES - 1) / PrivateSyncProtocol.CHUNK_BYTES

    private suspend fun attempt() {
        val user = bind()
        if (!enabled || user == null) { publishBind(busy = false); return }
        val device = withContext(Dispatchers.IO) { store.deviceId() }
        var state = withContext(Dispatchers.IO) { store.load(user) }
        var archive = try { withContext(Dispatchers.IO) { library.archive() } }
        catch (e: Exception) { fail(user, state, "retry", "Private sync needs a retry. Your local journal is unchanged."); return }
        // ensureRegistry persists durable photo progress as it goes; a failure must keep
        // that progress, so fail from the reloaded state instead of the pre-registry one.
        state = try { withContext(Dispatchers.IO) { ensureRegistry(user, state, archive) } }
        catch (e: IOException) { fail(user, withContext(Dispatchers.IO) { store.load(user) }, "waiting", "Waiting for connection. Photo uploads resume where they stopped."); return }
        catch (e: Exception) { fail(user, withContext(Dispatchers.IO) { store.load(user) }, "retry", e.message ?: "Private sync needs a retry."); return }
        var assets = state.photos.mapValues { it.value.assetId }
        var projected = try { projection.project(archive, assets) }
        catch (e: Exception) {
            // A library that fails projection fails the attempt. Nothing uploads; nothing changes locally.
            fail(user, state, "retry", e.message ?: "Some saved information cannot be synced safely yet. Your journal is unchanged."); return
        }
        var local = PrivateSyncProtocol.stampLocal(projected, JSONArray(state.base), device, state.maxCounter)
        // Every mutation advances the logical counter beyond observed counters, including on
        // paths that never merge (a fresh upload), so no version is ever stamped twice.
        state = advancedAfterStamp(user, state, local)
        var fetched = fetch(user, state) ?: return
        var attempts = 0
        // Records from the last merge pass. A 409 retry stamps the rebuilt journal against
        // these instead of the acknowledged base, so already-merged winner versions survive
        // the retry instead of being restamped with this device's identity.
        var lastMerged: JSONArray? = null
        while (true) {
            val (version, envelope) = fetched
            if (envelope == null) {
                if (state.serverVersion == 0L && JSONArray(state.base).length() == 0 && version == 0L) {
                    when (upload(user, state, 0, local, JSONArray(state.conflicts), JSONArray(), state.resolved, emptySet(), projectedKeys(projected))) {
                        UploadResult.ACKED, UploadResult.FAILED -> return
                        UploadResult.CONFLICT -> {
                            // Another device created the backup first: fall through to fetch/merge/retry.
                            if (++attempts >= 3) { fail(user, withContext(Dispatchers.IO) { store.load(user) }, "retry", "Another device keeps changing the backup. Retry shortly."); return }
                            fetched = fetch(user, withContext(Dispatchers.IO) { store.load(user) }) ?: return
                            state = withContext(Dispatchers.IO) { store.load(user) }
                            continue
                        }
                    }
                }
                // The server holds no snapshot while this device remembers one: rebase by
                // restamping every row as this device's own mutation. Nothing is dropped silently.
                if (version != 0L) { fail(user, state, "retry", "Private sync needs a retry. Your local journal is unchanged."); return }
                val rebasedRows = restampAll(projected, device, withContext(Dispatchers.IO) { store.load(user) }.maxCounter)
                val rebasedState = advancedAfterStamp(user, withContext(Dispatchers.IO) { store.load(user).copy(conflicts = "[]", resolved = emptyList()) }, rebasedRows)
                when (upload(user, rebasedState, 0, rebasedRows, JSONArray(), JSONArray(), emptyList(), emptySet(), projectedKeys(projected))) {
                    UploadResult.ACKED, UploadResult.FAILED -> return
                    UploadResult.CONFLICT -> {
                        if (++attempts >= 3) { fail(user, withContext(Dispatchers.IO) { store.load(user) }, "retry", "Another device keeps changing the backup. Retry shortly."); return }
                        fetched = fetch(user, withContext(Dispatchers.IO) { store.load(user) }) ?: return
                        state = withContext(Dispatchers.IO) { store.load(user) }
                        continue
                    }
                }
            }
            if (version < state.serverVersion) { fail(user, state, "retry", "Private sync needs a retry. Your local journal is unchanged."); return }
            val remoteRecords = envelope.getJSONArray("records"); val remoteConflicts = envelope.getJSONArray("conflicts")
            try {
                projection.validateRows(liveProjected(remoteRecords))
            } catch (e: Exception) { fail(user, state, "retry", "The server backup could not be applied safely. Your journal is unchanged."); return }
            val merged = try { PrivateSyncMerge.merge(JSONArray(state.base), local, remoteRecords) }
            catch (e: Exception) { fail(user, state, "retry", "The server backup could not be merged safely. Your journal is unchanged."); return }
            state = state.copy(maxCounter = maxOf(state.maxCounter, merged.getLong("nextCounter")))
            val previousConflicts = JSONArray(state.conflicts).length()
            val retained = unionConflicts(JSONArray(state.conflicts), merged.getJSONArray("conflicts"), state.resolved)
            state = state.copy(conflicts = retained.toString())
            withContext(Dispatchers.IO) { store.save(user, state) }
            val mergedRecords = merged.getJSONArray("records")
            lastMerged = mergedRecords
            try {
                projection.validateRows(liveProjected(mergedRecords))
            } catch (e: Exception) { fail(user, state, "retry", "The merged backup could not be applied safely. Your journal is unchanged."); return }
            if (merged.getBoolean("blocked")) {
                // Immutable rewrites never upload. The old rows stay; both versions await review.
                if (!applyMerged(user, state, mergedRecords, archive, assets)) return
                state = withContext(Dispatchers.IO) { store.load(user).copy(pending = true) }
                withContext(Dispatchers.IO) { store.save(user, state) }
                publishBind(busy = false); return
            }
            if (version == state.serverVersion && sameRecords(mergedRecords, JSONArray(state.base)) && retained.length() == previousConflicts && state.resolved.isEmpty()) {
                state = state.copy(lastSyncedAt = now(), outcome = "ok", message = null)
                withContext(Dispatchers.IO) { store.save(user, state) }
                publishBind(busy = false); return
            }
            if (!applyMerged(user, state, mergedRecords, archive, assets)) return
            state = withContext(Dispatchers.IO) { store.load(user) }
            val serverIds = (0 until remoteConflicts.length()).map { remoteConflicts.getJSONObject(it).getString("id") }.toSet()
            when (upload(user, state, version, mergedRecords, JSONArray(state.conflicts), merged.getJSONArray("conflicts"), state.resolved, serverIds, projectedKeys(projected))) {
                UploadResult.ACKED -> return
                UploadResult.FAILED -> return
                UploadResult.CONFLICT -> Unit
            }
            if (++attempts >= 3) { fail(user, withContext(Dispatchers.IO) { store.load(user) }, "retry", "Another device keeps changing the backup. Retry shortly."); return }
            // 409 with a moved server version: fetch, merge and retry with a fresh identity.
            val fresh = fetch(user, withContext(Dispatchers.IO) { store.load(user) }) ?: return
            if (fresh.first < version) { fail(user, state, "retry", "Private sync needs a retry. Your local journal is unchanged."); return }
            fetched = fresh
            state = withContext(Dispatchers.IO) { store.load(user) }
            // Rebuild from the current journal: the merged apply above changed it, so the
            // retry diffs and validates against the fresh build, not the stale one.
            archive = withContext(Dispatchers.IO) { library.archive() }
            assets = state.photos.mapValues { it.value.assetId }
            projected = try { projection.project(archive, assets) }
            catch (e: Exception) { fail(user, state, "retry", "Some saved information cannot be synced safely yet. Your journal is unchanged."); return }
            local = PrivateSyncProtocol.stampLocal(projected, lastMerged ?: JSONArray(state.base), device, state.maxCounter)
        }
    }

    private suspend fun fetch(user: String, state: PrivateSyncState): Pair<Long, JSONObject?>? {
        val reply = try {
            authorized(user) { token -> api.snapshot(token) }
        } catch (_: IOException) { fail(user, state, "waiting", "Waiting for connection. Your changes stay on this device."); return null }
        catch (e: PrivateSyncHttpError) { fail(user, state, "retry", e.message ?: "Private sync needs a retry."); return null }
        catch (_: AccountFailure) { bind(); publishBind(busy = false); return null }
        return try {
            PrivateSyncProtocol.parseSnapshotGet(reply)
        } catch (e: Exception) { fail(user, state, "retry", "The server backup could not be read safely. Your journal is unchanged."); null }
    }

    private suspend fun <T> authorized(user: String, block: suspend (String) -> T): T {
        check(session.userId() == user) { "Sign in with the account that started this sync." }
        return try {
            block(session.access(false))
        } catch (e: PrivateSyncHttpError) {
            if (!e.unauthorized) throw e
            val token = session.access(true)
            check(session.userId() == user) { "Sign in with the account that started this sync." }
            block(token)
        }
    }

    private suspend fun applyMerged(user: String, state: PrivateSyncState, merged: JSONArray, buildArchive: JSONObject, assets: Map<String, String>): Boolean {
        val base = JSONArray(state.base)
        val baseByKey = (0 until base.length()).associate { base.getJSONObject(it).let { row -> row.getString("recordType") + "|" + row.getString("recordId") to row } }
        val upserts = mutableListOf<PrivateSyncUpsert>(); val deletes = mutableListOf<PrivateSyncDelete>()
        for (i in 0 until merged.length()) {
            val row = merged.getJSONObject(i)
            val key = row.getString("recordType") + "|" + row.getString("recordId")
            val prior = baseByKey[key]
            if (prior != null && PrivateSyncMerge.stable(row) == PrivateSyncMerge.stable(prior)) continue
            if (row.getBoolean("deleted")) deletes += PrivateSyncDelete(row.getString("recordType"), row.getString("recordId"))
            else upserts += PrivateSyncUpsert(row.getString("recordType"), row.getString("recordId"), row.getJSONObject("payload"))
        }
        if (upserts.isEmpty() && deletes.isEmpty()) return true
        // A local write that landed mid-sync on an affected row aborts this attempt so the
        // next pass merges it properly; unrelated concurrent writes never block sync.
        val fresh = withContext(Dispatchers.IO) { library.archive() }
        for (key in (upserts.map { it.type + "|" + it.id } + deletes.map { it.type + "|" + it.id })) {
            val type = key.substringBefore("|"); val id = key.substringAfter("|")
            if (PrivateSyncMerge.stable(archiveRow(buildArchive, type, id)) != PrivateSyncMerge.stable(archiveRow(fresh, type, id))) {
                fail(user, state, "retry", "Your journal changed during sync. Retry to merge the latest edits."); return false
            }
        }
        val mutableAssets = assets.toMutableMap()
        var live = state
        for (row in upserts.filter { it.type in setOf("photos", "recipeAssets", "companionPhotos") }) {
            val key = row.type + "|" + row.id
            val assetId = row.payload.getString("assetId")
            if (live.photos[key]?.assetId == assetId && live.photos[key]?.complete == true) continue
            val bytes = downloadAsset(user, assetId)
            if (bytes == null) {
                fail(user, live, "waiting", "Waiting for connection. A photo could not be downloaded yet."); return false
            }
            mutableAssets[key] = assetId
            live = live.copy(photos = live.photos + (key to PrivateSyncPhotoEntry(assetId, RelayProtocol.hash(bytes), bytes.size, mimeOf(row.payload), (0 until partsFor(bytes.size)).toList(), true)))
        }
        withContext(Dispatchers.IO) { store.save(user, live) }
        val attachments = mutableMapOf<String, ByteArray>()
        // Re-read staged download bytes from the registry-verified helper instead of holding them all.
        for (row in upserts.filter { it.type in setOf("photos", "recipeAssets", "companionPhotos") }) {
            val key = row.type + "|" + row.id
            if (state.photos[key]?.assetId == row.payload.getString("assetId") && state.photos[key]?.complete == true) continue
            val bytes = downloadCache.remove(row.payload.getString("assetId"))
            if (bytes == null) { fail(user, live, "retry", "A photo could not be staged for this sync."); return false }
            attachments[row.id] = bytes
        }
        try {
            projection.project(validatedArchive(buildArchive, upserts, deletes), mutableAssets)
        } catch (e: Exception) { fail(user, live, "retry", "The merged backup could not be applied safely. Your journal is unchanged."); return false }
        return try {
            withContext(Dispatchers.IO) { library.apply(PrivateSyncApplyPlan(upserts, deletes, attachments)) }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail(user, live, "retry", "The merged backup could not be saved. Your journal is unchanged."); false }
    }

    private val downloadCache = mutableMapOf<String, ByteArray>()
    private suspend fun downloadAsset(user: String, assetId: String): ByteArray? {
        downloadCache[assetId]?.let { return it }
        val staging = withContext(Dispatchers.IO) { store.stagingDir() }
        val file = java.io.File(staging, assetId + ".download")
        return try {
            authorized(user) { token -> api.downloadPhoto(assetId, file, token) }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            require(bytes.size in 1..PrivateSyncProtocol.MAX_PHOTO_BYTES)
            downloadCache[assetId] = bytes; bytes
        } catch (_: IOException) { null } catch (_: PrivateSyncHttpError) { null } catch (_: IllegalArgumentException) { null }
        finally { withContext(Dispatchers.IO) { file.delete() } }
    }

    /** Mint stable asset identities for local photos and upload every incomplete one with
     * durable per-part state, so an interruption resumes instead of restarting. Commits
     * only ever advertise complete objects. */
    private suspend fun ensureRegistry(user: String, state: PrivateSyncState, archive: JSONObject): PrivateSyncState {
        var live = state
        for (type in setOf("photos", "recipeAssets", "companionPhotos")) {
            val rows = archive.optJSONArray(type) ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val id = row.getString("id"); val key = "$type|$id"
                val source = withContext(Dispatchers.IO) { library.photoBytes(type, id) } ?: error("A photo is unavailable on this device. Your journal is unchanged.")
                require(source.bytes.size in 1..PrivateSyncProtocol.MAX_PHOTO_BYTES) { "A photo exceeds the 16 MB sync limit." }
                require(source.mime in setOf("image/jpeg", "image/png", "image/webp")) { "A photo uses an unsupported format." }
                val sha = RelayProtocol.hash(source.bytes)
                val prior = live.photos[key]
                if (prior != null && prior.sha256 == sha && prior.bytes == source.bytes.size && prior.mime == source.mime) continue
                prior?.let { runCatching { authorized(user) { token -> api.abandonPhoto(it.assetId, token) } } }
                live = live.copy(photos = live.photos + (key to PrivateSyncPhotoEntry(UUID.randomUUID().toString(), sha, source.bytes.size, source.mime, emptyList(), false)))
            }
        }
        withContext(Dispatchers.IO) { store.save(user, live) }
        for ((key, entry) in live.photos.toSortedMap()) {
            if (entry.complete) continue
            // Registry entries for remotely downloaded photos are already complete.
            val type = key.substringBefore("|"); val id = key.substringAfter("|")
            val local = withContext(Dispatchers.IO) { library.photoBytes(type, id) }
            if (local == null || RelayProtocol.hash(local.bytes) != entry.sha256) continue
            live = uploadPhoto(user, live, key, entry, local.bytes)
        }
        return live
    }

    private suspend fun uploadPhoto(user: String, state: PrivateSyncState, key: String, entry: PrivateSyncPhotoEntry, bytes: ByteArray): PrivateSyncState {
        var live = state
        val received = entry.received.toMutableSet()
        val status = try {
            authorized(user) { token -> api.photoStatus(entry.assetId, token) }
        } catch (e: PrivateSyncHttpError) {
            if (e.status != 404) throw e
            try {
                authorized(user) { token -> api.beginPhoto(entry.assetId, entry.bytes.toLong(), entry.mime, entry.sha256, token) }
            } catch (retry: PrivateSyncHttpError) {
                if (!retry.conflict) throw retry
                // The server holds different bytes under this identity: mint a fresh one.
                val fresh = entry.copy(assetId = UUID.randomUUID().toString(), received = emptyList())
                live = live.copy(photos = live.photos + (key to fresh))
                withContext(Dispatchers.IO) { store.save(user, live) }
                return uploadPhoto(user, live, key, fresh, bytes)
            }
        }
        if (status.optBoolean("complete", false)) {
            live = live.copy(photos = live.photos + (key to entry.copy(received = (0 until partsFor(entry.bytes)).toList(), complete = true)))
            withContext(Dispatchers.IO) { store.save(user, live) }
            return live
        }
        (0 until status.getJSONArray("receivedParts").length()).map { status.getJSONArray("receivedParts").getInt(it) }.forEach { received += it }
        val count = partsFor(entry.bytes)
        for (index in 0 until count) {
            if (index in received) continue
            val chunk = bytes.copyOfRange(index * PrivateSyncProtocol.CHUNK_BYTES, minOf(bytes.size, (index + 1) * PrivateSyncProtocol.CHUNK_BYTES))
            try {
                authorized(user) { token -> api.putPart(entry.assetId, index, chunk, token) }
            } catch (e: PrivateSyncHttpError) {
                if (!e.conflict) throw e
                // A replaced or expired part restarts that part with the server's current state.
                val current = authorized(user) { token -> api.photoStatus(entry.assetId, token) }
                (0 until current.getJSONArray("receivedParts").length()).map { current.getJSONArray("receivedParts").getInt(it) }.forEach { received += it }
                if (index in received) continue
                throw e
            }
            received += index
            live = live.copy(photos = live.photos + (key to entry.copy(received = received.sorted())))
            withContext(Dispatchers.IO) { store.save(user, live) }
        }
        authorized(user) { token -> api.completePhoto(entry.assetId, token) }
        live = live.copy(photos = live.photos + (key to entry.copy(received = (0 until count).toList(), complete = true)))
        withContext(Dispatchers.IO) { store.save(user, live) }
        return live
    }

    private enum class UploadResult { ACKED, FAILED, CONFLICT }
    private suspend fun upload(user: String, state: PrivateSyncState, baseVersion: Long, records: JSONArray, retained: JSONArray, fresh: JSONArray, resolved: List<String>, serverIds: Set<String>, projectedKeys: Set<String>): UploadResult {
        var live = withContext(Dispatchers.IO) { store.load(user) }
        val device = withContext(Dispatchers.IO) { store.deviceId() }
        val candidateId = live.mutationId ?: UUID.randomUUID().toString()
        val candidate = try {
            PrivateSyncProtocol.putBody(baseVersion, candidateId, device, records, retained, fresh, resolved, serverIds)
        } catch (e: Exception) { fail(user, live, "retry", "This library could not be uploaded safely. Your journal is unchanged."); return UploadResult.FAILED }
        val stored = live.mutationId?.let { id -> runCatching { withContext(Dispatchers.IO) { store.readBody(user, id) } }.getOrNull() }
        val storedJson = stored?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        // An ambiguous outcome retries the persisted upload with the same identity and the
        // same bytes. The journal content is compared without version stamps: the logical
        // counter may have advanced since the body was persisted, but restamped versions of
        // unchanged content must not rotate the idempotency identity.
        val contentUnchanged = stored != null && storedJson != null &&
            storedJson.optLong("baseVersion", -1) == baseVersion &&
            sameRecordContent(records, storedJson.optJSONArray("records") ?: JSONArray()) &&
            PrivateSyncMerge.stable(candidate.optJSONArray("conflicts")) == PrivateSyncMerge.stable(storedJson.optJSONArray("conflicts")) &&
            PrivateSyncMerge.stable(candidate.optJSONArray("resolvedConflictIds")) == PrivateSyncMerge.stable(storedJson.optJSONArray("resolvedConflictIds"))
        if (contentUnchanged) {
            // Exact retry of the persisted upload: same identity, same bytes. The base
            // tracks exactly what the server acknowledged: the persisted records.
            val storedRecords = storedJson!!.optJSONArray("records") ?: JSONArray()
            live = live.copy(pending = true)
            withContext(Dispatchers.IO) { store.save(user, live) }
            return commit(user, live, stored!!, live.mutationId!!, baseVersion, storedRecords, retained, fresh, resolved, serverIds, projectedKeys)
        }
        // Changed content always rotates to a fresh idempotency identity; the server
        // rejects a reused identity with different bytes.
        val mutation = if (candidateId == live.mutationId) UUID.randomUUID().toString() else candidateId
        live.mutationId?.let { old -> if (old != mutation) withContext(Dispatchers.IO) { store.bodyFile(user, old).delete() } }
        val rebuilt = PrivateSyncProtocol.putBody(baseVersion, mutation, device, records, retained, fresh, resolved, serverIds)
        // The receipt's sha256 covers the server's canonical
        // re-serialization of the envelope, so the body itself is built
        // and sent in canonical form: hash(sent bytes) == receipt by
        // construction (issue #262).
        val rebuiltBytes = PrivateSyncMerge.stable(rebuilt).toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) { store.writeBody(user, mutation, rebuiltBytes) }
        live = live.copy(mutationId = mutation, mutationSha = RelayProtocol.hash(rebuiltBytes), pending = true)
        withContext(Dispatchers.IO) { store.save(user, live) }
        return commit(user, live, rebuiltBytes, mutation, baseVersion, records, retained, fresh, resolved, serverIds, projectedKeys)
    }

    private suspend fun commit(user: String, state: PrivateSyncState, bytes: ByteArray, mutation: String, baseVersion: Long, records: JSONArray, retained: JSONArray, fresh: JSONArray, resolved: List<String>, serverIds: Set<String>, projectedKeys: Set<String>): UploadResult {
        val receipt = try {
            authorized(user) { token -> api.commit(bytes, mutation, token) }
        } catch (_: IOException) { fail(user, state, "waiting", "Waiting for connection. The exact upload will retry unchanged."); return UploadResult.FAILED }
        catch (e: PrivateSyncHttpError) {
            if (e.conflict) return UploadResult.CONFLICT // Caller refetches, merges and retries with a fresh identity.
            if (e.status == 422 && e.code == "private_assets_unavailable") {
                // A referenced photo is missing server-side: re-verify every advertised asset.
                val reset = state.photos.mapValues { it.value.copy(complete = false, received = emptyList()) }
                withContext(Dispatchers.IO) { store.save(user, state.copy(photos = reset)) }
            }
            fail(user, state, "retry", e.message ?: "Private sync needs a retry."); return UploadResult.FAILED
        }
        catch (_: AccountFailure) { bind(); publishBind(busy = false); return UploadResult.FAILED }
        try {
            require(receipt.getString("mutationId") == mutation && receipt.getString("sha256") == RelayProtocol.hash(bytes) && receipt.getLong("version") == baseVersion + 1)
        } catch (e: Exception) { fail(user, state, "retry", "The server receipt did not match this upload. Nothing was acknowledged."); return UploadResult.FAILED }
        val merged = linkedMapOf<String, JSONObject>()
        for (source in listOf(retained, fresh)) for (i in 0 until source.length()) merged[source.getJSONObject(i).getString("id")] = source.getJSONObject(i)
        val sentResolved = resolved.filter { it in serverIds }.toSet()
        for (id in resolved) merged.remove(id)
        val baseKeys = (0 until records.length()).map { records.getJSONObject(it).let { row -> row.getString("recordType") + "|" + row.getString("recordId") } }.toSet()
        val conflictKeys = merged.values.flatMap { conflict -> listOf(conflict.getJSONObject("winner"), conflict.getJSONObject("loser")).map { row -> row.getString("recordType") + "|" + row.getString("recordId") } }.toSet()
        val pruned = state.photos.filterKeys { it in projectedKeys || it in baseKeys || it in conflictKeys }
        // Abandon pruned partial uploads best-effort; completed shared assets stay.
        for ((key, entry) in state.photos.filterKeys { it !in pruned }) if (!entry.complete) runCatching { authorized(user) { token -> api.abandonPhoto(entry.assetId, token) } }
        val next = state.copy(serverVersion = receipt.getLong("version"), base = JSONArray(records.toString()).toString(),
            conflicts = JSONArray(merged.values.sortedBy { it.getString("id") }).toString(), resolved = resolved.filter { it !in sentResolved },
            pending = false, mutationId = null, mutationSha = null, photos = pruned, lastSyncedAt = now(), outcome = "ok", message = null)
        withContext(Dispatchers.IO) {
            store.save(user, next)
            store.bodyFile(user, mutation).delete()
        }
        publishBind(busy = false)
        return UploadResult.ACKED
    }

    private suspend fun fail(user: String, state: PrivateSyncState, outcome: String, message: String) {
        withContext(Dispatchers.IO) { store.save(user, state.copy(outcome = outcome, message = message.take(500), pending = true)) }
        publishBind(busy = false)
    }

    private suspend fun publishBind(busy: Boolean) {
        val user = boundUser
        if (!enabled || user == null) {
            _presentation.value = PrivateSyncPresentation(busy = busy)
            return
        }
        val state = withContext(Dispatchers.IO) { runCatching { store.load(user) }.getOrDefault(PrivateSyncState(account = user)) }
        val device = withContext(Dispatchers.IO) { runCatching { store.deviceId() }.getOrNull() }
        val conflicts = try {
            val retained = JSONArray(state.conflicts)
            (0 until retained.length()).map { PrivateSyncProtocol.conflictView(retained.getJSONObject(it), device ?: "") }
        } catch (_: Exception) { emptyList() }
        val status = when {
            conflicts.isNotEmpty() -> "Conflicts to review"
            state.outcome == "waiting" -> "Waiting for connection"
            state.outcome == "retry" -> "Retry needed"
            else -> "Last synced " + if (state.lastSyncedAt <= 0) "never" else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(state.lastSyncedAt).atZone(ZoneId.systemDefault()))
        }
        _presentation.value = PrivateSyncPresentation(status, state.message, state.lastSyncedAt, conflicts, busy, true, state.automatic && withContext(Dispatchers.IO) { store.libraryAccount() } == user)
    }

    private fun liveProjected(records: JSONArray): JSONArray {
        val rows = JSONArray()
        for (i in 0 until records.length()) {
            val row = records.getJSONObject(i)
            if (row.getBoolean("deleted")) continue
            rows.put(JSONObject().put("recordType", row.getString("recordType")).put("recordId", row.getString("recordId")).put("payload", row.getJSONObject("payload")).put("assetIds", row.getJSONArray("assetIds")))
        }
        return rows
    }

    private fun sameRecords(a: JSONArray, b: JSONArray): Boolean {
        if (a.length() != b.length()) return false
        val left = (0 until a.length()).map { PrivateSyncMerge.stable(a.getJSONObject(it)) }.sorted()
        val right = (0 until b.length()).map { PrivateSyncMerge.stable(b.getJSONObject(it)) }.sorted()
        return left == right
    }

    /** Record content without version stamps, for retry comparisons. Restamping unchanged
     * rows with an advanced logical counter must not count as a content change. */
    private fun sameRecordContent(a: JSONArray, b: JSONArray): Boolean {
        if (a.length() != b.length()) return false
        fun keyed(records: JSONArray): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (i in 0 until records.length()) {
                val row = records.getJSONObject(i)
                val copy = JSONObject(row.toString())
                copy.remove("version")
                map[row.getString("recordType") + "|" + row.getString("recordId")] = PrivateSyncMerge.stable(copy)
            }
            return map
        }
        return keyed(a) == keyed(b)
    }

    private fun unionConflicts(retained: JSONArray, fresh: JSONArray, resolved: List<String>): JSONArray {
        val merged = linkedMapOf<String, JSONObject>()
        for (source in listOf(retained, fresh)) for (i in 0 until source.length()) merged[source.getJSONObject(i).getString("id")] = source.getJSONObject(i)
        for (id in resolved) merged.remove(id)
        return JSONArray(merged.values.sortedBy { it.getString("id") })
    }

    private fun restampAll(projected: JSONArray, device: String, counter: Long): JSONArray {
        val rows = JSONArray()
        for (i in 0 until projected.length()) {
            val row = projected.getJSONObject(i)
            rows.put(JSONObject().put("recordType", row.getString("recordType")).put("recordId", row.getString("recordId"))
                .put("version", JSONObject().put("counter", counter).put("clientId", device)).put("deleted", false)
                .put("payload", row.getJSONObject("payload")).put("assetIds", row.getJSONArray("assetIds")))
        }
        val sorted = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.getString("recordType") + "|" + it.getString("recordId") }
        val result = JSONArray(sorted)
        PrivateSyncMerge.validateRecords(result)
        return result
    }

    private suspend fun advancedAfterStamp(user: String, state: PrivateSyncState, stamped: JSONArray): PrivateSyncState {
        var observed = 0L
        for (i in 0 until stamped.length()) observed = maxOf(observed, stamped.getJSONObject(i).getJSONObject("version").getLong("counter"))
        if (observed < state.maxCounter) return state
        val advanced = state.copy(maxCounter = observed + 1)
        withContext(Dispatchers.IO) { store.save(user, advanced) }
        return advanced
    }

    private fun projectedKeys(projected: JSONArray) = (0 until projected.length()).map { projected.getJSONObject(it).let { row -> row.getString("recordType") + "|" + row.getString("recordId") } }.toSet()

    private fun archiveRow(archive: JSONObject, type: String, id: String): Any? {
        val rows = archive.optJSONArray(type) ?: return null
        val key = when (type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString(key, "") == id) return rows.getJSONObject(i)
        return null
    }

    private fun validatedArchive(archive: JSONObject, upserts: List<PrivateSyncUpsert>, deletes: List<PrivateSyncDelete>): JSONObject {
        val copy = JSONObject(archive.toString())
        for (delete in deletes) {
            val key = when (delete.type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
            val rows = copy.optJSONArray(delete.type) ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString(key, "") != delete.id) kept.put(rows.getJSONObject(i))
            copy.put(delete.type, kept)
        }
        for (upsert in upserts) {
            val key = when (upsert.type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
            val rows = copy.optJSONArray(upsert.type) ?: JSONArray()
            val next = JSONArray()
            var replaced = false
            for (i in 0 until rows.length()) {
                if (rows.getJSONObject(i).optString(key, "") == upsert.id) {
                    // Overlay portable fields over the local row, preserving device-only keys.
                    val merged = JSONObject(rows.getJSONObject(i).toString())
                    for (field in upsert.payload.keys()) merged.put(field, upsert.payload.get(field))
                    next.put(merged); replaced = true
                } else next.put(rows.getJSONObject(i))
            }
            if (!replaced) next.put(JSONObject(upsert.payload.toString()))
            copy.put(upsert.type, next)
        }
        return copy
    }

}
