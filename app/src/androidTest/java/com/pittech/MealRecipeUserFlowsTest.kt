package com.pittech

import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
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
    @Test fun codeImportRejectsForeignLinksWithoutChangingTheLocalLibrary() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-more"); compose.onNodeWithTag("nav-more").performClick()
        waitFor("more-relay-import"); compose.onNodeWithTag("more-relay-import").performScrollTo().performClick()
        waitFor("relay-code-input"); compose.onNodeWithTag("relay-code-input").performTextReplacement("https://foreign.invalid/s/ABCD-1234-5678-9EF0")
        compose.onNodeWithTag("relay-download").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Only PitTech share links are supported.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Only PitTech share links are supported.").performScrollTo().assertIsDisplayed()
        shot("relay-import-invalid")
        assertTrue(runBlocking(Dispatchers.IO) { app.cookRepository.observeCooks().first().isEmpty() })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.companionDao().observeAll().first().isEmpty() })
    }
    // Flowing §4.2: the composer's one-tap capture. An untouched wrap
    // prompt writes nothing; one tap on a material chip records it; a
    // spritz saved without touching its prompt still records "yes"
    // (the logged action is the data) with no medium row.
    @Test fun composerCaptureRecordsWrapAndSpritzInOneTap() {
        val cookId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.startCook(NewCookDraft("Capture flow brisket", dishes = listOf(DishDraft("Brisket", "Beef"))))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Capture flow brisket").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Capture flow brisket").performClick()
        waitFor("timeline-add")
        fun results() = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllResults().filter { it.cookId == cookId } }
        fun logAction(optionTag: String, details: String) {
            compose.onNodeWithTag("timeline-add").performClick()
            waitFor("timeline-entry-details")
            compose.onNodeWithTag("timeline-entry-details").performTextInput(details)
            closeFocusedSoftKeyboard()
            compose.onNodeWithText("More details", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("timeline-entry-type", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("timeline-entry-type", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag(optionTag).performClick()
        }
        // 1) Untouched wrap prompt: the entry saves, no input row appears.
        logAction("timeline-entry-type-option-wrap", "Wrapped at the stall")
        compose.onNodeWithTag("composer-wrap-material", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        // Notes are stored as eventType "note" with the action carried in
        // the note metadata — PlaybookCodec.action is how the app reads it.
        fun wrapLogged() = runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(cookId) }
            .any { PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "wrap" }
        compose.waitUntil(15000) { wrapLogged() }
        assertTrue(wrapLogged())
        assertTrue("an untouched prompt must not write a wrap row", results().none { it.resultType == "wrap_material" })
        // 2) One tap on Foil, then Save: the material is recorded.
        logAction("timeline-entry-type-option-wrap", "Re-wrapped for the rest")
        compose.onNodeWithTag("composer-wrap-material", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Foil", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(15000) { results().any { it.resultType == "wrap_material" } }
        assertEquals("foil", results().single { it.resultType == "wrap_material" }.textValue)
        // 3) Spritz saved without touching its prompt: spritz = yes, no medium.
        logAction("timeline-entry-type-option-spritz", "Spritzed the bark")
        compose.onNodeWithTag("composer-spritz", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(15000) { results().any { it.resultType == "spritz" } }
        assertEquals("yes", results().single { it.resultType == "spritz" }.textValue)
        assertTrue(results().none { it.resultType == "spritz_medium" })
        shot("composer-capture")
    }
    @Test fun savedRecipeCardPreviewAndCanceledShareKeepTheDraftAndSource() {
        val recipe = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.recipes.save(RecipeSnapshot("Pepper pork 🌶",DishDraft("Pepper pork 🌶","Pork"),description="Private original note"))
        }
        compose.activityRule.scenario.recreate()
        waitFor("nav-recipes");compose.onNodeWithTag("nav-recipes").performClick()
        waitFor("recipe-open-${recipe.id}");compose.onNodeWithTag("recipe-open-${recipe.id}").performScrollTo().performClick()
        compose.onNodeWithTag("recipe-share").performScrollTo().performClick()
        waitFor("sharing-preview-card");compose.onNodeWithTag("sharing-preview-card").performScrollTo().performClick()
        waitFor("share-card-preview");shot("share-card-preview")
        compose.onNodeWithTag("card-share").performScrollTo().performClick()
        // Espresso requires our Activity to be resumed; the system chooser owns focus here.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(10000) {
            automation.rootInActiveWindow?.packageName?.toString()?.let { it != app.packageName } == true
        }
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        waitFor("card-close");compose.onNodeWithTag("card-close").performScrollTo().performClick()
        waitFor("sharing-title")
        val record = runBlocking(Dispatchers.IO) { app.database.companionDao().observeAll().first().single { it.kind == SharingDraftCodec.DRAFT } }
        val doc = SharingDraftCodec.decode(record.payload)
        assertEquals(recipe.currentRevisionId,doc.entries.single().sourceRevisionId)
        assertFalse(doc.entries.single().includeStory);assertTrue(doc.entries.single().photoIds.isEmpty())
        assertEquals("Private original note",runBlocking(Dispatchers.IO) { RecipeSnapshotCodec.decode(app.database.recipeDao().revision(recipe.currentRevisionId)!!.snapshotJson).description })
    }
    @Test fun recipeCardOpensFromAnywhereOnItsFrame() {
        val recipe = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.recipes.save(RecipeSnapshot("Frame tap pork", DishDraft("Frame tap pork", "Pork"), description = "Tap target regression"))
        }
        compose.activityRule.scenario.recreate()
        waitFor("nav-recipes"); compose.onNodeWithTag("nav-recipes").performClick()
        waitFor("recipe-open-${recipe.id}")
        // Tap the bottom edge of the card — the "Saved recipe · version"
        // meta line, far from the name. Pre-fix only the name text was
        // clickable, so this tap did nothing and no detail ever opened.
        val card = compose.onNodeWithTag("recipe-open-${recipe.id}").performScrollTo()
        val cardBounds = card.fetchSemanticsNode().boundsInRoot
        card.performTouchInput { click(Offset(cardBounds.width / 2f, cardBounds.height - 6f)) }
        waitFor("recipe-details-close")
        compose.onNodeWithTag("recipe-details-close").assertIsDisplayed()
    }
    @Test fun oneGuideToggleControlsLiveTimelineAndChartsAndSurvivesRecreation() {
        val prepared = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val now = System.currentTimeMillis(); val revisions = mutableListOf<String>()
            for (name in listOf("Chicken", "Mac", "Pork")) {
                val source = app.cookRepository.startCook(NewCookDraft(name, dishes = listOf(DishDraft(name, "Other"))))
                val dish = app.database.cookDao().getDishesForCook(source).single().id
                app.cookRepository.addTimelineEvent(source, dish, "meat_on", "Food on", null, now - 7200000)
                app.cookRepository.notes.save(CookNoteDraft(cookId = source, dishId = dish, title = "Recorded check", occurredAt = now - 7140000,
                    temperatures = listOf(NoteTemperature(probeName = "Same name", valueText = "74.125", unit = "°C", measuredAt = now - 7140000, dishId = dish))))
                app.cookRepository.completeCook(source)
                revisions += app.cookRepository.recipes.save(RecipeExtraction.fromMeal(app.cookRepository.observeCookDetail(source).first()!!, dish).snapshot, sourceCookId = source, sourceDishId = dish).currentRevisionId
            }
            val cook = app.cookRepository.startRecipeMeal("Guided meal", revisions)
            val dishes = app.database.cookDao().getDishesForCook(cook)
            dishes.forEachIndexed { i, dish -> app.cookRepository.addTimelineEvent(cook, dish.id, "meat_on", "Food on", null, now - 3600000 + i * 600000) }
            app.reminderRepository.save(cook, dishes[0].id, "My separate check", ReminderTrigger.Historical("food_on", 7200000))
            cook to dishes.map { it.id }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Guided meal").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Guided meal").performClick()
        waitFor("show-guide"); compose.onNodeWithTag("show-guide").assertIsOff().performClick()
        waitFor("guide-reference-${prepared.second[0]}"); shot("show-guide-live")
        compose.onNodeWithTag("cook-tab-timeline").performClick()
        waitFor("guide-reference-${prepared.second[0]}"); shot("show-guide-timeline")
        compose.onNodeWithTag("cook-tab-charts").performClick()
        waitFor("guide-chart-legend"); compose.onNodeWithTag("guide-chart-legend").performScrollTo(); shot("show-guide-charts")
        compose.onNodeWithTag("show-guide").performClick()
        waitFor("guide-reminders-active")
        assertTrue(runBlocking(Dispatchers.IO) { app.database.reminderDao().rules().single().enabled })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Guided meal").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Guided meal").performClick(); waitFor("show-guide")
        compose.onNodeWithTag("show-guide").assertIsOff()
        assertEquals(3, runBlocking(Dispatchers.IO) { notePlanEvents(app.database.cookDao().getTimelineEventsForCook(prepared.first)).count { it.action == "food_on" } })
    }
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
        // PL-10 (#172): the Recipes start lands on the Start Cook review —
        // the source card shows and no cook exists until "Begin preparation".
        waitFor("cook-source")
        compose.onNodeWithTag("cook-source").assertIsDisplayed()
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().none { it.status == CookStatus.ACTIVE } })
        shot("start-cook-review-recipes")
        compose.onNodeWithTag("cook-save").performClick()
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
    /** PL-10 (#172): "Use this version" no longer instant-creates a cook —
     * it lands on the same Start Cook review, and the cook exists only
     * after "Begin preparation" confirms it, with the recipe attached. */
    @Test fun useThisVersionReviewsBeforeStarting() {
        val recipe = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.recipes.save(RecipeSnapshot("Review ribs", DishDraft("Review ribs", "Pork")))
        }
        compose.activityRule.scenario.recreate()
        waitFor("nav-recipes"); compose.onNodeWithTag("nav-recipes").performClick()
        waitFor("recipe-open-${recipe.id}"); compose.onNodeWithTag("recipe-open-${recipe.id}").performScrollTo().performClick()
        compose.onNodeWithText("Use this version").performClick()
        waitFor("cook-source")
        compose.onNodeWithTag("cook-source").assertIsDisplayed()
        compose.onNodeWithText("From your Recipes · saved recipes stay attached to their dishes").assertIsDisplayed()
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().isEmpty() })
        shot("start-cook-review-use-version")
        compose.onNodeWithTag("cook-save").performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().size == 1 } }
        val cook = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().single() }
        val dish = runBlocking(Dispatchers.IO) { app.database.cookDao().getDishesForCook(cook.id).single() }
        assertEquals(recipe.currentRevisionId, runBlocking(Dispatchers.IO) { app.cookRepository.recipes.source(dish.id)!!.recipeRevisionId })
    }
    @Test fun recordedCookPreviewAddsHistoryWithoutSavingRecipesOrStartingAutomation() {
        val uri = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val cook = app.cookRepository.startCook(NewCookDraft("Pork and potatoes", dishes = listOf(DishDraft("Pork", "Pork"), DishDraft("Potatoes", "Vegetables"))))
            val dishes = app.database.cookDao().getDishesForCook(cook)
            app.cookRepository.completeCook(cook)
            val id = app.cookRepository.sharing.fromMeal(cook, dishes.map { it.id }.toSet())
            val record = app.cookRepository.sharing.get(id)!!; val document = SharingDraftCodec.decode(record.payload)
            app.cookRepository.sharing.save(id, document.copy(entries = document.entries.map { it.copy(includeStory = true) }), record.updatedAtUtcMillis)
            val file = File(app.cacheDir, "exports/ui-history.pittech").apply { parentFile!!.mkdirs() }
            file.outputStream().use { app.dataTransfer.writeCookPack(it, id, includeRecordedCook = true) }
            app.database.clearAllTables()
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        }
        compose.activityRule.scenario.onActivity { activity ->
            val launch = activity.intent
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, android.content.Intent(launch).setAction(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/zip"))
            activity.intent = launch
        }
        waitFor("pack-add-history"); compose.onNodeWithTag("pack-add-history").performScrollTo()
        shot("cook-pack-recorded-history-preview")
        compose.onNodeWithTag("pack-add-history").performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().size == 1 } }
        assertEquals(CookStatus.COMPLETED, runBlocking(Dispatchers.IO) { app.database.cookDao().getAllCooks().single().status })
        assertEquals(2, runBlocking(Dispatchers.IO) { app.database.cookDao().getAllDishes().size })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.recipeDao().recipes().isEmpty() })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.reminderDao().rules().isEmpty() })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.recordingDao().getAllRecordings().isEmpty() })
        // PL-19: adding history leaves the save dialog open with the recipe
        // selection intact — the recipes can still be saved afterwards.
        waitFor("pack-history-added")
        compose.onNodeWithTag("pack-save-recipes").assertIsDisplayed().performClick()
        compose.waitUntil(20000) { runBlocking(Dispatchers.IO) { app.database.recipeDao().recipes().size == 2 } }
    }
}
