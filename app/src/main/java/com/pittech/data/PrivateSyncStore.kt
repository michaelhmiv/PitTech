package com.pittech.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Durable per-account private-sync journal. Device-only: never exported, never synced,
 * never attached to another account. Holds the last acknowledged base snapshot plus the
 * server version, per-record mutation metadata, retained conflict copies with their
 * resolution IDs, resumable photo part state and the last honest sync status.
 * Sensitivity matches the on-device Room journal, which this mirrors. */
internal data class PrivateSyncPhotoEntry(val assetId: String, val sha256: String, val bytes: Int, val mime: String, val received: List<Int>, val complete: Boolean) {
    fun validate() {
        require(publicationUuid(assetId) && sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 1..16 * 1024 * 1024)
        require(mime in setOf("image/jpeg", "image/png", "image/webp"))
        require(received.distinct().size == received.size && received.all { it in 0 until 16 })
        require(!complete || received.size == (bytes + 1024 * 1024 - 1) / (1024 * 1024))
    }
    fun json() = JSONObject().put("assetId", assetId).put("sha256", sha256).put("bytes", bytes).put("mime", mime).put("received", JSONArray(received)).put("complete", complete)
    companion object {
        fun parse(o: JSONObject) = PrivateSyncPhotoEntry(o.getString("assetId"), o.getString("sha256"), o.getInt("bytes"), o.getString("mime"), (0 until o.getJSONArray("received").length()).map { o.getJSONArray("received").getInt(it) }, o.getBoolean("complete")).also { it.validate() }
    }
}

internal data class PrivateSyncState(
    val account: String = "",
    val serverVersion: Long = 0,
    val maxCounter: Long = 1,
    val base: String = "[]",
    val conflicts: String = "[]",
    val resolved: List<String> = emptyList(),
    val pending: Boolean = false,
    val mutationId: String? = null,
    val mutationSha: String? = null,
    val photos: Map<String, PrivateSyncPhotoEntry> = emptyMap(),
    val lastSyncedAt: Long = 0,
    val outcome: String = "ok",
    val message: String? = null,
    val automatic: Boolean = false,
) {
    fun json() = JSONObject().put("version", 1).put("account", account).put("serverVersion", serverVersion).put("maxCounter", maxCounter)
        .put("base", JSONArray(base)).put("conflicts", JSONArray(conflicts)).put("resolved", JSONArray(resolved))
        .put("pending", pending).put("mutationId", mutationId ?: JSONObject.NULL).put("mutationSha", mutationSha ?: JSONObject.NULL)
        .put("photos", JSONObject().apply { photos.forEach { (key, entry) -> put(key, entry.json()) } })
        .put("lastSyncedAt", lastSyncedAt).put("outcome", outcome).put("message", message ?: JSONObject.NULL).put("automatic", automatic)
    companion object {
        fun parse(user: String, o: JSONObject): PrivateSyncState {
            require(o.getInt("version") == 1 && o.getString("account") == user)
            val serverVersion = o.getLong("serverVersion"); val maxCounter = o.getLong("maxCounter")
            require(serverVersion in 0..9007199254740990L && maxCounter in 1..9007199254740990L)
            val base = o.getJSONArray("base"); val conflicts = o.getJSONArray("conflicts")
            require(base.length() <= 100000 && conflicts.length() <= 10000)
            PrivateSyncMerge.validateRecords(base); PrivateSyncMerge.validateConflicts(conflicts)
            val resolved = (0 until o.getJSONArray("resolved").length()).map { o.getJSONArray("resolved").getString(it) }
            require(resolved.distinct().size == resolved.size && resolved.all { it.matches(Regex("[a-f0-9]{64}")) })
            val mutationId = if (o.isNull("mutationId")) null else o.getString("mutationId")
            val mutationSha = if (o.isNull("mutationSha")) null else o.getString("mutationSha")
            require((mutationId == null) == (mutationSha == null))
            mutationId?.let { require(publicationUuid(it) && mutationSha!!.matches(Regex("[a-f0-9]{64}"))) }
            val photos = o.getJSONObject("photos"); val entries = mutableMapOf<String, PrivateSyncPhotoEntry>()
            require(photos.length() <= 100000)
            for (key in photos.keys()) {
                require(key.matches(Regex("(photos|recipeAssets|companionPhotos)\\|[A-Za-z0-9:_-]{1,200}")))
                entries[key] = PrivateSyncPhotoEntry.parse(photos.getJSONObject(key))
            }
            val outcome = o.getString("outcome"); require(outcome in setOf("ok", "waiting", "retry"))
            return PrivateSyncState(user, serverVersion, maxCounter, base.toString(), conflicts.toString(), resolved,
                o.getBoolean("pending"), mutationId, mutationSha, entries, o.getLong("lastSyncedAt"), outcome,
                if (o.isNull("message")) null else o.getString("message").take(500), o.optBoolean("automatic", false))
        }
    }
}

