package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SharingEntry(
    val id: String = UUID.randomUUID().toString(),
    val sourceDishId: String? = null,
    val sourceRevisionId: String? = null,
    val snapshot: RecipeSnapshot,
    val storyJson: String,
    val included: Boolean = true,
    val includeStory: Boolean = false,
    val photoIds: List<String> = emptyList(),
)

/** An independently owned composition. Editing instructions never edits its frozen measured story. */
data class SharingDocument(
    val title: String,
    val entries: List<SharingEntry>,
    val sourceCookId: String? = null,
    val identityMode: String = "anonymous",
    val heroPhotoId: String? = null,
    val collectionId: String? = null,
    val parentRevisionId: String? = null,
    val sourceHistoryJson: String? = null,
)

object SharingDraftCodec {
    const val DRAFT = "sharing_draft"
    const val COLLECTION = "collection_revision"
    fun sameStory(a: String, b: String): Boolean = equivalent(JSONObject(a), JSONObject(b))
    private fun equivalent(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val keys = a.keys().asSequence().toSet()
            keys == b.keys().asSequence().toSet() && keys.all { equivalent(a.opt(it), b.opt(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equivalent(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
    fun encode(value: SharingDocument): String {
        validate(value)
        return JSONObject().put("version", 1).put("title", value.title).put("identityMode", value.identityMode)
            .put("sourceCookId", value.sourceCookId ?: JSONObject.NULL).put("heroPhotoId", value.heroPhotoId ?: JSONObject.NULL)
            .put("collectionId", value.collectionId ?: JSONObject.NULL).put("parentRevisionId", value.parentRevisionId ?: JSONObject.NULL)
            .put("sourceHistory", value.sourceHistoryJson?.let(::JSONObject) ?: JSONObject.NULL)
            .put("entries", JSONArray(value.entries.map { entry -> JSONObject().put("id", entry.id)
                .put("sourceDishId", entry.sourceDishId ?: JSONObject.NULL).put("sourceRevisionId", entry.sourceRevisionId ?: JSONObject.NULL)
                .put("snapshotJson", RecipeSnapshotCodec.encode(entry.snapshot)).put("story", JSONObject(entry.storyJson))
                .put("included", entry.included).put("includeStory", entry.includeStory).put("photoIds", JSONArray(entry.photoIds)) })).toString()
    }
    fun decode(raw: String): SharingDocument {
        val o = JSONObject(raw)
        require(o.getInt("version") == 1) { "Unsupported sharing draft." }
        val rows = o.getJSONArray("entries")
        fun optional(key: String) = if (o.isNull(key)) null else o.getString(key)
        val result = SharingDocument(o.getString("title"), (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val photos = row.getJSONArray("photoIds")
            SharingEntry(row.getString("id"), if (row.isNull("sourceDishId")) null else row.getString("sourceDishId"),
                if (row.isNull("sourceRevisionId")) null else row.getString("sourceRevisionId"), RecipeSnapshotCodec.decode(row.getString("snapshotJson")),
                row.getJSONObject("story").toString(), row.getBoolean("included"), row.getBoolean("includeStory"), (0 until photos.length()).map { photos.getString(it) })
        }, optional("sourceCookId"), o.getString("identityMode"), optional("heroPhotoId"), optional("collectionId"), optional("parentRevisionId"), o.optJSONObject("sourceHistory")?.toString())
        validate(result); return result
    }
    fun validate(value: SharingDocument) {
        require(value.title.isNotBlank() && value.title.length <= 160 && value.entries.size in 1..20 && value.identityMode in setOf("named", "anonymous")) { "Choose a title and one to twenty dishes." }
        require(value.entries.map { it.id }.distinct().size == value.entries.size) { "Duplicate sharing dish identities." }
        value.entries.forEach { e ->
            require(e.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && e.photoIds.size <= 100 && e.photoIds.distinct().size == e.photoIds.size) { "Invalid sharing dish." }
            RecipeSnapshotCodec.encode(e.snapshot)
            val story = JSONObject(e.storyJson)
            require(story.getInt("version") == 1 && (story.isNull("attemptId") || story.getString("attemptId").isNotBlank())) { "Invalid recorded cook story." }
            require(story.getJSONArray("readings").length() <= 250000) { "Too many source measurements in one dish." }
            require(!story.isNull("attemptId") || story.getJSONArray("readings").length() == 0) { "Measurements require an actual source attempt." }
            val samples = story.getJSONArray("readings")
            story.optJSONArray("connectionGaps")?.let { gaps -> require(gaps.length() <= 10000 && (0 until gaps.length()).all { gaps.getDouble(it).isFinite() && gaps.getDouble(it) > 0 }) { "Invalid recorded measurement gaps." } }
            for (i in 0 until samples.length()) {
                val sample = samples.getJSONObject(i)
                require(sample.getString("id").isNotBlank() && sample.getString("id").length <= 128 && sample.getString("measurementType").isNotBlank()) { "Invalid source sample identity or role." }
                require(sample.getDouble("value").isFinite() && sample.getString("unit") in setOf("°F", "°C") && sample.getLong("measuredAtUtcMillis") > 0) { "Invalid source measurement." }
                require(sample.getString("source") in setOf("manual", "recorded") && sample.getString("probeName").length <= 200) { "Invalid source channel." }
                require(!sample.has("sourceDeviceId") && !sample.has("deviceId") && !sample.has("credentials")) { "Private device fields cannot enter a cook story." }
            }
        }
        require(value.heroPhotoId == null || value.entries.filter { it.included }.any { value.heroPhotoId in it.photoIds }) { "Choose a hero photo included in this draft." }
    }
}
