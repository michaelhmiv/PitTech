package com.pittech

import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class MealRecipeUserFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as PitTechApplication
    private fun shot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(app.filesDir, "pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun waitFor(tag: String) { compose.waitUntil(20000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun cookPackPreviewLetsGuestSaveOnlyOneRecipeAndKeepsHistorySeparate() {
        val prepared = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val cook = app.cookRepository.startCook(NewCookDraft("Pork and potatoes", dishes = listOf(DishDraft("Pork", "Pork"), DishDraft("Potatoes", "Vegetables"))))
            val dishes = app.database.cookDao().getDishesForCook(cook)
            app.cookRepository.completeCook(cook)
            val id = app.cookRepository.sharing.fromMeal(cook, dishes.map { it.id }.toSet())
            val file = File(app.cacheDir, "exports/ui-selection.pittech").apply { parentFile!!.mkdirs() }
            file.outputStream().use { app.dataTransfer.writeCookPack(it, id) }
            val preview = file.inputStream().use(app.dataTransfer::previewCookPack)
            val omit = preview.snapshot.recipes.first { it.name == "Potatoes" }.currentRevisionId
            app.database.clearAllTables()
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            uri to omit
        }
        compose.activityRule.scenario.onActivity { activity ->
            val launchIntent = activity.intent
            val incoming = android.content.Intent(launchIntent).setAction(android.content.Intent.ACTION_VIEW).setDataAndType(prepared.first, "application/zip")
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, incoming)
            // ActivityScenario matches the launch intent when it receives later lifecycle callbacks.
            activity.intent = launchIntent
        }
        waitFor("pack-save-recipes")
        compose.onNodeWithTag("pack-select-${prepared.second}").performScrollTo().performClick()
        compose.onNodeWithTag("pack-save-recipes").assertTextEquals("Save 1 recipe")
        shot("cook-pack-selective-preview")
        compose.onNodeWithTag("pack-save-recipes").performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.database.recipeDao().recipes().size == 1 } }
        assertEquals("Pork", runBlocking(Dispatchers.IO) { app.database.recipeDao().recipes().single().name })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().isEmpty() })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.reminderDao().rules().isEmpty() })
        compose.onNodeWithTag("nav-recipes").performClick(); shot("cook-pack-saved-recipe")
    }
    @Test fun combineAdjustEnableHistoricalCheckAndSaveOnlyOneDishAfterPausedFinish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val sourceRecipes = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            listOf("Chicken" to "Poultry", "Mac and cheese" to "Pasta", "Pork" to "Pork").map { (name, type) ->
                val cook = app.cookRepository.startCook(NewCookDraft("Previous $name", dishes = listOf(DishDraft(name, type))))
                val dish = app.database.cookDao().getDishesForCook(cook).single()
                val anchor = System.currentTimeMillis() - 5 * 3600000
                if (name == "Mac and cheese") app.cookRepository.notes.save(CookNoteDraft(cookId = cook, dishId = dish.id, title = "Boil macaroni", occurredAt = anchor - 600000, method = "stovetop", durationMillis = 480000))
                app.cookRepository.addTimelineEvent(cook, dish.id, "meat_on", "Food on", null, anchor)
                app.cookRepository.addTimelineEvent(cook, dish.id, "wrap", "Wrapped", "Check bark", anchor + 12000000)
                app.cookRepository.completeCook(cook)
                val extracted = RecipeExtraction.fromMeal(app.cookRepository.observeCookDetail(cook).first()!!, dish.id)
                app.cookRepository.recipes.save(extracted.snapshot, sourceCookId = cook, sourceDishId = dish.id)
            }
        }
        compose.activityRule.scenario.recreate()
        waitFor("nav-recipes"); compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithTag("recipe-compose").performClick()
        sourceRecipes.forEach { recipe -> waitFor("recipe-select-${recipe.id}"); compose.onNodeWithTag("recipe-select-${recipe.id}").performScrollTo().performClick() }
        compose.onNodeWithTag("recipe-meal-title").performScrollTo().performTextInput("Three source meal")
        compose.onNodeWithTag("recipe-start-meal").performScrollTo().performClick()
        waitFor("meal-reminders")
        val meal = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().single { it.status == CookStatus.ACTIVE } }
        val dishes = runBlocking(Dispatchers.IO) { app.database.cookDao().getDishesForCook(meal.id) }
        assertEquals(3, dishes.size)
        val chicken = dishes.first { it.name == "Chicken" }; val mac = dishes.first { it.name == "Mac and cheese" }
        assertTrue(runBlocking(Dispatchers.IO) { notePlanEvents(app.database.cookDao().getTimelineEventsForCook(meal.id)).none { it.action in CookPhases.phaseActions } })
        val revision = runBlocking(Dispatchers.IO) { val source = app.cookRepository.recipes.source(chicken.id)!!; app.cookRepository.recipes.revision(source.recipeRevisionId)!! }
        val milestone = RecipeSnapshotCodec.decode(revision.snapshotJson).observations.first { it.action == "wrap" }
        compose.onNodeWithTag("meal-reminders").performScrollTo().performClick()
        val suggestion = "historical-reminder-${chicken.id}-${milestone.sourceEventId}"
        waitFor(suggestion); compose.onNodeWithTag(suggestion).performScrollTo().performClick()
        compose.onNodeWithTag("reminder-rule-save").performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.database.reminderDao().rules().singleOrNull()?.status == "waiting" } }
        assertEquals("waiting", runBlocking(Dispatchers.IO) { app.database.reminderDao().rules().single().status })
        androidx.test.espresso.Espresso.pressBack()
        waitFor("meal-recipe-actions"); compose.onNodeWithTag("meal-recipe-actions").performScrollTo().performClick()
        waitFor("recipe-adjust-${mac.id}"); compose.onNodeWithTag("recipe-adjust-${mac.id}").performScrollTo().performClick()
        compose.onNodeWithTag("recipe-description").performScrollTo().performTextInput("Try more Gouda")
        compose.onNodeWithTag("recipe-save").performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.cookRepository.recipeAttempt(mac.id)?.description == "Try more Gouda" } }
        assertTrue(runBlocking(Dispatchers.IO) { app.database.recipeDao().revisions().all { RecipeSnapshotCodec.decode(it.snapshotJson).description.isEmpty() } })
        compose.waitUntil(20000) { compose.onAllNodesWithTag("recipe-save").fetchSemanticsNodes().isEmpty() }
        closeFocusedSoftKeyboard()
        compose.onNodeWithTag("meal-recipe-actions").performScrollTo().performClick()
        compose.onNodeWithTag("live-cook-elapsed").performScrollTo()
        compose.waitForIdle()
        shot("recipe-composed-live")
        runBlocking(Dispatchers.IO) { app.cookRepository.pauseCook(meal.id) }
        compose.onNodeWithTag("live-finish-cook").performScrollTo().performClick()
        compose.onNodeWithTag("finish-cook-save").performClick()
        waitFor("dish-save-recipe-${mac.id}")
        compose.onNode(hasTestTag("dish-save-recipe-${mac.id}") and hasAnyAncestor(hasTestTag("meal-completion-choices"))).performScrollTo().performClick()
        compose.onNodeWithTag("recipe-name").performTextClearance(); compose.onNodeWithTag("recipe-name").performTextInput("Mac variation")
        compose.onNodeWithTag("recipe-save").performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.database.recipeDao().recipes().size == 4 } }
        val variation = runBlocking(Dispatchers.IO) { val header = app.database.recipeDao().recipes().single { it.name == "Mac variation" }; app.cookRepository.recipes.revision(header.currentRevisionId)!! }
        assertEquals("Try more Gouda", RecipeSnapshotCodec.decode(variation.snapshotJson).description)
        assertEquals(meal.id, variation.sourceCookId)
        assertTrue(runBlocking(Dispatchers.IO) { notePlanEvents(app.database.cookDao().getTimelineEventsForCook(meal.id)).none { it.action in CookPhases.phaseActions } })
        compose.waitUntil(20000) { compose.onAllNodesWithTag("recipe-save").fetchSemanticsNodes().isEmpty() }
        closeFocusedSoftKeyboard()
        compose.onNodeWithTag("meal-view-summary").performScrollTo()
        compose.waitForIdle()
        shot("meal-finished-recipe-choice")
        compose.onNodeWithTag("leave-without-saving").performScrollTo().performClick()
        assertEquals(CookStatus.COMPLETED, runBlocking(Dispatchers.IO) { app.database.cookDao().getCook(meal.id)!!.status })
    }
}
