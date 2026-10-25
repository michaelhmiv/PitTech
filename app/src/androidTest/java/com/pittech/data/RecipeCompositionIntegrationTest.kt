package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class RecipeCompositionIntegrationTest {
    @Test fun mixedMealsOwnTheirSetupAndHistoryWhileRecipesAndOlderVersionsStayIndependent(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val saved = mutableListOf<SavedRecipe>(); val originalIds = mutableListOf<String>()
            for (name in listOf("Chicken", "Mac", "Pork")) {
                val id = repo.startCook(NewCookDraft(name, dishes = listOf(DishDraft(name, "Other", preparationItems = listOf(IngredientDraft("Salt", amountText = "1.00", amountUnit = "tsp"))))))
                originalIds += id
                val dish = db.cookDao().getDishesForCook(id).single()
                repo.notes.save(CookNoteDraft(cookId = id, dishId = dish.id, description = "$name private note"))
                repo.completeCook(id)
                saved += repo.recipes.save(RecipeExtraction.fromMeal(repo.observeCookDetail(id).first()!!, dish.id).snapshot, sourceCookId = id, sourceDishId = dish.id)
            }
            val before = repo.exportSnapshot()
            val meal = repo.startRecipeMeal("Today", saved.map { it.currentRevisionId }, listOf(DishDraft("Potatoes", "Vegetables")))
            val dishes = db.cookDao().getDishesForCook(meal)
            assertEquals(4, dishes.size)
            assertEquals(saved.map { it.currentRevisionId }, dishes.take(3).map { repo.recipes.source(it.id)!!.recipeRevisionId })
            assertTrue(db.cookDao().getTimelineEventsForCook(meal).none { it.eventType == "note" })
            assertTrue(db.recordingDao().getAllRecordings().isEmpty())
            assertTrue(db.companionDao().forCook(meal).none { it.kind == "plan" })
            val working = repo.recipeAttempt(dishes[1].id)!!
            repo.updateRecipeAttempt(dishes[1].id, working.copy(dish = working.dish.copy(preparationItems = listOf(IngredientDraft("Jalapeños", amountText = "2", amountUnit = "count"))), targets = listOf(PlaybookTarget(0, "personal_finish", 0.0, "°C", "Personal cold target"))))
            assertEquals("Salt", RecipeSnapshotCodec.decode(repo.recipes.revision(saved[1].currentRevisionId)!!.snapshotJson).dish.preparationItems.single().name)
            assertEquals("Jalapeños", db.cookDao().getIngredientsForCook(meal).single { it.dishId == dishes[1].id }.name)
            assertEquals(before.events.filter { it.cookId in originalIds }, repo.exportSnapshot().events.filter { it.cookId in originalIds })
            assertEquals(0.0, db.cookDao().getAllTargets().single { it.dishId == dishes[1].id }.value, 0.0)
            val preset = repo.saveMealPreset(meal, "Favorites")
            assertEquals(3, db.recipeDao().recipes().size)
            val repeated = repo.startMealPreset(preset)
            assertEquals(4, db.cookDao().getDishesForCook(repeated).size)
            assertEquals("Jalapeños", db.cookDao().getIngredientsForCook(repeated).single { it.dishId == db.cookDao().getDishesForCook(repeated)[1].id }.name)
            val updated = repo.recipes.save(working.copy(description = "New preference"), saved[1].id, saved[1].currentRevisionId)
            assertNotEquals(saved[1].currentRevisionId, updated.currentRevisionId)
            val older = repo.startRecipeMeal("Older version", listOf(saved[1].currentRevisionId))
            assertEquals(saved[1].currentRevisionId, repo.recipes.source(db.cookDao().getDishesForCook(older).single().id)!!.recipeRevisionId)
            repo.recipes.archive(saved[1].id)
            assertNotNull(db.cookDao().getCook(meal))
            assertNotNull(repo.recipes.revision(saved[1].currentRevisionId))
            val snapshot = repo.exportSnapshot()
            RecipeArchive.validate(snapshot)
            val other = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
            try { val restored = CookRepository(other, PhotoStorage(context)); restored.importSnapshot(snapshot, emptyMap()); assertEquals(db.recipeDao().sources().size, other.recipeDao().sources().size); assertEquals(3, other.recipeDao().recipes().size) } finally { other.close() }
        } finally { db.close() }
    }

    /** PL-10 (#172): a meal combination now starts through the Start Cook
     * review draft. The draft must carry the preset's fidelity — revision
     * attachments plus working copies — so confirming the review produces
     * exactly the cook the old direct start produced. */
    @Test fun presetReviewDraftStartsWithWorkingCopiesIntact(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val source = repo.startCook(NewCookDraft("Source", dishes = listOf(DishDraft("Chicken", "Poultry", preparationItems = listOf(IngredientDraft("Salt", amountText = "1", amountUnit = "tsp"))))))
            val sourceDish = db.cookDao().getDishesForCook(source).single()
            repo.completeCook(source)
            val saved = repo.recipes.save(RecipeExtraction.fromMeal(repo.observeCookDetail(source).first()!!, sourceDish.id).snapshot, sourceCookId = source, sourceDishId = sourceDish.id)
            val meal = repo.startRecipeMeal("Working meal", listOf(saved.currentRevisionId))
            val mealDish = db.cookDao().getDishesForCook(meal).single()
            val working = repo.recipeAttempt(mealDish.id)!!
            repo.updateRecipeAttempt(mealDish.id, working.copy(dish = working.dish.copy(preparationItems = listOf(IngredientDraft("Jalapeños", amountText = "2", amountUnit = "count")))))
            val preset = repo.saveMealPreset(meal, "Favorites")

            val draft = repo.presetStartDraft(preset)
            assertEquals("Favorites", draft.title)
            assertEquals("From a saved meal combination · Favorites", draft.sourceLabel)
            assertEquals(listOf(saved.currentRevisionId), draft.recipeRevisionIds)
            assertEquals(1, draft.recipeAttemptSnapshots.filterNotNull().size)

            val started = repo.startCook(draft)
            val startedDish = db.cookDao().getDishesForCook(started).single()
            assertEquals(saved.currentRevisionId, repo.recipes.source(startedDish.id)!!.recipeRevisionId)
            assertEquals("Jalapeños", db.cookDao().getIngredientsForCook(started).single { it.dishId == startedDish.id }.name)
        } finally { db.close() }
    }

    /** PL-10 (#172): previewing a cook-again setup must not save a playbook
     * as a side effect. Starting from the preview attaches the suggested
     * steps to the new cook as its plan while the playbook library gains
     * nothing. */
    @Test fun cookAgainStartAttachesStepsWithoutSavingAPlaybook(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val companion = CookCompanionRepository(db, PhotoStorage(context))
            val source = repo.startCook(NewCookDraft("Sunday ribs", dishes = listOf(DishDraft("Ribs", "Pork"))))
            repo.completeCook(source)
            val playbooksBefore = db.companionDao().observeAll().first().count { it.kind == "playbook" }
            val book = companion.suggest(source)
            val started = repo.startCook(book.draft.copy(playbookId = null), transientPlaybook = book)
            val records = db.companionDao().observeAll().first()
            assertEquals(playbooksBefore, records.count { it.kind == "playbook" })
            assertTrue(records.any { it.kind == "plan" && it.cookId == started })
            assertEquals(listOf("Ribs"), db.cookDao().getDishesForCook(started).map { it.name })
        } finally { db.close() }
    }
}
