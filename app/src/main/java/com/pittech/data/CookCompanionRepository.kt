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
            setpointText = data.cook.initialSetpointValue?.toString().orEmpty(), setpointUnit = data.cook.initialSetpointUnit ?: "°F",
            fuelType = data.cook.fuelType.orEmpty(), woodOrPelletBlend = data.cook.woodOrPelletBlend.orEmpty(),
            dishes = data.dishes.map { d -> DishDraft(d.name, d.foodType, d.cut.orEmpty(), d.weightValue?.toString().orEmpty(), d.weightUnit ?: "lb", d.startingCondition, d.boneIn, d.placement.orEmpty(), d.gradeOrSource.orEmpty(), d.thicknessNotes.orEmpty(), d.prepNotes.orEmpty(), ingredients.filter { it.dishId == d.id }.map { IngredientDraft(it.name, it.stage, it.brand.orEmpty(), it.amountValue?.toString().orEmpty(), it.amountUnit ?: "tbsp") }) })
        val events = database.cookDao().getTimelineEventsForCook(cookId).sortedBy { it.occurredAtUtcMillis }
        val steps = events.filter { PlaybookCodec.action(it.eventType) in PlaybookCodec.actions }.map { e ->
            val index = e.dishId?.let { id -> data.dishes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            val foodOn = events.firstOrNull { PlaybookCodec.action(it.eventType) == "food_on" && (it.dishId == e.dishId || it.dishId == null) }
            val minutes = foodOn?.let { (e.occurredAtUtcMillis - it.occurredAtUtcMillis).coerceAtLeast(0) / 60_000L } ?: 0L
            // Times suggest a check. Notes and photos can help decide whether the food is ready.
            PlaybookStep(dishIndex = index, action = PlaybookCodec.action(e.eventType), title = e.title, instructions = e.details.orEmpty(), trigger = if (PlaybookCodec.action(e.eventType) == "food_on" || foodOn == null) "manual" else "elapsed", minutes = minutes,
                stage = if (PlaybookCodec.action(e.eventType) in setOf("rest_start", "hold_start", "dish_done")) "resting" else "cooking")
        }
        val targets = database.cookDao().getAllTargets().filter { it.cookId == cookId }.map { t -> PlaybookTarget(t.dishId?.let { id -> data.dishes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }, t.targetType, t.value, t.unit, t.explanation) }
        return CookPlaybook(data.cook.title, draft, steps, targets, sourceCookId = cookId)
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

    suspend fun applyToCook(cookId: String, draft: NewCookDraft) {
        val id = draft.playbookId ?: return
        val record = dao.get(id) ?: error("This playbook could not be found.")
        val book = PlaybookCodec.decode(record.payload)
        val dishes = database.cookDao().getDishesForCook(cookId)
        // Dish order is explicit in the preview and preserved when the draft is saved.
        require(dishes.size == book.draft.dishes.size) { "Keep the playbook dishes when following its steps, or choose Setup only." }
        val now = System.currentTimeMillis()
        database.withTransaction {
            book.targets.forEach { t -> database.cookDao().insertTarget(TargetEntity(UUID.randomUUID().toString(), cookId, t.dishIndex?.let { dishes[it].id }, t.type, t.value, t.unit, if (t.dishIndex == null) "cook" else "dish", t.explanation, createdAtUtcMillis = now)) }
            if (draft.followPlaybook) {
                val payload = JSONObject(PlaybookCodec.encode(book.copy(draft = draft.copy(playbookId = null))))
                    .put("dishIds", JSONArray(dishes.map { it.id })).put("playbookId", id).put("paused", false)
                dao.put(CompanionRecord("plan:$cookId", "plan", cookId, book.name, payload.toString(), now, now))
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
