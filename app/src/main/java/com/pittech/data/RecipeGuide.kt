package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.*
import org.json.JSONArray
import org.json.JSONObject

data class GuidePresentation(val visible: Boolean = false, val dishIds: Set<String>? = null, val channels: Map<String, String> = emptyMap()) {
    fun encode() = JSONObject().put("version", 1).put("visible", visible)
        .put("dishIds", dishIds?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL).put("channels", JSONObject(channels)).toString()
    companion object {
        fun decode(raw: String): GuidePresentation {
            val o = JSONObject(raw); require(o.getInt("version") == 1)
            val ids = o.optJSONArray("dishIds")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet().also { require(it.size == a.length() && it.size <= 30) } }
            val mappings = o.optJSONObject("channels") ?: JSONObject()
            require(mappings.length() <= 200)
            return GuidePresentation(o.getBoolean("visible"), ids, mappings.keys().asSequence().associateWith { mappings.getString(it) })
        }
    }
}

data class PinnedRecipeGuide(val revisionId: String, val snapshot: RecipeSnapshot, val story: String) {
    val recordedSnapshot: RecipeSnapshot get() {
        val facts = JSONObject(story)
        return RecipeSnapshotCodec.decode(JSONObject(RecipeSnapshotCodec.encode(snapshot)).put("observations", facts.optJSONArray("observations") ?: JSONArray())
            .put("smokingAnchorUtcMillis", facts.opt("anchorUtcMillis") ?: JSONObject.NULL).toString())
    }
    fun encode() = JSONObject().put("version", 1).put("revisionId", revisionId)
        .put("snapshot", JSONObject(RecipeSnapshotCodec.encode(snapshot))).put("story", JSONObject(story))
    companion object {
        fun decode(o: JSONObject): PinnedRecipeGuide {
            require(o.getInt("version") == 1 && o.getString("revisionId").isNotBlank())
            val result = PinnedRecipeGuide(o.getString("revisionId"), RecipeSnapshotCodec.decode(o.getJSONObject("snapshot").toString()), o.getJSONObject("story").toString())
            SharingDraftCodec.validate(SharingDocument(result.snapshot.name.take(160), listOf(SharingEntry(snapshot = result.snapshot, storyJson = result.story))))
            return result
        }
    }
}

data class AlignedGuide(val readings: List<SensorReadingEntity> = emptyList(), val gaps: List<TimelineEventEntity> = emptyList(), val originalTimes: Map<String, Long> = emptyMap(), val matches: Map<String, String> = emptyMap())

object RecipeGuideProjection {
    fun role(raw: String) = when (raw) {
        "food", "food_probe" -> "food"
        "pit", "pit_ambient", "ambient_temperature", "grill_temperature", "pit_temperature", "pit_probe" -> "pit"
        else -> raw
    }
    fun channel(dishId: String, row: JSONObject) = listOf(dishId, if (row.isNull("probeId")) "sample:${row.getString("id")}" else row.getString("probeId"), role(row.getString("measurementType"))).joinToString("|")
    fun rows(guide: PinnedRecipeGuide) = CookPackHistory.rows(JSONObject(guide.story), "readings")
    fun currentAnchor(data: CookDetailData, dishId: String): Long? {
        val created = data.dishes.firstOrNull { it.id == dishId }?.createdAtUtcMillis ?: return null
        return data.events.filter { event ->
            val scopes = CookNoteMetadata.decode(event.noteMetadataJson)?.dishIds.orEmpty()
            PlaybookCodec.action(event.eventType, event.noteMetadataJson) == "food_on" &&
                (event.dishId == dishId || dishId in scopes || event.dishId == null && scopes.isEmpty() && event.recordedAtUtcMillis >= created)
        }.minOfOrNull { it.occurredAtUtcMillis }
    }
    fun aligned(guide: PinnedRecipeGuide, dishId: String, name: String, anchor: Long?): AlignedGuide {
        val story = JSONObject(guide.story)
        if (anchor == null || story.isNull("anchorUtcMillis")) return AlignedGuide()
        val originalAnchor = story.getLong("anchorUtcMillis"); val original = mutableMapOf<String, Long>()
        val samples = rows(guide).map { row ->
            val sourceAt = row.getLong("measuredAtUtcMillis"); val at = anchor + sourceAt - originalAnchor
            val id = "guide:$dishId:${row.getString("id")}"
            original[id] = sourceAt
            SensorReadingEntity(id, "guide:$dishId", dishId, if (row.isNull("probeId")) "sample:${row.getString("id")}" else row.getString("probeId"),
                "Author · $name · ${row.getString("probeName")}", row.getString("measurementType"), row.getDouble("value"), row.getString("unit"),
                at, row.optString("timeZoneId", "UTC"), row.getString("source"), qualityStatus = row.optString("qualityStatus", "valid"),
                recordedAtUtcMillis = at, timestampBasis = row.optString("timestampBasis", "measurement"), samplingIntervalMillis = row.optLong("samplingIntervalMillis", 15000))
        }
        val gaps = story.optJSONArray("connectionGaps")?.let { a -> (0 until a.length()).map { i ->
            val at = anchor + a.getLong(i) - originalAnchor
            TimelineEventEntity("guide-gap:$dishId:$i", "guide:$dishId", dishId, "connection_gap", "Reference measurement gap", occurredAtUtcMillis = at,
                recordedAtUtcMillis = at, timeZoneId = "UTC", source = "controller_cloud", createdAtUtcMillis = at, updatedAtUtcMillis = at)
        } }.orEmpty()
        return AlignedGuide(samples, gaps, original)
    }
}

