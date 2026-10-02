package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.*
import java.util.UUID

class ServePlanRepository(private val db: PitTechDatabase, private val companion: CookCompanionRepository) {
    suspend fun save(plan: ServePlan, id: String? = null): String {
        ServeTimePlanner.validate(plan)
        require(plan.startedCookId == null) { "Edit the active cook's plan instead." }
        require(plan.serveAt > System.currentTimeMillis()) { "Choose a future serving time." }
        val key = id ?: UUID.randomUUID().toString()
        val old = db.companionDao().get(key); val now = System.currentTimeMillis()
        db.companionDao().put(CompanionRecord(key, "upcoming", title = plan.book.name, payload = ServeTimePlanner.encode(plan), createdAtUtcMillis = old?.createdAtUtcMillis ?: now, updatedAtUtcMillis = now))
        return key
    }
    suspend fun evidence(): List<DurationEvidence> {
        val cooks = db.cookDao().getAllCooks().filter { it.status == CookStatus.COMPLETED }.associateBy { it.id }
        val events = db.cookDao().getAllTimelineEvents().groupBy { it.cookId }
        return db.cookDao().getAllDishes().mapNotNull { dish ->
            val cook = cooks[dish.cookId] ?: return@mapNotNull null
            val scoped = events[dish.cookId].orEmpty().filter { it.dishId == dish.id || it.dishId == null }
            val on = scoped.filter { it.eventType == "meat_on" }
            val remove = scoped.filter { it.eventType == "remove" }
            // Ambiguous/multiple Food on or Remove events are not treated as a clean sample.
            if (on.size != 1 || remove.size != 1 || remove.single().occurredAtUtcMillis <= on.single().occurredAtUtcMillis) return@mapNotNull null
            val duration = (remove.single().occurredAtUtcMillis - on.single().occurredAtUtcMillis) / 60_000
            if (duration !in 1..100_800) return@mapNotNull null
            DurationEvidence(dish.cut, dish.foodType, cook.smokerName, ServeTimePlanner.weightKg(dish.weightValue, dish.weightUnit ?: "lb"), dish.startingCondition, dish.boneIn,
                cook.initialSetpointValue?.let { CookPlanEngine.fahrenheit(it, cook.initialSetpointUnit ?: "°F") }, duration, cook.id, scoped.any { it.eventType == "wrap" })
        }
    }
    suspend fun attach(cookId: String, draft: NewCookDraft, orderedDishIds: List<String>? = null) = db.withTransaction {
        val id = draft.scheduledPlanId ?: return@withTransaction
        val record = db.companionDao().get(id) ?: error("This scheduled cook is no longer saved.")
        val plan = ServeTimePlanner.decode(record.payload)
        require(plan.startedCookId == null) { "This scheduled cook has already started." }
        require(draft.dishes.size == plan.book.draft.dishes.size) { "Update the serving plan when changing its dish list, then start the cook." }
        companion.applySnapshot(cookId, draft, plan.book, null, orderedDishIds)
        val started = plan.copy(startedCookId = cookId, book = plan.book.copy(draft = draft))
        db.companionDao().put(record.copy(payload = ServeTimePlanner.encode(started), updatedAtUtcMillis = System.currentTimeMillis()))
        val now = System.currentTimeMillis()
        db.companionDao().put(CompanionRecord("serve:$cookId", "serve_goal", cookId, record.title, ServeTimePlanner.encode(started), now, now))
    }
}
