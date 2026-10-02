package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CookCompanionRepository(private val database: PitTechDatabase, private val photoStorage: PhotoStorage) {
    private val dao = database.companionDao()
    fun observeAll() = dao.observeAll()
    fun observeCook(cookId: String) = dao.observeCook(cookId)
    suspend fun get(id: String) = dao.get(id)
    suspend fun photos(id: String) = dao.photosFor(id)

    suspend fun suggest(cookId: String): CookPlaybook {
        val data = database.cookDao().observeCook(cookId).first() ?: error("This cook could not be found.")
        val ingredients = database.cookDao().getIngredientsForCook(cookId)
        val draft = NewCookDraft(title = data.cook.title, smokerName = data.cook.smokerName.orEmpty(),
            notes = data.cook.notes.orEmpty(),
            setpointText = data.cook.initialSetpointValue?.toString().orEmpty(), setpointUnit = data.cook.initialSetpointUnit ?: "°F",
            fuelType = data.cook.fuelType.orEmpty(), woodOrPelletBlend = data.cook.woodOrPelletBlend.orEmpty(),
            dishes = data.dishes.map { d -> DishDraft(d.name, d.foodType, d.cut.orEmpty(), d.weightValue?.toString().orEmpty(), d.weightUnit ?: "lb", d.startingCondition, d.boneIn, d.placement.orEmpty(), d.gradeOrSource.orEmpty(), d.thicknessNotes.orEmpty(), d.prepNotes.orEmpty(), ingredients.filter { it.dishId == d.id }.map { IngredientDraft(it.name, it.stage, it.brand.orEmpty(), it.amountValue?.toString().orEmpty(), it.amountUnit ?: "tbsp") }) })
        val events = database.cookDao().getTimelineEventsForCook(cookId).sortedBy { it.occurredAtUtcMillis }
        val steps = events.filter { PlaybookCodec.action(it.eventType) in PlaybookCodec.actions }.map { e ->
            val index = e.dishId?.let { id -> data.dishes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            val foodOn = events.firstOrNull { PlaybookCodec.action(it.eventType) == "food_on" && (it.dishId == e.dishId || it.dishId == null) }
            val minutes = foodOn?.let { (e.occurredAtUtcMillis - it.occurredAtUtcMillis).coerceAtLeast(0) / 60_000L } ?: 0L
            val preparation = foodOn != null && e.occurredAtUtcMillis < foodOn.occurredAtUtcMillis
            // Times suggest a check. Notes and photos can help decide whether the food is ready.
            PlaybookStep(dishIndex = index, action = PlaybookCodec.action(e.eventType), title = e.title, instructions = e.details.orEmpty(), trigger = if (PlaybookCodec.action(e.eventType) == "food_on" || foodOn == null || preparation) "manual" else "elapsed", minutes = minutes,
                stage = if (preparation) "prep" else if (PlaybookCodec.action(e.eventType) in setOf("rest_start", "hold_start", "dish_done")) "resting" else "cooking", sourceObservations = TemperatureContext.summary(e.temperatureContextJson))
        }
        val targets = database.cookDao().getAllTargets().filter { it.cookId == cookId }.map { t -> PlaybookTarget(t.dishId?.let { id -> data.dishes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }, t.targetType, t.value, t.unit, t.explanation) }
        val results = database.cookDao().getAllResults().filter { it.cookId == cookId }
        return CookPlaybook(data.cook.title, draft, steps, targets, sourceCookId = cookId, keepDoing = results.filter { it.resultType == "keep_doing" }.mapNotNull { it.textValue }.joinToString("\n"), changeNextTime = results.filter { it.resultType in setOf("change_next_time", "result_notes") }.mapNotNull { it.textValue }.distinct().joinToString("\n"))
    }

    /** Saving an edit creates a revision. A cook holds its own snapshot, never a live mutable recipe. */
    suspend fun save(book: CookPlaybook, previousId: String? = null, includePhotos: Boolean = false): String {
        val previous = previousId?.let { dao.get(it)?.let { r -> PlaybookCodec.decode(r.payload) } }
        val saved = book.copy(name = book.name.trim(), revision = (previous?.revision ?: 0) + 1, previousRevisionId = previousId)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val copies = mutableListOf<CompanionPhoto>()
        try {
            if (includePhotos && book.sourceCookId != null) {
                val dishes = database.cookDao().getDishesForCook(book.sourceCookId)
                val events = database.cookDao().getTimelineEventsForCook(book.sourceCookId).associateBy { it.id }
                database.cookDao().getPhotosForCook(book.sourceCookId).filter { events[it.eventId]?.eventType?.let(PlaybookCodec::action) in PlaybookCodec.actions }.take(12).forEach { photo ->
                    val newId = UUID.randomUUID().toString()
                    val extension = photo.relativePath.substringAfterLast('.').filter(Char::isLetterOrDigit).take(8).ifBlank { "jpg" }
                    val path = "photos/$newId.$extension"
                    val bytes = photoStorage.read(photo.relativePath) ?: error("A reference photo is unavailable.")
                    photoStorage.writeImported(path, bytes)
                    copies += CompanionPhoto(newId, id, path, photo.mimeType, photo.caption ?: events[photo.eventId]?.title.orEmpty(), photo.dishId?.let { key -> dishes.indexOfFirst { it.id == key }.takeIf { it >= 0 } }, events[photo.eventId]?.eventType)
                }
            }
            if (previousId != null) {
                dao.photosFor(previousId).forEach { photo ->
                    val newId = UUID.randomUUID().toString()
                    val path = "photos/$newId.${photo.relativePath.substringAfterLast('.')}"
                    photoStorage.writeImported(path, photoStorage.read(photo.relativePath) ?: error("A reference photo is unavailable."))
                    copies += photo.copy(id = newId, recordId = id, relativePath = path)
                }
            }
            database.withTransaction {
                dao.put(CompanionRecord(id, "playbook", title = saved.name, payload = PlaybookCodec.encode(saved), createdAtUtcMillis = now, updatedAtUtcMillis = now))
                dao.putPhotos(copies)
            }
        } catch (failure: Throwable) { copies.forEach { photoStorage.delete(it.relativePath) }; throw failure }
        return id
    }

    suspend fun applyToCook(cookId: String, draft: NewCookDraft, orderedDishIds: List<String>? = null) {
        val id = draft.playbookId ?: return
        val record = dao.get(id) ?: error("This playbook could not be found.")
        val book = PlaybookCodec.decode(record.payload)
        applySnapshot(cookId, draft, book, id, orderedDishIds)
    }

    suspend fun applySnapshot(cookId: String, draft: NewCookDraft, book: CookPlaybook, id: String?, orderedDishIds: List<String>? = null) {
        val saved = database.cookDao().getDishesForCook(cookId)
        val dishes = orderedDishIds?.map { key -> saved.firstOrNull { it.id == key } ?: error("A dish in this setup could not be found.") } ?: saved
        // Capture draft order explicitly; database relationship queries need not preserve insertion order.
        if (!draft.followPlaybook && dishes.size != book.draft.dishes.size) return
        require(dishes.size == book.draft.dishes.size) { "Keep the playbook dishes when following its steps, or choose Setup only." }
        val now = System.currentTimeMillis()
        if (draft.followPlaybook) require(book.steps.none { it.trigger == "clock" && (it.clockAtUtcMillis ?: 0) < now }) { "Review clock reminders for this cook and choose new dates in Adjust playbook steps." }
        database.withTransaction {
            book.targets.forEach { t -> database.cookDao().insertTarget(TargetEntity(UUID.randomUUID().toString(), cookId, t.dishIndex?.let { dishes[it].id }, t.type, t.value, t.unit, if (t.dishIndex == null) "cook" else "dish", t.explanation, createdAtUtcMillis = now)) }
            if (draft.followPlaybook) {
                val payload = JSONObject(PlaybookCodec.encode(book.copy(draft = draft.copy(playbookId = null))))
                    .put("dishIds", JSONArray(dishes.map { it.id })).put("playbookId", id).put("paused", false)
                dao.put(CompanionRecord("plan:$cookId", "plan", cookId, book.name, payload.toString(), now, now))
            }
        }
    }

    suspend fun saveReference(cookId: String, reference: CookReference) {
        require(database.cookDao().getDish(reference.dishId)?.cookId == cookId && database.cookDao().getDish(reference.sourceDishId)?.cookId == reference.sourceCookId) { "Choose dishes in the selected cooks." }
        val now = System.currentTimeMillis()
        dao.put(CompanionRecord("reference:$cookId:${reference.dishId}", "reference", cookId, "Reference cook", CookReferenceCodec.encode(reference), now, now))
    }
    suspend fun beginPlan(cookId: String) = database.withTransaction {
        if (plan(cookId) != null) return@withTransaction
        val book = suggest(cookId).copy(steps = emptyList())
        val ids = database.cookDao().getDishesForCook(cookId).map { it.id }
        val now = System.currentTimeMillis()
        dao.put(CompanionRecord("plan:$cookId", "plan", cookId, book.name, CookPlanEngine.encode(CookPlan(book, ids, null)), now, now))
    }
    suspend fun detachDish(cookId: String, dishId: String) {
        val plan = plan(cookId)
        val index = plan?.dishIds?.indexOf(dishId)?.takeIf { it >= 0 }
        if (plan != null && index != null) {
            val book = plan.book.copy(draft = plan.book.draft.copy(dishes = plan.book.draft.dishes.filterIndexed { i, _ -> i != index }),
                steps = plan.book.steps.filterNot { it.dishIndex == index }.map { s -> s.copy(dishIndex = s.dishIndex?.let { if (it > index) it - 1 else it }) },
                targets = plan.book.targets.filterNot { it.dishIndex == index }.map { t -> t.copy(dishIndex = t.dishIndex?.let { if (it > index) it - 1 else it }) })
            storePlan(cookId, plan.copy(book = book, dishIds = plan.dishIds.filterNot { it == dishId }, playbookId = null, progress = plan.progress.filterKeys { id -> book.steps.any { it.id == id } }))
            dao.get("serve:$cookId")?.let { record ->
                if (book.draft.dishes.isEmpty()) dao.delete(record.id)
                else { val goal = ServeTimePlanner.decode(record.payload); dao.put(record.copy(payload = ServeTimePlanner.encode(goal.copy(book = book, schedules = goal.schedules.filterNot { it.dishIndex == index }.map { it.copy(dishIndex = if (it.dishIndex > index) it.dishIndex - 1 else it.dishIndex) })))) }
            }
        }
        dao.forCook(cookId).filter { r -> r.kind == "alert" && CookAlertEngine.decode(r.payload).first.dishId == dishId || r.kind == "reference" && CookReferenceCodec.decode(r.payload).dishId == dishId }.forEach { dao.delete(it.id) }
    }
    suspend fun stageAction(cookId: String, dishId: String, action: String) = database.withTransaction {
        require(action in setOf("food_on", "rest_start", "hold_start", "dish_done")) { "Choose a dish stage." }
        val cook = database.cookDao().getCook(cookId) ?: return@withTransaction
        require(cook.status != CookStatus.COMPLETED) { "This cook is finished." }
        require(database.cookDao().getDish(dishId)?.cookId == cookId) { "Dish does not belong to this cook." }
        val now = System.currentTimeMillis()
        val event = TimelineEventEntity(UUID.randomUUID().toString(), cookId, dishId, PlaybookCodec.eventType(action), PlaybookCodec.actions.getValue(action),
            occurredAtUtcMillis = now, recordedAtUtcMillis = now, timeZoneId = java.time.ZoneId.systemDefault().id, source = "manual", createdAtUtcMillis = now, updatedAtUtcMillis = now)
        database.cookDao().insertTimelineEvent(event)
        onEvent(event)
    }
    private fun List<TimelineEventEntity>.planEvents() = map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }
    suspend fun plan(cookId: String): CookPlan? = dao.get("plan:$cookId")?.let { CookPlanEngine.decode(it.payload) }
    private suspend fun storePlan(cookId: String, plan: CookPlan) {
        val record = dao.get("plan:$cookId") ?: return
        dao.put(record.copy(payload = CookPlanEngine.encode(plan), updatedAtUtcMillis = System.currentTimeMillis()))
    }
    suspend fun completeStep(cookId: String, stepId: String, occurrence: Int, at: Long = System.currentTimeMillis()) = database.withTransaction {
        val plan = plan(cookId) ?: return@withTransaction
        val cook = database.cookDao().getCook(cookId) ?: return@withTransaction
        if (cook.status == CookStatus.COMPLETED) return@withTransaction
        val step = plan.book.steps.firstOrNull { it.id == stepId } ?: return@withTransaction
        val progress = plan.progress[step.id] ?: StepProgress()
        if (progress.status != "pending" || progress.occurrence != occurrence) return@withTransaction
        val now = System.currentTimeMillis()
        val event = TimelineEventEntity(UUID.randomUUID().toString(), cookId, step.dishIndex?.let { plan.dishIds[it] }, PlaybookCodec.eventType(step.action), step.title,
            step.instructions.ifBlank { null }, at, now, java.time.ZoneId.systemDefault().id, "manual", createdAtUtcMillis = now, updatedAtUtcMillis = now)
        database.cookDao().insertTimelineEvent(event)
        storePlan(cookId, CookPlanEngine.satisfy(plan, step, PlanEvent(event.id, step.action, event.dishId, at)))
    }
    /** The existing quick actions satisfy a matching planned occurrence in the same transaction. */
    suspend fun onEvent(event: TimelineEventEntity) {
        val plan = plan(event.cookId) ?: return
        val action = PlaybookCodec.action(event.eventType)
        val matches = plan.book.steps.filter { s ->
            s.action == action && (s.dishIndex?.let { plan.dishIds[it] } == event.dishId || event.dishId == null || s.dishIndex == null && plan.dishIds.size == 1) &&
                (plan.progress[s.id]?.status ?: "pending") == "pending" && event.id !in plan.progress[s.id]?.eventIds.orEmpty()
        }.distinctBy { it.dishIndex }
        if (matches.isEmpty()) return
        val updated = matches.fold(plan) { current, step -> CookPlanEngine.satisfy(current, step, PlanEvent(event.id, action, event.dishId, event.occurredAtUtcMillis)) }
        storePlan(event.cookId, updated)
    }
    suspend fun updateStep(cookId: String, step: PlaybookStep) = database.withTransaction {
        val plan = plan(cookId) ?: return@withTransaction
        val progress = plan.progress[step.id]?.let { if (it.status == "pending") it.copy(notifiedOccurrence = null, snoozedUntil = null) else it }
        val updated = plan.copy(book = plan.book.copy(steps = if (plan.book.steps.any { it.id == step.id }) plan.book.steps.map { if (it.id == step.id) step else it } else plan.book.steps + step), progress = if (progress == null) plan.progress else plan.progress + (step.id to progress))
        PlaybookCodec.validate(updated.book)
        storePlan(cookId, updated)
    }
    suspend fun setPaused(cookId: String, paused: Boolean) = database.withTransaction { plan(cookId)?.let { storePlan(cookId, it.copy(paused = paused)) } }
    suspend fun snooze(cookId: String, stepId: String, occurrence: Int, until: Long) = database.withTransaction {
        val plan = plan(cookId) ?: return@withTransaction
        val p = plan.progress[stepId] ?: StepProgress()
        if (p.status == "pending" && p.occurrence == occurrence) storePlan(cookId, plan.copy(progress = plan.progress + (stepId to p.copy(snoozedUntil = until, notifiedOccurrence = null))))
    }
    suspend fun skip(cookId: String, stepId: String, useNowAsAnchor: Boolean = false) = database.withTransaction {
        val plan = plan(cookId) ?: return@withTransaction
        val p = plan.progress[stepId] ?: StepProgress()
        if (p.status != "pending") return@withTransaction
        // Skip is plan progress, never a physical timeline event. The optional anchor is explicit.
        storePlan(cookId, plan.copy(progress = plan.progress + (stepId to p.copy(status = "skipped", skippedAnchorAt = if (useNowAsAnchor) System.currentTimeMillis() else null))))
    }
    suspend fun markNotified(cookId: String, stepId: String, occurrence: Int): Boolean = database.withTransaction {
        val plan = plan(cookId) ?: return@withTransaction false
        val p = plan.progress[stepId] ?: StepProgress()
        if (p.status != "pending" || p.occurrence != occurrence || p.notifiedOccurrence == occurrence) return@withTransaction false
        storePlan(cookId, plan.copy(progress = plan.progress + (stepId to p.copy(notifiedOccurrence = occurrence))))
        true
    }
    suspend fun reconcileAll(context: android.content.Context) {
        dao.all().filter { it.kind == "plan" }.forEach { record -> reconcile(context, record.cookId ?: return@forEach) }
    }
    suspend fun reconcile(context: android.content.Context, cookId: String) {
        val plan = plan(cookId) ?: return
        val cook = database.cookDao().getCook(cookId) ?: return
        val events = database.cookDao().getTimelineEventsForCook(cookId).planEvents()
        val readings = database.cookDao().observeSensorReadings(cookId).first().map { PlanReading(it.dishId, CookPlanEngine.fahrenheit(it.value, it.unit), it.measuredAtUtcMillis, it.qualityStatus == "valid", it.measurementType, it.probeName) }
        val evaluated = CookPlanEngine.evaluate(plan, events, readings, System.currentTimeMillis())
        // One pending wake per step. Missed recurring checks collapse to one current occurrence.
        evaluated.forEach { e ->
            if (plan.paused || cook.status == CookStatus.COMPLETED || e.progress.status != "pending") com.pittech.CookGuidanceNotifications.cancel(context, cookId, e.step.id)
            else {
                if (e.ready && e.step.trigger != "manual" && com.pittech.CookGuidanceNotifications.canNotify(context) && markNotified(cookId, e.step.id, e.progress.occurrence)) com.pittech.CookGuidanceNotifications.show(context, cookId, e)
                e.dueAt?.takeIf { it > System.currentTimeMillis() }?.let {
                    com.pittech.CookGuidanceNotifications.cancel(context, cookId, e.step.id)
                    if (e.progress.notifiedOccurrence != null) database.withTransaction {
                        this@CookCompanionRepository.plan(cookId)?.let { current -> current.progress[e.step.id]?.let { p -> storePlan(cookId, current.copy(progress = current.progress + (e.step.id to p.copy(notifiedOccurrence = null)))) } }
                    }
                    com.pittech.CookGuidanceNotifications.schedule(context, cookId, e.step.id, it, calendar = e.step.trigger == "clock")
                }
                if (e.waitingFor != null) com.pittech.CookGuidanceNotifications.cancel(context, cookId, e.step.id)
                else if (e.dueAt == null) com.pittech.CookGuidanceNotifications.cancelAlarm(context, cookId, e.step.id)
            }
        }
    }

    suspend fun delete(id: String) {
        val record = dao.get(id) ?: return
        require(record.cookId == null) { "This is part of a cook record." }
        val photos = dao.photosFor(id)
        database.withTransaction { dao.delete(id) }
        photos.forEach { photoStorage.delete(it.relativePath) }
    }
}
