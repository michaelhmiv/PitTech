package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.CookEntryValidation
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class CookRepository(
    private val database: PitTechDatabase,
    private val photoStorage: PhotoStorage,
) {
    private val dao = database.cookDao()

    fun observeCooks(): Flow<List<CookWithDishes>> = dao.observeCooks()

    suspend fun startCook(draft: NewCookDraft): String {
        require(CookEntryValidation.isOptionalPositiveNumberValid(draft.setpointText)) {
            "Enter a positive setpoint or leave it blank."
        }
        draft.dishes.forEach { dish ->
            require(dish.name.isNotBlank()) { "Each dish needs a name or cut." }
            require(CookEntryValidation.isOptionalPositiveNumberValid(dish.weightText)) {
                "Enter a positive weight or leave it blank."
            }
            dish.preparationItems.forEach { item ->
                require(CookEntryValidation.isOptionalPositiveNumberValid(item.amountText)) {
                    "Enter a positive seasoning amount or leave it blank."
                }
            }
        }

        val now = System.currentTimeMillis()
        val timeZoneId = ZoneId.systemDefault().id
        val cookId = UUID.randomUUID().toString()
        val setpoint = CookEntryValidation.optionalPositiveNumber(draft.setpointText)
        val cook = CookEntity(
            id = cookId,
            title = draft.title.trim().ifBlank { "Cook ${DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now))}" },
            status = CookStatus.ACTIVE,
            startedAtUtcMillis = now,
            startedTimeZoneId = timeZoneId,
            smokerName = draft.smokerName.trim().ifBlank { null },
            initialSetpointValue = setpoint,
            initialSetpointUnit = setpoint?.let { draft.setpointUnit },
            notes = draft.notes.trim().ifBlank { null },
            createdAtUtcMillis = now,
            updatedAtUtcMillis = now,
        )

        val dishPairs = draft.dishes.map { it to UUID.randomUUID().toString() }
        val dishes = dishPairs.map { (dishDraft, dishId) -> dishDraft.toEntity(cookId, dishId, now) }
        val ingredients = dishPairs.flatMap { (dishDraft, dishId) ->
            dishDraft.preparationItems
                .filter { it.name.isNotBlank() }
                .map { ingredient ->
                    IngredientEntity(
                        id = UUID.randomUUID().toString(),
                        cookId = cookId,
                        dishId = dishId,
                        stage = ingredient.stage,
                        name = ingredient.name.trim(),
                        amountValue = CookEntryValidation.optionalPositiveNumber(ingredient.amountText),
                        amountUnit = ingredient.amountUnit.takeIf { ingredient.amountText.isNotBlank() },
                        createdAtUtcMillis = now,
                    )
                }
        }

        val events = buildList {
            add(
                TimelineEventEntity(
                    id = UUID.randomUUID().toString(),
                    cookId = cookId,
                    eventType = "cook_started",
                    title = "Cook started",
                    occurredAtUtcMillis = now,
                    recordedAtUtcMillis = now,
                    timeZoneId = timeZoneId,
                    source = "manual",
                    createdAtUtcMillis = now,
                    updatedAtUtcMillis = now,
                ),
            )
            if (setpoint != null) {
                add(
                    TimelineEventEntity(
                        id = UUID.randomUUID().toString(),
                        cookId = cookId,
                        eventType = "setpoint_recorded",
                        title = "Starting setpoint recorded",
                        details = "$setpoint ${draft.setpointUnit}",
                        occurredAtUtcMillis = now,
                        recordedAtUtcMillis = now,
                        timeZoneId = timeZoneId,
                        source = "manual",
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    ),
                )
            }
        }

        val importedPhotos = mutableListOf<PhotoEntity>()
        try {
            dishPairs.forEach { (dishDraft, dishId) ->
                dishDraft.photoUris.forEach { uri ->
                    importedPhotos += photoStorage.copyIntoLibrary(uri, cookId, dishId, now)
                }
            }
            database.withTransaction {
                dao.insertCook(cook)
                if (dishes.isNotEmpty()) dao.insertDishes(dishes)
                if (ingredients.isNotEmpty()) dao.insertIngredients(ingredients)
                dao.insertTimelineEvents(events)
                if (importedPhotos.isNotEmpty()) dao.insertPhotos(importedPhotos)
            }
        } catch (failure: Throwable) {
            importedPhotos.forEach { photoStorage.delete(it.relativePath) }
            throw failure
        }
        return cookId
    }

    private fun DishDraft.toEntity(cookId: String, dishId: String, now: Long) = DishEntity(
        id = dishId,
        cookId = cookId,
        name = name.trim(),
        foodType = foodType,
        cut = cut.trim().ifBlank { null },
        weightValue = CookEntryValidation.optionalPositiveNumber(weightText),
        weightUnit = weightUnit.takeIf { weightText.isNotBlank() },
        startingCondition = startingCondition,
        boneIn = boneIn,
        prepNotes = prepNotes.trim().ifBlank { null },
        createdAtUtcMillis = now,
        updatedAtUtcMillis = now,
    )
}

object CookStatus {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
}