internal interface PrivateSyncStorage {
    fun deviceId(): String
    fun libraryAccount(): String?
    fun attachLibrary(user: String)
    fun load(user: String): PrivateSyncState
    fun save(user: String, state: PrivateSyncState)
    fun purgeAccount(user: String)
    fun bodyFile(user: String, mutationId: String): File
    fun writeBody(user: String, mutationId: String, bytes: ByteArray)
    fun readBody(user: String, mutationId: String): ByteArray
    fun stagingDir(): File
}

/** In-memory storage for JVM unit tests. Same contract, no filesystem. */
internal class PrivateSyncMemoryStore : PrivateSyncStorage {
    private val states = mutableMapOf<String, PrivateSyncState>()
    private val bodies = mutableMapOf<String, ByteArray>()
    private var device: String? = null
    private var libraryOwner: String? = null
    @Synchronized override fun libraryAccount(): String? = libraryOwner
    @Synchronized override fun attachLibrary(user: String) { require(publicationUuid(user)); libraryOwner = user }
    private val staging by lazy { java.nio.file.Files.createTempDirectory("private-sync-staging-").toFile() }
    @Synchronized override fun deviceId(): String = device ?: UUID.randomUUID().toString().also { device = it }
    @Synchronized override fun load(user: String): PrivateSyncState = states[user] ?: PrivateSyncState(account = user)
    @Synchronized override fun save(user: String, state: PrivateSyncState) {
        require(state.account == user)
        states[user] = PrivateSyncState.parse(user, state.json())
    }
    @Synchronized override fun purgeAccount(user: String) {
        if (libraryOwner == user) libraryOwner = null
        states.remove(user)
        bodies.keys.filter { it.startsWith("$user|") }.forEach { bodies.remove(it) }
        staging.listFiles().orEmpty().filter { it.name.startsWith("$user.") && it.extension == "body" }.forEach { it.delete() }
    }
    override fun bodyFile(user: String, mutationId: String): File {
        require(publicationUuid(user) && publicationUuid(mutationId))
        return File(staging, "$user.$mutationId.body")
    }
    @Synchronized override fun writeBody(user: String, mutationId: String, bytes: ByteArray) {
        require(publicationUuid(user) && publicationUuid(mutationId) && bytes.isNotEmpty())
        bodies["$user|$mutationId"] = bytes.copyOf()
        bodyFile(user, mutationId).writeBytes(bytes)
    }
    @Synchronized override fun readBody(user: String, mutationId: String): ByteArray {
        // The file is the source of truth, mirroring the durable store: a cancelled
        // upload deletes the file, so its bytes can never replay.
        if (!bodyFile(user, mutationId).isFile) error("Missing sync upload bytes.")
        return bodies["$user|$mutationId"]?.copyOf() ?: error("Missing sync upload bytes.")
    }
    override fun stagingDir(): File = staging
}