/** Presentation changes only. No scheduler, telemetry runtime or actual-log writer is called here. */
class RecipeGuideRepository(private val database: PitTechDatabase, private val cooks: CookRepository) {
    private val dao = database.companionDao()
    fun observe(cookId: String) = dao.observeCook(cookId)
    suspend fun pin(revision: RecipeRevision, snapshot: RecipeSnapshot, inheritedRevisionId: String? = revision.parentRevisionId): JSONObject {
        val inherited = inheritedRevisionId?.let { database.recipeDao().revision(it) }
        val shared = dao.all().asSequence().filter { it.kind in setOf("recipe_reference", "collection_revision") }
            .flatMap { SharingDraftCodec.decode(it.payload).entries.asSequence() }.filter { it.includeStory }.toList().let { entries ->
                entries.firstOrNull { it.sourceRevisionId == revision.id } ?: entries.firstOrNull { inherited != null && revision.sourceCookId == inherited.sourceCookId && it.sourceRevisionId == inherited.id }
            }
        val story = shared?.storyJson ?: run {
            val source = revision.sourceCookId?.takeIf { database.cookDao().getCook(it) != null }?.let { cooks.exportSnapshot(it) }
            val data = source?.let { CookDetailData(it.cooks.single(), it.dishes, it.ingredients, it.events, it.readings, it.targets, it.results, it.photos, it.reminders, logReadingLinks = it.logReadingLinks) }
            if (data != null && data.dishes.any { it.id == revision.sourceDishId }) cooks.sharing.story(data, revision.sourceDishId!!)
            else JSONObject().put("version", 1).put("attemptId", revision.sourceDishId ?: snapshot.observations.firstOrNull()?.sourceDishId ?: if (snapshot.observations.any { it.recordedTemperatures.isNotEmpty() }) "saved-observations:${revision.id}" else JSONObject.NULL)
                .put("anchorUtcMillis", snapshot.smokingAnchorUtcMillis ?: JSONObject.NULL)
                .put("observations", JSONObject(revision.snapshotJson).getJSONArray("observations"))
                .put("readings", JSONArray(snapshot.observations.flatMap { it.recordedTemperatures }.distinctBy { it.sourceReadingId }.map { r ->
                    JSONObject().put("id", r.sourceReadingId).put("probeId", r.probeId ?: JSONObject.NULL).put("probeName", r.probeName).put("measurementType", r.measurementType)
                        .put("value", r.value).put("unit", r.unit).put("measuredAtUtcMillis", r.measuredAtUtcMillis).put("timeZoneId", r.timeZoneId)
                        .put("source", "manual").put("samplingIntervalMillis", 0).put("qualityStatus", "valid")
                })).put("results", JSONArray()).toString()
        }
        return PinnedRecipeGuide(revision.id, snapshot, story).encode()
    }
    suspend fun retain(revision: RecipeRevision, snapshot: RecipeSnapshot, inheritedRevisionId: String?) {
        val pinned = pin(revision, snapshot, inheritedRevisionId)
        val entry = SharingEntry(sourceDishId = revision.sourceDishId, sourceRevisionId = revision.id, snapshot = snapshot, storyJson = pinned.getJSONObject("story").toString(), includeStory = true)
        val document = SharingDocument(snapshot.name.take(160), listOf(entry))
        dao.put(CompanionRecord("recipe-guide:${revision.id}", "recipe_reference", title = document.title, payload = SharingDraftCodec.encode(document), createdAtUtcMillis = revision.createdAtUtcMillis, updatedAtUtcMillis = revision.createdAtUtcMillis))
    }
    suspend fun ensure(cookId: String) = database.withTransaction {
        dao.forCook(cookId).filter { it.kind == "recipe_attempt" }.forEach { record ->
            val payload = JSONObject(record.payload)
            if (payload.optJSONObject("guide") == null) {
                val revision = database.recipeDao().revision(payload.getString("revisionId")) ?: return@forEach
                payload.put("guide", pin(revision, RecipeSnapshotCodec.decode(revision.snapshotJson)))
                dao.put(record.copy(payload = payload.toString()))
            }
        }
    }
    suspend fun save(cookId: String, state: GuidePresentation) = database.withTransaction {
        require(database.cookDao().getCook(cookId) != null)
        val available = database.cookDao().getDishesForCook(cookId).map { it.id }.toSet()
        require(state.dishIds == null || available.containsAll(state.dishIds))
        val id = "guide-presentation:$cookId"; val previous = dao.get(id); val now = System.currentTimeMillis()
        dao.put(CompanionRecord(id, "guide_presentation", cookId, "Show guide", state.encode(), previous?.createdAtUtcMillis ?: now, now))
    }
}
