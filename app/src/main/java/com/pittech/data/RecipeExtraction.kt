package com.pittech.data

import com.pittech.domain.*
import java.util.UUID

data class RecipeExtractionDraft(val snapshot: RecipeSnapshot, val photoIds: Set<String>, val contextEvents: List<TimelineEventEntity>)

object RecipeExtraction {
    fun fromMeal(data: CookDetailData, dishId: String, contextIds: Set<String> = emptySet()): RecipeExtractionDraft {
        val dish = data.dishes.firstOrNull { it.id == dishId } ?: error("This dish is unavailable.")
        require(contextIds.all { id -> data.events.any { it.id == id } }) { "Choose context from this meal." }
        fun owns(e: TimelineEventEntity): Boolean = e.dishId == dishId || dishId in CookNoteMetadata.decode(e.noteMetadataJson)?.dishIds.orEmpty()
        val owned = data.events.filter { owns(it) || it.id in contextIds }.sortedBy { it.occurredAtUtcMillis }
        // A whole-meal Food on is explicitly an action for every original dish, not a guessed start.
        val anchorEvent = data.events.sortedBy { it.occurredAtUtcMillis }.firstOrNull { e ->
            PlaybookCodec.action(e.eventType, e.noteMetadataJson) == "food_on" &&
                (owns(e) || e.dishId == null && CookNoteMetadata.decode(e.noteMetadataJson)?.dishIds.isNullOrEmpty())
        }
        val anchor = anchorEvent?.occurredAtUtcMillis
        val events = (owned + listOfNotNull(anchorEvent)).distinctBy { it.id }.sortedBy { it.occurredAtUtcMillis }
        val ingredients = data.ingredients.filter { it.dishId == dishId }.map {
            IngredientDraft(it.name, it.stage, it.brand.orEmpty(), it.amountText ?: it.amountValue?.toString().orEmpty(), it.amountUnit ?: "count")
        } + owned.flatMap { CookNoteMetadata.decode(it.noteMetadataJson)?.ingredients.orEmpty() }
        val setup = DishDraft(dish.name, dish.foodType, dish.cut.orEmpty(), dish.weightValue?.toString().orEmpty(), dish.weightUnit ?: "lb",
            dish.startingCondition, dish.boneIn, dish.placement.orEmpty(), dish.gradeOrSource.orEmpty(), dish.thicknessNotes.orEmpty(), dish.prepNotes.orEmpty(), ingredients)
        fun relevant(reading: SensorReadingEntity) = reading.dishId == dishId || reading.measurementType in setOf("pit", "pit_ambient", "setpoint")
        fun scopedContext(event: TimelineEventEntity): String? = TemperatureContext.encode(TemperatureContext.decode(event.temperatureContextJson).filter { value ->
            val matches = data.readings.filter { it.probeName == value.name && it.value == value.value && it.unit == value.unit && it.measuredAtUtcMillis == value.observedAtUtcMillis && it.source == value.source }
            if (matches.isNotEmpty()) matches.all(::relevant) else event.dishId == dishId
        })
        val observations = events.map { e -> RecipeObservation(e.id, e.occurredAtUtcMillis, e.timeZoneId, e.title, e.details.orEmpty(),
            PlaybookCodec.action(e.eventType, e.noteMetadataJson).takeIf { it in CookNoteMetadata.actions }, anchor?.let { e.occurredAtUtcMillis - it }, scopedContext(e),
            e.noteMetadataJson, data.cook.id, dishId, data.logReadingLinks.filter { it.eventId == e.id }.mapNotNull { link ->
                data.readings.firstOrNull { it.id == link.readingId && it.qualityStatus == "valid" && (relevant(it) || it.dishId == null && e.id in contextIds) }?.let { t ->
                    RecipeMeasurement(t.id, t.probeId, t.probeName, t.measurementType, t.value, t.unit, t.measuredAtUtcMillis, t.timeZoneId, t.dishId)
                }
            }) }
        val instructions = owned.map { e -> val metadata = CookNoteMetadata.decode(e.noteMetadataJson)
            RecipeInstruction(UUID.randomUUID().toString(), e.title, e.details.orEmpty(), metadata?.method,
                anchor?.let { e.occurredAtUtcMillis - it }, metadata?.durationMillis, e.id) }
        val results = data.results.filter { it.dishId == dishId }
        val snapshot = RecipeSnapshot(dish.name, setup, instructions = instructions, observations = observations,
            targets = data.targets.filter { it.dishId == dishId }.map { PlaybookTarget(0, it.targetType, it.value, it.unit, it.explanation) },
            resultNotes = results.mapNotNull { it.textValue }.distinct().joinToString("\n"),
            rating = results.firstOrNull { it.resultType == "overall" }?.numericValue, smokingAnchorUtcMillis = anchor)
        val eventIds = events.map { it.id }.toSet()
        return RecipeExtractionDraft(snapshot, data.photos.filter { it.dishId == dishId || it.eventId in eventIds }.map { it.id }.toSet(),
            data.events.filter { !owns(it) && it.id != anchorEvent?.id && it.dishId == null && CookNoteMetadata.decode(it.noteMetadataJson)?.dishIds.isNullOrEmpty() })
    }
    private fun mergeInstructions(previous: List<RecipeInstruction>, recorded: List<RecipeInstruction>): List<RecipeInstruction> {
        val groups = recorded.groupBy { it.title.trim().lowercase() to it.method }
        val used = mutableMapOf<Pair<String, String?>, Int>()
        val result = previous.map { old -> val key = old.title.trim().lowercase() to old.method; val index = used[key] ?: 0; used[key] = index + 1; groups[key]?.getOrNull(index) ?: old }
        return result + recorded.groupBy { it.title.trim().lowercase() to it.method }.flatMap { (key, values) -> values.drop(used[key] ?: 0) }
    }
    fun mergeAttempt(previous: RecipeSnapshot, attempt: RecipeSnapshot): RecipeSnapshot = attempt.copy(
        name = previous.name, description = previous.description, tags = previous.tags,
        dish = attempt.dish.copy(preparationItems = attempt.dish.preparationItems.ifEmpty { previous.dish.preparationItems },
            prepNotes = attempt.dish.prepNotes.ifBlank { previous.dish.prepNotes }),
        instructions = mergeInstructions(previous.instructions, attempt.instructions),
        resultNotes = attempt.resultNotes.ifBlank { previous.resultNotes },
        preferredPitTemperatureF = previous.preferredPitTemperatureF, servings = previous.servings,
        targets = attempt.targets.ifEmpty { previous.targets })
}

object RecipeAttemptCodec {
    fun encode(dishId: String, revisionId: String, snapshot: RecipeSnapshot) = org.json.JSONObject().put("version", 1)
        .put("dishId", dishId).put("revisionId", revisionId).put("snapshot", org.json.JSONObject(RecipeSnapshotCodec.encode(snapshot))).toString()
    fun snapshot(raw: String): RecipeSnapshot = org.json.JSONObject(raw).let { o -> require(o.getInt("version") == 1); RecipeSnapshotCodec.decode(o.getJSONObject("snapshot").toString()) }
}
