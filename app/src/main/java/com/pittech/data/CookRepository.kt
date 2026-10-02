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
            dao.observeReminders(cookId),
        ) { targets, results, photos, reminders -> CookExtras(targets, results, photos, reminders) }
        return combine(core, extras, database.recordingDao().observeRecording(cookId), dao.observeProbes(cookId), database.recordingDao().observeAssignments(cookId)) { first, second, recording, probes, assignments ->
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
                    reminders = second.reminders,
                    recording = recording,
                    probes = probes,
                    assignments = assignments,
                )
            }
        }
    }

    fun observeInsights(): Flow<InsightsSnapshot> = combine(combine(
        dao.observeCooks(),
        dao.observeAllSensorReadings(),
        dao.observeAllResults(),
        dao.observeConnectionGaps(),
    ) { cooks, readings, results, gaps -> InsightsSnapshot(cooks, readings, results, gaps) }, dao.observeAllIngredients()) { snapshot, ingredients -> snapshot.copy(ingredients = ingredients) }

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
            title = draft.title.trim().ifBlank { DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withZone(ZoneId.of(timeZoneId)).format(Instant.ofEpochMilli(now)) },
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
                val companion = CookCompanionRepository(database, photoStorage)
                companion.applyToCook(cookId, draft, dishes.map { it.id })
                ServePlanRepository(database, companion).attach(cookId, draft, dishes.map { it.id })
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
    ): TimelineEventEntity = database.withTransaction {
        require(title.isNotBlank()) { "Enter a title for this entry." }
        val now = System.currentTimeMillis()
        val event = TimelineEventEntity(
            id = UUID.randomUUID().toString(), cookId = cookId, dishId = dishId,
            eventType = eventType, title = title.trim(), details = details?.trim()?.ifBlank { null },
            occurredAtUtcMillis = occurredAtUtcMillis, recordedAtUtcMillis = now,
            timeZoneId = timeZoneId, source = "manual", createdAtUtcMillis = now, updatedAtUtcMillis = now,
            temperatureContextJson = temperatureContext(cookId, occurredAtUtcMillis, dishId),
        )
        dao.insertTimelineEvent(event)
        CookCompanionRepository(database, photoStorage).onEvent(event)
        event
    }

    suspend fun updateTimelineEvent(event: TimelineEventEntity) {
        require(event.title.isNotBlank()) { "Enter a title for this entry." }
        val previous = dao.getTimelineEvent(event.id)
        val context = if (previous == null || previous.occurredAtUtcMillis != event.occurredAtUtcMillis || previous.dishId != event.dishId)
            temperatureContext(event.cookId, event.occurredAtUtcMillis, event.dishId) else previous.temperatureContextJson
        dao.updateTimelineEvent(
            event.copy(
                title = event.title.trim(),
                details = event.details?.trim()?.ifBlank { null },
                updatedAtUtcMillis = System.currentTimeMillis(),
                temperatureContextJson = context,
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

    suspend fun createCookReminder(cookId: String, title: String, dueAtUtcMillis: Long): CookReminderEntity {
        require(dao.getCook(cookId) != null) { "This cook is no longer available." }
        require(title.isNotBlank()) { "Give the reminder a short title." }
        require(dueAtUtcMillis > System.currentTimeMillis()) { "Choose a time in the future." }
        require(dueAtUtcMillis <= System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000) { "Reminders can be scheduled up to 30 days ahead." }
        val reminder = CookReminderEntity(
            id = UUID.randomUUID().toString(),
            cookId = cookId,
            title = title.trim(),
            dueAtUtcMillis = dueAtUtcMillis,
            timeZoneId = ZoneId.systemDefault().id,
            createdAtUtcMillis = System.currentTimeMillis(),
        )
        dao.insertReminder(reminder)
        return reminder
    }

    suspend fun cancelCookReminder(reminderId: String): CookReminderEntity? {
        val reminder = dao.getReminder(reminderId) ?: return null
        if (reminder.status != CookReminderEntity.STATUS_PENDING) return reminder
        val cancelled = reminder.copy(status = CookReminderEntity.STATUS_CANCELLED)
        dao.updateReminder(cancelled)
        return cancelled
    }

    suspend fun snoozeCookReminder(reminderId: String, dueAtUtcMillis: Long): CookReminderEntity? {
        val reminder = dao.getReminder(reminderId) ?: return null
        if (reminder.status != CookReminderEntity.STATUS_PENDING) return reminder
        val snoozed = reminder.copy(dueAtUtcMillis = dueAtUtcMillis)
        dao.updateReminder(snoozed)
        return snoozed
    }

    suspend fun completeCookReminder(reminderId: String, note: String?): ReminderCheckIn? = database.withTransaction {
        val reminder = dao.getReminder(reminderId) ?: return@withTransaction null
        if (reminder.status != CookReminderEntity.STATUS_PENDING) return@withTransaction null
        val now = System.currentTimeMillis()
        val completed = reminder.copy(status = CookReminderEntity.STATUS_COMPLETED, completedAtUtcMillis = now)
        val event = TimelineEventEntity(
                id = UUID.randomUUID().toString(),
                cookId = reminder.cookId,
                eventType = "reminder_completed",
                title = "Check-in: ${reminder.title}",
                temperatureContextJson = temperatureContext(reminder.cookId, now, null),
                details = note?.trim()?.ifBlank { null } ?: "Reminder check-in completed.",
                occurredAtUtcMillis = now,
                recordedAtUtcMillis = now,
                timeZoneId = reminder.timeZoneId,
                source = "manual",
                createdAtUtcMillis = now,
                updatedAtUtcMillis = now,
            )
        dao.updateReminder(completed)
        dao.insertTimelineEvent(event)
        ReminderCheckIn(completed, event)
    }

    suspend fun addTimelineEventWithPhoto(
        cookId: String,
        dishId: String?,
        eventType: String,
        title: String,
        details: String?,
        occurredAtUtcMillis: Long,
        photoUri: String?,
        photoCaption: String?,
        usePhotoCaptureTime: Boolean = false,
    ): CookLogSaveResult {
        val event = addTimelineEvent(cookId, dishId, eventType, title, details, occurredAtUtcMillis)
        val attached = if (photoUri == null) true else try {
            attachPhotoToTimelineEvent(event.id, cookId, dishId, photoUri, photoCaption, usePhotoCaptureTime)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return CookLogSaveResult(dao.getTimelineEvent(event.id) ?: event, attached)
    }

    suspend fun attachPhotoToTimelineEvent(
        eventId: String,
        cookId: String,
        dishId: String?,
        uri: String,
        caption: String?,
        usePhotoCaptureTime: Boolean = false,
    ): PhotoEntity {
        val event = dao.getTimelineEvent(eventId) ?: error("This timeline entry is no longer available.")
        require(event.cookId == cookId) { "A photo can only be attached to an entry in the same cook." }
        require(dishId == null || dao.getDish(dishId)?.cookId == cookId) { "This dish is not part of the selected cook." }
        val copied = photoStorage.copyIntoLibrary(uri, cookId, dishId ?: event.dishId, System.currentTimeMillis(), eventId, caption)
        return try {
            val photo = copied.copy(temperatureContextJson = copied.capturedAtUtcMillis?.let { temperatureContext(cookId, it, copied.dishId) })
            database.withTransaction {
                dao.insertPhotos(listOf(photo))
                if (event.eventType == "photo" && usePhotoCaptureTime) {
                    // Default photo entries follow known capture time. Unknown gallery dates do
                    // not get today's temperatures. A user-selected entry time is preserved.
                    dao.updateTimelineEvent(event.copy(occurredAtUtcMillis = photo.capturedAtUtcMillis ?: event.occurredAtUtcMillis,
                        temperatureContextJson = photo.temperatureContextJson, updatedAtUtcMillis = System.currentTimeMillis()))
                }
            }
            photo
        } catch (failure: Throwable) {
            photoStorage.delete(copied.relativePath)
            throw failure
        }
    }

    suspend fun addReminderCheckInPhoto(eventId: String, cookId: String, uri: String, caption: String?): PhotoEntity =
        attachPhotoToTimelineEvent(eventId, cookId, null, uri, caption)

    suspend fun addCookPhoto(cookId: String, uri: String, caption: String?): PhotoEntity {
        val event = addTimelineEvent(cookId, null, "photo", caption?.takeIf { it.isNotBlank() } ?: "Photo added", null, System.currentTimeMillis())
        return attachPhotoToTimelineEvent(event.id, cookId, null, uri, caption, usePhotoCaptureTime = true)
    }

    suspend fun saveLearning(cookId: String, dishId: String?, keepDoing: String, changeNextTime: String) {
        val now = System.currentTimeMillis()
        listOf("keep_doing" to keepDoing, "change_next_time" to changeNextTime).forEach { (type, text) ->
            val id = "$cookId:${dishId ?: "cook"}:$type"
            if (text.isBlank()) dao.deleteResult(id) else dao.upsertResult(CookResultEntity(id, cookId, dishId, type, textValue = text.trim(), recordedAtUtcMillis = now))
        }
    }
    suspend fun setFavorite(cookId: String, favorite: Boolean) {
        val id = "$cookId:cook:favorite"
        if (!favorite) dao.deleteResult(id) else dao.upsertResult(CookResultEntity(id, cookId, null, "favorite", numericValue = 1.0, recordedAtUtcMillis = System.currentTimeMillis()))
    }
    suspend fun saveCookTags(cookId: String, method: String, tags: String) {
        val now = System.currentTimeMillis()
        listOf("method" to method, "tags" to tags).forEach { (type, text) ->
            val id = "$cookId:cook:$type"
            if (text.isBlank()) dao.deleteResult(id) else dao.upsertResult(CookResultEntity(id, cookId, null, type, textValue = text.trim(), recordedAtUtcMillis = now))
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

    suspend fun completeCook(cookId: String) = database.withTransaction {
        val plan = CookCompanionRepository(database, photoStorage).plan(cookId)
        if (plan != null) {
            val events = dao.getTimelineEventsForCook(cookId).map { com.pittech.domain.PlanEvent(it.id, com.pittech.domain.PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }
            require(plan.book.steps.none { s -> (plan.progress[s.id]?.status ?: "pending") == "pending" && s.stage in setOf("resting", "holding") &&
                s.dishIndex?.let { com.pittech.domain.CookPlanEngine.stage(events, plan.dishIds[it]) == s.stage } != false }) { "Rest or hold checks are still active. Complete or skip them in View plan before finishing." }
        }
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        if (cook.status == CookStatus.COMPLETED) return@withTransaction
        val now = System.currentTimeMillis()
        dao.updateCook(cook.copy(status = CookStatus.COMPLETED, endedAtUtcMillis = now, updatedAtUtcMillis = now))
        addTimelineEvent(cookId, null, "cook_finished", "Cook finished", null, now)
        CookRecordingRepository(database).stop(cookId, now)
    }

    suspend fun pauseCook(cookId: String) = database.withTransaction {
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        if (cook.status != CookStatus.ACTIVE) return@withTransaction
        val now = System.currentTimeMillis()
        dao.updateCook(cook.copy(status = CookStatus.PAUSED, updatedAtUtcMillis = now))
        addTimelineEvent(cookId, null, "cook_paused", "Cook paused", null, now)
        CookRecordingRepository(database).pause(cookId, "Cook paused · temperature recording paused.", now)
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
        val allRecords = database.companionDao().all().filter { it.kind != "monitor_status" }
        val scopedRecords = allRecords.filter { it.cookId in selectedCookIds }
        val bookIds = scopedRecords.filter { it.kind == "plan" }.mapNotNull { com.pittech.domain.CookPlanEngine.decode(it.payload).playbookId }.toSet()
        val smokers = allCooks.filter { it.id in selectedCookIds }.mapNotNull { it.smokerName }.toSet()
        val records = if (cookId == null) allRecords else scopedRecords + allRecords.filter {
            it.cookId == null && (it.id in bookIds || it.kind == "equipment" && smokers.any { name -> com.pittech.domain.CookPreparation.decodeEquipment(it.payload).name.equals(name, true) })
        }
        val recordIds = records.map { it.id }.toSet()
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
            reminders = dao.getAllReminders().filter { it.cookId in selectedCookIds },
            recordings = database.recordingDao().getAllRecordings().filter { it.cookId in selectedCookIds }
                .map { it.copy(controllerKey = "", status = CookRecordingEntity.STOPPED, message = "Restored history · attach a grill to record again.") },
            companionRecords = records,
            companionPhotos = database.companionDao().photos().filter { it.recordId in recordIds },
            assignments = database.recordingDao().getAllAssignments().filter { it.cookId in selectedCookIds },
        )
    }

    suspend fun importSnapshot(snapshot: ExportSnapshot, attachmentData: Map<String, ByteArray>): ImportSummary {
        require(snapshot.photos.map { it.id }.distinct().size == snapshot.photos.size) { "The backup contains duplicate photo IDs." }
        require((snapshot.photos.map { it.id } + snapshot.companionPhotos.map { it.id }).all { attachmentData[it]?.isNotEmpty() == true }) {
            "The backup is incomplete: one or more photo attachments are missing."
        }
        val existingCookIds = dao.getAllCooks().map { it.id }.toSet()
        val newCooks = snapshot.cooks.filterNot { it.id in existingCookIds }
        val newIds = newCooks.map { it.id }.toSet()
        val existingPhotoIds = dao.getAllPhotos().map { it.id }.toSet()
        val photos = snapshot.photos.filter { it.cookId in newIds && it.id !in existingPhotoIds }
        val restoredPhotos = mutableListOf<PhotoEntity>()
        val existingRecordIds = database.companionDao().all().map { it.id }.toSet()
        val newRecords = snapshot.companionRecords.filter { it.id !in existingRecordIds && (it.cookId == null || it.cookId in newIds) }
        val newRecordIds = newRecords.map { it.id }.toSet()
        val referencePhotos = snapshot.companionPhotos.filter { it.recordId in newRecordIds }
        val savedPhotoIds = existingPhotoIds + database.companionDao().photos().map { it.id }
        require(referencePhotos.none { it.id in savedPhotoIds } && photos.none { p -> database.companionDao().photos().any { it.id == p.id } }) { "An attachment ID conflicts with an existing photo. Restore into a separate backup first." }
        val writtenReferencePhotos = mutableListOf<CompanionPhoto>()
        try {
            photos.forEach { photo ->
                val bytes = attachmentData.getValue(photo.id)
                photoStorage.writeImported(photo.relativePath, bytes)
                restoredPhotos += photo
            }
            referencePhotos.forEach { photo ->
                photoStorage.writeImported(photo.relativePath, attachmentData.getValue(photo.id))
                writtenReferencePhotos += photo
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
                dao.insertRemindersIgnoringDuplicates(snapshot.reminders.filter { it.cookId in newIds })
                database.companionDao().restore(newRecords.map { r -> if (r.kind == "plan") r.copy(payload = org.json.JSONObject(r.payload).put("paused", true).toString()) else if (r.kind == "alert") { val rule = com.pittech.domain.CookAlertEngine.decode(r.payload).first; r.copy(payload = com.pittech.domain.CookAlertEngine.encode(rule.copy(enabled = false))) } else r })
                database.companionDao().putPhotos(referencePhotos)
                database.recordingDao().insertRecordings(snapshot.recordings.filter { it.cookId in newIds }
                    .map { it.copy(controllerKey = "", status = CookRecordingEntity.STOPPED, message = "Restored history · attach a grill to record again.") })
                database.recordingDao().insertAssignments(snapshot.assignments.filter { it.cookId in newIds })
            }
        } catch (failure: Throwable) {
            restoredPhotos.forEach { photo -> photoStorage.delete(photo.relativePath) }
            writtenReferencePhotos.forEach { photoStorage.delete(it.relativePath) }
            throw failure
        }
        return ImportSummary(newCooks.size, snapshot.cooks.size - newCooks.size, restoredPhotos.size)
    }

    suspend fun readPhoto(relativePath: String): ByteArray? = photoStorage.read(relativePath)

    internal suspend fun temperatureContext(cookId: String, time: Long, dishId: String?): String? {
        val recording = database.recordingDao().getRecording(cookId)
        val rows = dao.getReadingsForContext(cookId, time - 330_000L, time)
        // A known outage invalidates preceding cloud values even when a slower cadence
        // would otherwise consider their receipt recent. Retain this boundary for backdated logs.
        val lastGap = dao.getTimelineEventsForCook(cookId).filter {
            it.source == "controller_cloud" && it.eventType == "connection_gap" && it.occurredAtUtcMillis <= time
        }.maxOfOrNull { it.occurredAtUtcMillis }
        // Reattaching restored history creates a new local device identity. Older captures
        // retain their original context; new entries must wait for this attached grill.
        val applicable = if (recording != null && time >= recording.startedAtUtcMillis)
            rows.filter { it.source != "controller_cloud" || (it.sourceDeviceId == recording.deviceId &&
                (recording.samplingMode != com.pittech.devices.GrillSamplingMode.ON_LOG.key || time < recording.resumedAtUtcMillis ||
                    (it.samplingIntervalMillis == 0L && it.measuredAtUtcMillis >= recording.resumedAtUtcMillis))) }
        else rows
        val afterGap = applicable.filter { it.source != "controller_cloud" || lastGap == null || it.measuredAtUtcMillis > lastGap }
        return TemperatureContext.encode(TemperatureContext.select(afterGap, time, dishId))
    }

    suspend fun getPendingCookReminders(): List<CookReminderEntity> = dao.getPendingReminders()

    suspend fun cancelPendingCookReminders(cookId: String): List<CookReminderEntity> = database.withTransaction {
        val pending = dao.getPendingRemindersForCook(cookId)
        pending.forEach { dao.updateReminder(it.copy(status = CookReminderEntity.STATUS_CANCELLED)) }
        pending
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
        val reminders: List<CookReminderEntity>,
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
    val reminders: List<CookReminderEntity> = emptyList(),
    val recordings: List<CookRecordingEntity> = emptyList(),
    val assignments: List<ProbeAssignmentEntity> = emptyList(),
    val companionRecords: List<CompanionRecord> = emptyList(),
    val companionPhotos: List<CompanionPhoto> = emptyList(),
)

data class CookLogSaveResult(val event: TimelineEventEntity, val photoAttached: Boolean)

data class ReminderCheckIn(val reminder: CookReminderEntity, val event: TimelineEventEntity)

data class ImportSummary(val importedCooks: Int, val skippedCooks: Int, val importedPhotos: Int)

object CookStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
}
