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
            val scoped = notePlanEvents(events[dish.cookId].orEmpty()).filter { it.dishId == dish.id || it.dishId == null }
            val on = scoped.filter { it.action == "food_on" }
            val remove = scoped.filter { it.action == "remove" }
            // Ambiguous/multiple Food on or Remove events are not treated as a clean sample.
            if (on.size != 1 || remove.size != 1 || remove.single().at <= on.single().at) return@mapNotNull null
            val duration = (remove.single().at - on.single().at) / 60_000
            if (duration !in 1..100_800) return@mapNotNull null
            DurationEvidence(dish.cut, dish.foodType, cook.smokerName, ServeTimePlanner.weightKg(dish.weightValue, dish.weightUnit ?: "lb"), dish.startingCondition, dish.boneIn,
                cook.initialSetpointValue?.let { CookPlanEngine.fahrenheit(it, cook.initialSetpointUnit ?: "°F") }, duration, cook.id, scoped.any { it.action == "wrap" })
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
        armDerivedAlerts(cookId, started, orderedDishIds, now)
    }

    /**
     * Flowing §4.4 / 3.5: starting the plan arms its alerts — the lanes'
     * derived food-on / wrap / off alerts become reminder rules on the
     * cook, derived from the plan, never re-entered by hand. Timed alerts
     * arm as clock reminders under deterministic ids (a re-attach would
     * replace, never duplicate). A condition-based alert (a wrap at a
     * temperature) has no clock to arm; it rides the plan's guidance
     * steps, which [CookCompanionRepository.applySnapshot] just attached.
     * The reminder engine receives from here — reconcile evaluates and
     * delivers like any other rule.
     */
    private suspend fun armDerivedAlerts(cookId: String, plan: ServePlan, orderedDishIds: List<String>?, now: Long) {
        val timeline = PlanTimeline.derive(plan)
        for (alert in timeline.alerts) {
            val at = alert.atUtcMillis ?: continue
            val trigger = ReminderTrigger.Clock(at)
            val id = "plan:$cookId:${alert.dishIndex}:${alert.kind.name.lowercase()}"
            db.reminderDao().putRule(ReminderRule(
                id = id,
                cookId = cookId,
                dishId = orderedDishIds?.getOrNull(alert.dishIndex),
                title = alert.label,
                kind = ReminderTriggerCodec.kind(trigger),
                triggerConfig = ReminderTriggerCodec.encode(trigger),
                sourceRecipeRevisionId = null,
                sourceMilestoneId = null,
                enabled = true,
                status = "pending",
                createdAtUtcMillis = now,
                updatedAtUtcMillis = now,
            ))
            db.reminderDao().putOccurrence(ReminderOccurrence("state:$id", id, 0, "pending"))
        }
    }
}
