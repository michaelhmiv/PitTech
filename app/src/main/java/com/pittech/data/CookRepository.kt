package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.CookEntryValidation
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    fun observeCookDetail(cookId: String): Flow<CookDetailData?> {
        val core = combine(
            dao.observeCook(cookId),
            dao.observeIngredients(cookId),
            dao.observeTimelineEvents(cookId),
            dao.observeSensorReadings(cookId),
        ) { cook, ingredients, events, readings -> CookCore(cook, ingredients, events, readings) }
        val extras = combine(
            dao.observeTargets(cookId),
            dao.observeResults(cookId),
            dao.observePhotos(cookId),
        ) { targets, results, photos -> CookExtras(targets, results, photos) }
        return combine(core, extras) { first, second ->
            first.cook?.let { relation ->
                CookDetailData(
                    cook = relation.cook,
                    dishes = relation.dishes,
                    ingredients = first.ingredients,
                    events = first.events,
                    readings = first.readings,
                    targets = second.targets,
                    results = second.results,
                    photos = second.photos,
                )
            }
        }
    }

    fun observeInsights(): Flow<InsightsSnapshot> = combine(
        dao.observeCooks(),
        dao.observeAllSensorReadings(),
        dao.observeAllResults(),
    ) { cooks, readings, results -> InsightsSnapshot(cooks, readings, results) }

    suspend fun startCook(draft: NewCookDraft): String {
        require(CookEntryValidation.isOptionalPositiveNumberValid(draft.setpointText)) {
            "Enter a positive setpoint or leave it blank."
        }
        require(CookEntryValidation.isOptionalFiniteNumberValid(draft.outdoorTemperatureText)) {
            "Enter a valid outdoor temperature or leave it blank."
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
            fuelType = draft.fuelType.trim().ifBlank { null },
            woodOrPelletBlend = draft.woodOrPelletBlend.trim().ifBlank { null },
            outdoorTemperatureValue = CookEntryValidation.optionalFiniteNumber(draft.outdoorTemperatureText),
            outdoorTemperatureUnit = CookEntryValidation.optionalFiniteNumber(draft.outdoorTemperatureText)?.let { draft.outdoorTemperatureUnit },
            weatherNotes = draft.weatherNotes.trim().ifBlank { null },
            windNotes = draft.windNotes.trim().ifBlank { null },
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
                        brand = ingredient.brand.trim().ifBlank { null },
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

    suspend fun updateCookDetails(
        cookId: String,
        title: String,
        smokerName: String,
        setpointText: String,
        setpointUnit: String,
        notes: String,
        fuelType: String,
        woodOrPelletBlend: String,
        outdoorTemperatureText: String,
        outdoorTemperatureUnit: String,
        weatherNotes: String,
        windNotes: String,
    ) {
        require(CookEntryValidation.isOptionalPositiveNumberValid(setpointText)) { "Enter a positive setpoint or leave it blank." }
        require(CookEntryValidation.isOptionalFiniteNumberValid(outdoorTemperatureText)) { "Enter a valid outdoor temperature or leave it blank." }
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        val setpoint = CookEntryValidation.optionalPositiveNumber(setpointText)
        dao.updateCook(
            cook.copy(
                title = title.trim().ifBlank { cook.title },
                smokerName = smokerName.trim().ifBlank { null },
                initialSetpointValue = setpoint,
                initialSetpointUnit = setpoint?.let { setpointUnit },
                notes = notes.trim().ifBlank { null },
                fuelType = fuelType.trim().ifBlank { null },
                woodOrPelletBlend = woodOrPelletBlend.trim().ifBlank { null },
                outdoorTemperatureValue = CookEntryValidation.optionalFiniteNumber(outdoorTemperatureText),
                outdoorTemperatureUnit = CookEntryValidation.optionalFiniteNumber(outdoorTemperatureText)?.let { outdoorTemperatureUnit },
                weatherNotes = weatherNotes.trim().ifBlank { null },
                windNotes = windNotes.trim().ifBlank { null },
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun addDish(cookId: String, draft: DishDraft): String {
        require(draft.name.isNotBlank()) { "Each dish needs a name or cut." }
        require(CookEntryValidation.isOptionalPositiveNumberValid(draft.weightText)) { "Enter a positive weight or leave it blank." }
        draft.preparationItems.forEach { item ->
            require(CookEntryValidation.isOptionalPositiveNumberValid(item.amountText)) { "Enter a positive preparation amount or leave it blank." }
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val dish = draft.toEntity(cookId, id, now)
        val ingredients = draft.preparationItems.filter { it.name.isNotBlank() }.map { item ->
            IngredientEntity(
                id = UUID.randomUUID().toString(), cookId = cookId, dishId = id, stage = item.stage,
                name = item.name.trim(), brand = item.brand.trim().ifBlank { null },
                amountValue = CookEntryValidation.optionalPositiveNumber(item.amountText),
                amountUnit = item.amountUnit.takeIf { item.amountText.isNotBlank() },
                createdAtUtcMillis = now,
            )
        }
        val importedPhotos = mutableListOf<PhotoEntity>()
        try {
            draft.photoUris.forEach { uri -> importedPhotos += photoStorage.copyIntoLibrary(uri, cookId, id, now) }
            database.withTransaction {
                dao.insertDish(dish)
                if (ingredients.isNotEmpty()) dao.insertIngredients(ingredients)
                if (importedPhotos.isNotEmpty()) dao.insertPhotos(importedPhotos)
            }
        } catch (failure: Throwable) {
            importedPhotos.forEach { photoStorage.delete(it.relativePath) }
            throw failure
        }
        return id
    }

    suspend fun updateDishDetails(dish: DishEntity) {
        require(dish.name.isNotBlank()) { "Enter a dish name." }
        require(dish.weightValue == null || (dish.weightValue > 0.0 && dish.weightValue.isFinite())) { "Enter a positive weight or leave it blank." }
        dao.updateDish(dish.copy(updatedAtUtcMillis = System.currentTimeMillis()))
    }

    suspend fun deleteDish(dish: DishEntity) = dao.deleteDish(dish)

    suspend fun addTimelineEvent(
        cookId: String,
        dishId: String?,
        eventType: String,
        title: String,
        details: String?,
        occurredAtUtcMillis: Long,
        timeZoneId: String = ZoneId.systemDefault().id,
    ): TimelineEventEntity {
        require(title.isNotBlank()) { "Enter a title for this entry." }
        val now = System.currentTimeMillis()
        val event = TimelineEventEntity(
            id = UUID.randomUUID().toString(), cookId = cookId, dishId = dishId,
            eventType = eventType, title = title.trim(), details = details?.trim()?.ifBlank { null },
            occurredAtUtcMillis = occurredAtUtcMillis, recordedAtUtcMillis = now,
            timeZoneId = timeZoneId, source = "manual", createdAtUtcMillis = now, updatedAtUtcMillis = now,
        )
        dao.insertTimelineEvent(event)
        return event
    }

    suspend fun updateTimelineEvent(event: TimelineEventEntity) {
        require(event.title.isNotBlank()) { "Enter a title for this entry." }
        dao.updateTimelineEvent(
            event.copy(
                title = event.title.trim(),
                details = event.details?.trim()?.ifBlank { null },
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteTimelineEvent(event: TimelineEventEntity) = dao.deleteTimelineEvent(event)

    suspend fun restoreTimelineEvent(event: TimelineEventEntity) = dao.insertTimelineEvent(event)

    suspend fun updateSensorReading(reading: SensorReadingEntity) {
        require(reading.value.isFinite()) { "Enter a valid temperature." }
        dao.updateSensorReading(reading.copy(recordedAtUtcMillis = System.currentTimeMillis()))
    }

    suspend fun deleteSensorReading(reading: SensorReadingEntity) = dao.deleteSensorReading(reading)

    suspend fun restoreSensorReading(reading: SensorReadingEntity) = dao.insertSensorReading(reading)

    suspend fun addManualTemperature(
        cookId: String,
        dishId: String?,
        probeName: String,
        measurementType: String,
        value: Double,
        unit: String,
        measuredAtUtcMillis: Long,
    ): SensorReadingEntity {
        require(value.isFinite()) { "Enter a valid temperature." }
        require(probeName.isNotBlank()) { "Enter a probe or measurement name." }
        val now = System.currentTimeMillis()
        val reading = SensorReadingEntity(
            id = UUID.randomUUID().toString(), cookId = cookId, dishId = dishId,
            probeName = probeName.trim(), measurementType = measurementType,
            value = value, unit = unit, measuredAtUtcMillis = measuredAtUtcMillis,
            timeZoneId = ZoneId.systemDefault().id, source = "manual", qualityStatus = "valid",
            recordedAtUtcMillis = now,
        )
        dao.insertSensorReading(reading)
        return reading
    }

    suspend fun addTarget(cookId: String, dishId: String?, type: String, value: Double, unit: String, explanation: String?) {
        require(value.isFinite() && value > 0.0) { "Enter a positive target value." }
        val now = System.currentTimeMillis()
        dao.insertTarget(
            TargetEntity(
                id = UUID.randomUUID().toString(), cookId = cookId, dishId = dishId,
                targetType = type, value = value, unit = unit, scope = if (dishId == null) "cook" else "dish",
                explanation = explanation?.trim()?.ifBlank { null }, createdAtUtcMillis = now,
            ),
        )
    }

    suspend fun deleteTarget(target: TargetEntity) = dao.deleteTarget(target)

    suspend fun addCookPhoto(cookId: String, uri: String, caption: String?): PhotoEntity {
        val now = System.currentTimeMillis()
        val event = addTimelineEvent(cookId, null, "photo", caption?.takeIf { it.isNotBlank() } ?: "Photo added", null, now)
        val photo = try {
            photoStorage.copyIntoLibrary(uri, cookId, null, now, event.id, caption)
        } catch (failure: Throwable) {
            dao.deleteTimelineEvent(event)
            throw failure
        }
        return try {
            dao.insertPhotos(listOf(photo))
            photo
        } catch (failure: Throwable) {
            photoStorage.delete(photo.relativePath)
            dao.deleteTimelineEvent(event)
            throw failure
        }
    }

    suspend fun saveCookResults(
        cookId: String,
        dishId: String?,
        finalTemperatureText: String,
        temperatureUnit: String,
        restMinutesText: String,
        ratings: Map<String, String>,
        notes: String,
        finish: Boolean,
    ) {
        require(CookEntryValidation.isOptionalPositiveNumberValid(finalTemperatureText)) { "Enter a positive final temperature or leave it blank." }
        require(CookEntryValidation.isOptionalPositiveNumberValid(restMinutesText)) { "Enter a positive rest time or leave it blank." }
        val parsedRatings = ratings.mapValues { (_, text) -> CookEntryValidation.optionalPositiveNumber(text) }
        require(parsedRatings.values.all { it == null || it in 1.0..5.0 }) { "Ratings must be from 1 to 5." }
        val now = System.currentTimeMillis()
        listOf(
            Triple("final_temperature", CookEntryValidation.optionalPositiveNumber(finalTemperatureText), temperatureUnit),
            Triple("rest_minutes", CookEntryValidation.optionalPositiveNumber(restMinutesText), "min"),
        ).forEach { (type, value, unit) ->
            if (value != null) {
                dao.upsertResult(
                    CookResultEntity(
                        id = "$cookId:${dishId ?: "cook"}:$type", cookId = cookId, dishId = dishId,
                        resultType = type, numericValue = value, unit = unit, recordedAtUtcMillis = now,
                    ),
                )
            } else {
                dao.deleteResult("$cookId:${dishId ?: "cook"}:$type")
            }
        }
        if (notes.isNotBlank()) {
            dao.upsertResult(
                CookResultEntity(
                    id = "$cookId:${dishId ?: "cook"}:notes", cookId = cookId, dishId = dishId,
                    resultType = "result_notes", textValue = notes.trim(), recordedAtUtcMillis = now,
                ),
            )
        } else dao.deleteResult("$cookId:${dishId ?: "cook"}:notes")
        parsedRatings.forEach { (type, value) ->
            if (value != null) dao.upsertResult(
                CookResultEntity(
                    id = "$cookId:${dishId ?: "cook"}:$type", cookId = cookId, dishId = dishId,
                    resultType = type, numericValue = value, unit = "stars", recordedAtUtcMillis = now,
                ),
            ) else dao.deleteResult("$cookId:${dishId ?: "cook"}:$type")
        }
        if (finish) completeCook(cookId)
    }

    suspend fun completeCook(cookId: String) {
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        if (cook.status == CookStatus.COMPLETED) return
        val now = System.currentTimeMillis()
        dao.updateCook(cook.copy(status = CookStatus.COMPLETED, endedAtUtcMillis = now, updatedAtUtcMillis = now))
        addTimelineEvent(cookId, null, "cook_finished", "Cook finished", null, now)
    }

    suspend fun pauseCook(cookId: String) {
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        if (cook.status != CookStatus.ACTIVE) return
        val now = System.currentTimeMillis()
        dao.updateCook(cook.copy(status = CookStatus.PAUSED, updatedAtUtcMillis = now))
        addTimelineEvent(cookId, null, "cook_paused", "Cook paused", null, now)
    }

    suspend fun resumeCook(cookId: String) {
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        if (cook.status != CookStatus.PAUSED) return
        val now = System.currentTimeMillis()
        dao.updateCook(cook.copy(status = CookStatus.ACTIVE, updatedAtUtcMillis = now))
        addTimelineEvent(cookId, null, "cook_resumed", "Cook resumed", null, now)
    }

    suspend fun deleteCook(cook: CookEntity) {
        val photos = dao.getPhotosForCook(cook.id)
        database.withTransaction { dao.deleteCook(cook) }
        photos.forEach { photoStorage.delete(it.relativePath) }
    }

    suspend fun duplicateCookSetup(cookId: String): String {
        val relation = dao.observeCook(cookId).first() ?: error("This cook could not be found.")
        val ingredients = dao.getIngredientsForCook(cookId)
        val copiedDishes = relation.dishes.map { dish ->
            DishDraft(
                name = dish.name, foodType = dish.foodType, cut = dish.cut.orEmpty(),
                weightText = dish.weightValue?.toString().orEmpty(), weightUnit = dish.weightUnit ?: "lb",
                startingCondition = dish.startingCondition, boneIn = dish.boneIn,
                placement = dish.placement.orEmpty(), gradeOrSource = dish.gradeOrSource.orEmpty(),
                thicknessNotes = dish.thicknessNotes.orEmpty(), prepNotes = dish.prepNotes.orEmpty(),
                preparationItems = ingredients.filter { it.dishId == dish.id }.map { item ->
                    com.pittech.domain.IngredientDraft(
                        name = item.name, stage = item.stage, brand = item.brand.orEmpty(),
                        amountText = item.amountValue?.toString().orEmpty(), amountUnit = item.amountUnit ?: "tbsp",
                    )
                },
            )
        }
        val old = relation.cook
        return startCook(
            NewCookDraft(
                title = "Copy of ${old.title}", smokerName = old.smokerName.orEmpty(),
                setpointText = old.initialSetpointValue?.toString().orEmpty(),
                setpointUnit = old.initialSetpointUnit ?: "°F", fuelType = old.fuelType.orEmpty(),
                woodOrPelletBlend = old.woodOrPelletBlend.orEmpty(), dishes = copiedDishes,
            ),
        )
    }

    suspend fun exportSnapshot(cookId: String? = null): ExportSnapshot {
        val allCooks = dao.getAllCooks()
        val selectedCookIds = if (cookId == null) allCooks.map { it.id }.toSet() else setOf(cookId).intersect(allCooks.map { it.id }.toSet())
        require(cookId == null || selectedCookIds.isNotEmpty()) { "This cook is no longer in your library." }
        return ExportSnapshot(
            cooks = allCooks.filter { it.id in selectedCookIds },
            dishes = dao.getAllDishes().filter { it.cookId in selectedCookIds },
            ingredients = dao.getAllIngredients().filter { it.cookId in selectedCookIds },
            events = dao.getAllTimelineEvents().filter { it.cookId in selectedCookIds },
            readings = dao.getAllSensorReadings().filter { it.cookId in selectedCookIds },
            targets = dao.getAllTargets().filter { it.cookId in selectedCookIds },
            results = dao.getAllResults().filter { it.cookId in selectedCookIds },
            devices = dao.getAllDevices().filter { it.cookId in selectedCookIds },
            probes = dao.getAllProbes().filter { it.cookId in selectedCookIds },
            photos = dao.getAllPhotos().filter { it.cookId in selectedCookIds },
        )
    }

    suspend fun importSnapshot(snapshot: ExportSnapshot, attachmentData: Map<String, ByteArray>): ImportSummary {
        val existingCookIds = dao.getAllCooks().map { it.id }.toSet()
        val newCooks = snapshot.cooks.filterNot { it.id in existingCookIds }
        val newIds = newCooks.map { it.id }.toSet()
        val existingPhotoIds = dao.getAllPhotos().map { it.id }.toSet()
        val photos = snapshot.photos.filter { it.cookId in newIds && it.id !in existingPhotoIds }
        val restoredPhotos = mutableListOf<PhotoEntity>()
        try {
            photos.forEach { photo ->
                val bytes = attachmentData[photo.id] ?: return@forEach
                photoStorage.writeImported(photo.relativePath, bytes)
                restoredPhotos += photo
            }
            database.withTransaction {
                dao.insertCooksIgnoringDuplicates(newCooks)
                dao.insertDishesIgnoringDuplicates(snapshot.dishes.filter { it.cookId in newIds })
                dao.insertIngredientsIgnoringDuplicates(snapshot.ingredients.filter { it.cookId in newIds })
                dao.insertEventsIgnoringDuplicates(snapshot.events.filter { it.cookId in newIds })
                dao.insertTargetsIgnoringDuplicates(snapshot.targets.filter { it.cookId in newIds })
                dao.insertResultsIgnoringDuplicates(snapshot.results.filter { it.cookId in newIds })
                dao.insertDevicesIgnoringDuplicates(snapshot.devices.filter { it.cookId in newIds })
                dao.insertProbesIgnoringDuplicates(snapshot.probes.filter { it.cookId in newIds })
                dao.insertReadingsIgnoringDuplicates(snapshot.readings.filter { it.cookId in newIds })
                dao.insertPhotosIgnoringDuplicates(restoredPhotos)
            }
        } catch (failure: Throwable) {
            restoredPhotos.forEach { photo -> photoStorage.delete(photo.relativePath) }
            throw failure
        }
        return ImportSummary(newCooks.size, snapshot.cooks.size - newCooks.size, restoredPhotos.size)
    }

    suspend fun readPhoto(relativePath: String): ByteArray? = photoStorage.read(relativePath)

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
        placement = placement.trim().ifBlank { null },
        gradeOrSource = gradeOrSource.trim().ifBlank { null },
        thicknessNotes = thicknessNotes.trim().ifBlank { null },
        prepNotes = prepNotes.trim().ifBlank { null },
        createdAtUtcMillis = now,
        updatedAtUtcMillis = now,
    )

    private data class CookCore(
        val cook: CookWithDishes?,
        val ingredients: List<IngredientEntity>,
        val events: List<TimelineEventEntity>,
        val readings: List<SensorReadingEntity>,
    )

    private data class CookExtras(
        val targets: List<TargetEntity>,
        val results: List<CookResultEntity>,
        val photos: List<PhotoEntity>,
    )
}

data class ExportSnapshot(
    val cooks: List<CookEntity>,
    val dishes: List<DishEntity>,
    val ingredients: List<IngredientEntity>,
    val events: List<TimelineEventEntity>,
    val readings: List<SensorReadingEntity>,
    val targets: List<TargetEntity>,
    val results: List<CookResultEntity>,
    val devices: List<DeviceEntity>,
    val probes: List<ProbeEntity>,
    val photos: List<PhotoEntity>,
)

data class ImportSummary(val importedCooks: Int, val skippedCooks: Int, val importedPhotos: Int)

object CookStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
}