internal class PrivateSyncStore(val directory: File) : PrivateSyncStorage {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "private-sync-v1"))
    companion object {
        private val lock = Any()
        private const val STATE_LIMIT = 72 * 1024 * 1024
        private const val BODY_LIMIT = 32 * 1024 * 1024 + 1024
    }
    private fun stateFile(user: String): File { require(publicationUuid(user)); return File(directory, RelayProtocol.hash(user.toByteArray()) + ".json") }
    override fun libraryAccount(): String? = synchronized(lock) {
        val file = File(directory, "library-account.json")
        if (!file.exists()) null else JSONObject(String(bounded(AtomicFile(file).openRead(), 1024), Charsets.UTF_8)).getString("account").also { require(publicationUuid(it)) }
    }
    override fun attachLibrary(user: String) = synchronized(lock) {
        require(publicationUuid(user)); write(File(directory, "library-account.json"), JSONObject().put("account", user).toString().toByteArray())
    }
    override fun deviceId(): String = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "device.json")
        if (file.exists()) {
            val id = JSONObject(bounded(AtomicFile(file).openRead()).toString(Charsets.UTF_8)).getString("deviceId")
            require(publicationUuid(id) && id == id.lowercase()); return id
        }
        val id = UUID.randomUUID().toString()
        write(file, JSONObject().put("version", 1).put("deviceId", id).toString().toByteArray())
        return id
    }
    private fun bounded(input: InputStream, limit: Int = STATE_LIMIT): ByteArray = input.use {
        val result = ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) {
            val count = it.read(buffer, 0, minOf(buffer.size, limit + 1 - result.size())); if (count < 0) break
            result.write(buffer, 0, count); require(result.size() <= limit) { "Private sync state exceeds its supported size." }
        }
        result.toByteArray()
    }
    private fun write(file: File, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (e: Throwable) { atomic.failWrite(stream); throw e }
        atomic.openRead().close()
        check(file.length() == bytes.size.toLong() && RelayProtocol.hash(file) == RelayProtocol.hash(bytes)) { "Private sync state could not be committed." }
    }
    override fun load(user: String): PrivateSyncState = synchronized(lock) {
        val file = stateFile(user)
        if (!file.exists() && !File(file.path + ".bak").exists()) return PrivateSyncState(account = user)
        return PrivateSyncState.parse(user, JSONObject(bounded(AtomicFile(file).openRead()).toString(Charsets.UTF_8)))
    }
    override fun save(user: String, state: PrivateSyncState) = synchronized(lock) {
        require(state.account == user)
        val bytes = state.json().toString().toByteArray()
        require(bytes.size <= STATE_LIMIT) { "Private sync state exceeds its supported size." }
        write(stateFile(user), bytes)
    }
    /** Remove the body's exact retry bytes first; the durable reference survives an interruption. */
    override fun purgeAccount(user: String) = synchronized(lock) {
        if (libraryAccount() == user) AtomicFile(File(directory, "library-account.json")).delete()
        val pending = runCatching { load(user) }.getOrNull()
        pending?.mutationId?.let { bodyFile(user, it).delete() }
        directory.listFiles().orEmpty().filter { it.name.startsWith(RelayProtocol.hash(user.toByteArray()) + ".") && it.extension == "body" }.forEach { it.delete() }
        AtomicFile(stateFile(user)).delete()
        val file = stateFile(user)
        check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
    }
    override fun bodyFile(user: String, mutationId: String): File {
        require(publicationUuid(user) && publicationUuid(mutationId))
        return File(directory, RelayProtocol.hash(user.toByteArray()) + "." + mutationId + ".body")
    }
    override fun writeBody(user: String, mutationId: String, bytes: ByteArray) = synchronized(lock) {
        require(bytes.size in 1..BODY_LIMIT)
        write(bodyFile(user, mutationId), bytes)
    }
    override fun readBody(user: String, mutationId: String): ByteArray = synchronized(lock) {
        val file = bodyFile(user, mutationId)
        require(file.isFile); return bounded(file.inputStream(), BODY_LIMIT)
    }
    override fun stagingDir(): File = synchronized(lock) {
        File(directory, "staging").apply { check(isDirectory || mkdirs()) }
    }
}
