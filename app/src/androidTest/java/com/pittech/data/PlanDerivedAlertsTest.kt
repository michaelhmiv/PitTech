package com.pittech.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flowing §6 gate evidence for Slice 4 (3.5): starting a planned cook
 * arms the plan's derived alerts — the PlanTimeline food-on / wrap / off
 * alerts land as clock reminder rules on the cook through the real
 * start path (ServePlanRepository.attach inside CookRepository.startCook),
 * at exactly the derived times. A temperature-conditioned wrap has no
 * clock to arm and must NOT become a timed rule; it rides the plan's
 * guidance steps instead.
 */
@RunWith(AndroidJUnit4::class)
class PlanDerivedAlertsTest {
    private fun plan(wrapTrigger: String): ServePlan {
        val draft = NewCookDraft(
            title = "Saturday",
            smokerName = "Austin XL",
            setpointText = "250",
            dishes = listOf(
                DishDraft("Brisket", "Beef", "Packer brisket"),
                DishDraft("Ribs", "Pork", "Spare ribs"),
            ),
        )
        val book = CookPlaybook(
            name = "Saturday",
            draft = draft,
            steps = listOf(PlaybookStep(dishIndex = 0, action = "wrap", title = "Wrap the brisket",
                trigger = wrapTrigger, minutes = 360, temperatureF = if (wrapTrigger == "temperature") 165.0 else null)),
        )
        return ServePlan(
            book = book,
            serveAt = System.currentTimeMillis() + 2 * 86_400_000L,
            zoneId = "America/New_York",
            schedules = listOf(
                DishSchedule(dishIndex = 0, cookMinMinutes = 600, cookMaxMinutes = 780, restMinutes = 60, pitF = 250.0),
                DishSchedule(dishIndex = 1, cookMinMinutes = 240, cookMaxMinutes = 280, restMinutes = 15, pitF = 250.0),
            ),
        )
    }

    private suspend fun startPlannedCook(db: PitTechDatabase, context: android.content.Context, plan: ServePlan): String {
        val companion = CookCompanionRepository(db, PhotoStorage(context))
        val planId = ServePlanRepository(db, companion).save(plan)
        return CookRepository(db, PhotoStorage(context)).startCook(
            NewCookDraft(title = plan.book.name, smokerName = "Austin XL", dishes = plan.book.draft.dishes, scheduledPlanId = planId)
        )
    }

    @Test fun startingAPlanArmsItsDerivedAlertsAsClockReminders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val plan = plan(wrapTrigger = "elapsed")
            val cookId = startPlannedCook(db, context, plan)
            val timeline = PlanTimeline.derive(plan)
            val rules = db.reminderDao().rules().filter { it.cookId == cookId }
            val timed = timeline.alerts.filter { it.atUtcMillis != null }
            assertEquals("one rule per timed derived alert", timed.size, rules.size)
            for (alert in timed) {
                val id = "plan:$cookId:${alert.dishIndex}:${alert.kind.name.lowercase()}"
                val found = rules.singleOrNull { it.id == id }
                assertNotNull("missing derived rule $id", found)
                val rule = found!!
                assertEquals(alert.label, rule.title)
                assertTrue(rule.enabled)
                val trigger = ReminderTriggerCodec.decode(rule.triggerConfig)
                assertEquals("rule $id arms at the derived time", ReminderTrigger.Clock(alert.atUtcMillis!!), trigger)
                assertNotNull("state occurrence row exists", db.reminderDao().history(id).singleOrNull { it.sequence == 0 })
            }
            // Dish linkage: rules point at the cook's real dishes, in plan order.
            val dishes = db.cookDao().getDishesForCook(cookId)
            assertEquals(dishes[0].id, rules.single { it.id.endsWith("0:food_on") }.dishId)
            assertEquals(dishes[1].id, rules.single { it.id.endsWith("1:food_on") }.dishId)
        } finally { db.close() }
    }

    @Test fun aTemperatureWrapArmsNoTimedRule(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val plan = plan(wrapTrigger = "temperature")
            val cookId = startPlannedCook(db, context, plan)
            val rules = db.reminderDao().rules().filter { it.cookId == cookId }
            assertTrue("no wrap rule for a condition-based alert", rules.none { it.id.contains("wrap") })
            // Food-on and off alerts are timed and still arm (2 dishes × 2).
            assertEquals(4, rules.size)
        } finally { db.close() }
    }
}
