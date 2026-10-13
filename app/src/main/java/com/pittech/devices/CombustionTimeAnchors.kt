package com.pittech.devices

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An estimate tied to a received live status, not a probe-provided wall clock. */
internal data class CombustionTimeAnchor(val session: Long, val sequence: Long, val timeMillis: Long, val periodMillis: Long) {
    fun timeFor(recordSequence: Long): Long? {
        if (recordSequence !in 0..0xffff_ffffL || periodMillis !in 1_000L..60_000L) return null
        return runCatching { Math.addExact(timeMillis, Math.multiplyExact(recordSequence - sequence, periodMillis)) }
            .getOrNull()?.takeIf { it > 0L }
    }
}
internal interface CombustionAnchorStore {
    fun get(key: String): CombustionTimeAnchor?
    fun save(key: String, value: CombustionTimeAnchor)
}
internal class MemoryCombustionAnchorStore : CombustionAnchorStore {
    private val entries = linkedMapOf<String, CombustionTimeAnchor>()
    @Synchronized override fun get(key: String) = entries[key]
    @Synchronized override fun save(key: String, value: CombustionTimeAnchor) { entries[key] = value }
}

/** Shared per process and no-backup on disk; contains only hashes and timing metadata. */
internal class FileCombustionAnchorStore(context: Context) : CombustionAnchorStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "combustion-time-anchors.json"))
    override fun get(key: String): CombustionTimeAnchor? = synchronized(lock) { read()[key] }
    override fun save(key: String, value: CombustionTimeAnchor) = synchronized(lock) {
        val entries = read().apply { put(key, value) }
        val array = JSONArray()
        entries.entries.sortedByDescending { it.value.timeMillis }.take(200).forEach { (id, anchor) ->
            array.put(JSONObject().put("key", id).put("session", anchor.session).put("sequence", anchor.sequence)
                .put("time", anchor.timeMillis).put("period", anchor.periodMillis))
        }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun read(): LinkedHashMap<String, CombustionTimeAnchor> {
        val entries = linkedMapOf<String, CombustionTimeAnchor>()
        if (!file.baseFile.exists()) return entries
        try {
            require(file.baseFile.length() <= 100_000L)
            val array = JSONArray(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            require(array.length() <= 200)
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val key = row.getString("key")
                val value = CombustionTimeAnchor(row.getLong("session"), row.getLong("sequence"), row.getLong("time"), row.getLong("period"))
                require(key.matches(Regex("[a-f0-9]{64}")) && value.session in 0..0xffff_ffffL &&
                    value.sequence in 0..0xffff_ffffL && value.timeFor(value.sequence) != null)
                entries[key] = value
            }
        } catch (_: Exception) { file.delete(); entries.clear() }
        return entries
    }
    private companion object { val lock = Any() }
}
