package com.pittech.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.pittech.domain.*
import com.pittech.ui.TemperaturePlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RecipeGuideIntegrationTest {
    @Test fun threePinnedGuidesPersistScopeAndNeverChangeActualActionsOrReminders(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val other = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context)); val saved = mutableListOf<SavedRecipe>()
            val now = System.currentTimeMillis(); val sourceAnchor = now - 7200000
            for (name in listOf("Chicken", "Mac", "Pork")) {
                val source = repo.startCook(NewCookDraft(name, dishes = listOf(DishDraft(name, "Other"))))
                val dish = db.cookDao().getDishesForCook(source).single().id
                repo.notes.save(CookNoteDraft(cookId = source, dishId = dish, title = "Prep $name", occurredAt = sourceAnchor - 60000,
                    temperatures = listOf(NoteTemperature(probeName = "Same name", valueText = "74.125", unit = "°C", measuredAt = sourceAnchor - 60000, dishId = dish))))
                repo.addTimelineEvent(source, dish, "meat_on", "Food on", null, sourceAnchor)
                repo.completeCook(source)
                saved += repo.recipes.save(RecipeExtraction.fromMeal(repo.observeCookDetail(source).first()!!, dish).snapshot, sourceCookId = source, sourceDishId = dish)
            }
            saved.forEach { recipe -> repo.recipes.revision(recipe.currentRevisionId)!!.sourceCookId?.let { repo.deleteCook(db.cookDao().getCook(it)!!) } }
            val cook = repo.startRecipeMeal("Three guides", saved.map { it.currentRevisionId })
            val dishes = db.cookDao().getDishesForCook(cook)
            dishes.forEachIndexed { i, dish -> repo.addTimelineEvent(cook, dish.id, "meat_on", "Food on", null, now - 3600000 + i * 600000) }
            val reminders = ReminderRepository(db); reminders.save(cook, dishes[0].id, "My check", ReminderTrigger.Historical("food_on", 7200000))
            val before = repo.exportSnapshot(cook)
            val guide = db.companionDao().get("recipe-attempt:${dishes[1].id}")!!.let { PinnedRecipeGuide.decode(JSONObject(it.payload).getJSONObject("guide")) }
            val detail = repo.observeCookDetail(cook).first()!!
            val aligned = RecipeGuideProjection.aligned(guide, dishes[1].id, "Mac", RecipeGuideProjection.currentAnchor(detail, dishes[1].id))
            assertEquals(now - 3600000 + 600000 - 60000, aligned.readings.single().measuredAtUtcMillis)
            assertEquals(74.125, aligned.readings.single().value, 0.0); assertEquals(sourceAnchor - 60000, aligned.originalTimes.values.single())
            assertTrue(RecipeGuideProjection.aligned(guide, dishes[1].id, "Mac", null).readings.isEmpty())
            val selected = setOf(dishes[1].id, dishes[2].id)
            repo.guide.save(cook, GuidePresentation(true, selected)); repo.guide.save(cook, GuidePresentation(false, selected))
            val after = repo.exportSnapshot(cook)
            assertEquals(before.events, after.events); assertEquals(before.readings, after.readings)
            assertEquals(before.reminderRules, after.reminderRules); assertEquals(before.reminderOccurrences, after.reminderOccurrences)
            assertTrue(after.recordings.isEmpty()); assertTrue(db.reminderDao().rules().single().enabled)
            val edited = repo.recipeAttempt(dishes[1].id)!!.copy(instructions = listOf(RecipeInstruction("changed", "My own instruction")))
            repo.updateRecipeAttempt(dishes[1].id, edited)
            val retained = JSONObject(db.companionDao().get("recipe-attempt:${dishes[1].id}")!!.payload).getJSONObject("guide")
            assertTrue(SharingDraftCodec.sameStory(guide.story, PinnedRecipeGuide.decode(retained).story))
            val transfer = PitTechDataTransfer(context, repo); val bytes = ByteArrayOutputStream(); transfer.writeZip(bytes)
            val restored = CookRepository(other, PhotoStorage(context)); val imported = PitTechDataTransfer(context, restored).previewImport(ByteArrayInputStream(bytes.toByteArray()))
            restored.importSnapshot(imported.snapshot, imported.attachments)
            val state = GuidePresentation.decode(other.companionDao().get("guide-presentation:$cook")!!.payload)
            assertFalse(state.visible); assertEquals(selected, state.dishIds)
            assertFalse(other.reminderDao().rules().single().enabled)
        } finally { db.close(); other.close() }
    }

    @Test fun sameNamedChannelsAndExplicitGapsStaySeparateAndNegativeOffsetsSurvive() {
        val snapshot = RecipeSnapshot("Pork", DishDraft("Pork", "Pork"), smokingAnchorUtcMillis = 1000000)
        fun sample(id: String, probe: String, at: Long, quality: String = "valid") = JSONObject().put("id", id).put("probeId", probe).put("probeName", "Same name")
            .put("measurementType", "food").put("value", 74.125).put("unit", "°C").put("measuredAtUtcMillis", at).put("source", "recorded").put("qualityStatus", quality).put("samplingIntervalMillis", 15000)
        val rows = listOf(sample("a", "probe-a", 940000), sample("b", "probe-a", 955000), sample("c", "probe-a", 970000), sample("d", "probe-b", 970000), sample("e", "probe-a", 985000, "unavailable"), sample("f", "probe-a", 1000000))
        val story = JSONObject().put("version", 1).put("attemptId", "source-dish").put("anchorUtcMillis", 1000000).put("observations", JSONArray()).put("readings", JSONArray(rows)).put("results", JSONArray()).put("connectionGaps", JSONArray(listOf(960000)))
        val guide = PinnedRecipeGuide("source-version", snapshot, story.toString())
        val projected = RecipeGuideProjection.aligned(guide, "current-dish", "Pork", 2000000)
        assertEquals(2, projected.readings.map(TemperaturePlot::identity).toSet().size)
        assertEquals(1940000L, projected.readings.first().measuredAtUtcMillis)
        assertEquals(4, TemperaturePlot.segments(projected.readings, projected.gaps).size)
        val corrected = RecipeGuideProjection.aligned(guide, "current-dish", "Pork", 2100000)
        assertEquals(projected.readings.map { it.measuredAtUtcMillis + 100000 }, corrected.readings.map { it.measuredAtUtcMillis })
        assertEquals(projected.originalTimes, corrected.originalTimes)
        assertNotEquals(RecipeGuideProjection.channel("current-dish", rows[0]), RecipeGuideProjection.channel("current-dish", rows[3]))
        assertNotEquals(RecipeGuideProjection.role("setpoint"), RecipeGuideProjection.role("pit"))
    }
}
