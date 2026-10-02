package com.pittech

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PreparationPersistenceTest {
    @Test fun checklistFuelAndMaintenanceSurvivePortableRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context)); val prep = CookPreparationRepository(db)
            val profileId = prep.saveEquipment(EquipmentProfile("Backyard", "Pellets", "Oak", 1.0))
            val ingredients = listOf(IngredientDraft("Pepper", amountText = "2", amountUnit = "tbsp"))
            prep.saveCombo(PrepCombo("House rub", ingredients, 4.0, "servings"))
            repeat(2) { index ->
                val id = repo.startCook(NewCookDraft("Fuel test $index", smokerName = "Backyard", dishes = listOf(DishDraft("Ribs", "Pork", preparationItems = ingredients))))
                val dish = db.cookDao().getDishesForCook(id).single()
                repo.addTimelineEvent(id, dish.id, "meat_on", "Food on", null, 1_000_000)
                repo.addTimelineEvent(id, dish.id, "remove", "Removed", null, 4_600_000)
                prep.createChecklist(id)
                val list = CookPreparation.decodeChecklist(db.companionDao().get("checklist:$id")!!.payload)
                val eventCount = db.cookDao().getAllTimelineEvents().size
                prep.check(id, list.first().id, true)
                assertEquals(eventCount, db.cookDao().getAllTimelineEvents().size)
                repo.completeCook(id)
                prep.addFuel(FuelEntry("Backyard", 2.0 + index, id, System.currentTimeMillis(), "used"))
            }
            val summary = prep.summaries().single()
            assertEquals(2.0, summary.cookingHours, 0.001)
            assertEquals(2.0 to 3.0, summary.burnRangeKgPerHour)
            assertTrue(summary.maintenanceDue)
            prep.maintained(profileId)
            assertFalse(prep.summaries().single().maintenanceDue)
            val active = repo.startCook(NewCookDraft("Manual alert", smokerName = "Backyard", dishes = listOf(DishDraft("Flat", "Beef"))))
            val activeDish = db.cookDao().getDishesForCook(active).single().id
            CookAlertRepository(db).save(active, "Check finish", CookAlertRule(dishId = activeDish, targetF = 200.0))
            val book = CookPlaybook("Future meal", NewCookDraft("Future meal", dishes = listOf(DishDraft("Ribs", "Pork"))), emptyList())
            ServePlanRepository(db, CookCompanionRepository(db, PhotoStorage(context))).save(ServePlan(book, System.currentTimeMillis() + 86_400_000, "UTC", schedules = listOf(DishSchedule(0))))
            val cookOnly = repo.exportSnapshot(active)
            assertFalse(cookOnly.companionRecords.any { it.kind in setOf("upcoming", "prep_combo") })
            assertTrue(cookOnly.companionRecords.any { it.kind == "alert" })
            val zip = ByteArrayOutputStream(); PitTechDataTransfer(context, repo).writeZip(zip)
            val transfer = PitTechDataTransfer(context, CookRepository(restored, PhotoStorage(context)))
            transfer.import(transfer.previewImport(ByteArrayInputStream(zip.toByteArray())))
            val summaryAfter = CookPreparationRepository(restored).summaries().single()
            assertEquals(2.0, summaryAfter.profile.maintainedAtCookingHours, 0.001)
            assertEquals(2.0 to 3.0, summaryAfter.burnRangeKgPerHour)
            assertEquals(2, restored.companionDao().all().count { it.kind == "checklist" })
            assertEquals(1, restored.companionDao().all().count { it.kind == "prep_combo" })
            assertEquals(1, restored.companionDao().all().count { it.kind == "upcoming" })
            assertFalse(CookAlertEngine.decode(restored.companionDao().all().single { it.kind == "alert" }.payload).first.enabled)
        } finally { db.close(); restored.close() }
    }
}
