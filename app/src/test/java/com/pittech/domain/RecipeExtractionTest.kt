package com.pittech.domain

import com.pittech.data.*
import org.junit.Test
import org.junit.Assert.*

class RecipeExtractionTest {
    private fun event(id: String, dish: String?, at: Long, title: String, metadata: CookNoteMetadata? = null, context: String? = null) =
        TimelineEventEntity(id, "meal", dish, "note", title, title, at, at, "UTC", "manual", createdAtUtcMillis = at, updatedAtUtcMillis = at, noteMetadataJson = metadata?.encode(), temperatureContextJson = context)
    @Test fun dishExtractionKeepsNegativePrepAndExplicitSharedNotesWithoutOtherFoodTemperatures() {
        val cook = CookEntity("meal", "Meal", "completed", 1000, "UTC", createdAtUtcMillis = 1000, updatedAtUtcMillis = 20000)
        val dishes = listOf("mac", "pork").map { DishEntity(it, "meal", it, "Other", null, createdAtUtcMillis = 1000, updatedAtUtcMillis = 1000) }
        val readings = listOf("mac", "pork").map { SensorReadingEntity("temp-$it", "meal", it, probeName = "Same probe name", measurementType = "food", value = 165.0, unit = "°F", measuredAtUtcMillis = 20000, timeZoneId = "UTC", source = "manual", recordedAtUtcMillis = 20000) }
        val context = TemperatureContext.encode(listOf(ContextTemperature("Same probe name", 165.0, "°F", 20000, "manual", "measurement")))
        val events = listOf(event("prep", "mac", 1000, "Boiled pasta", CookNoteMetadata(method = "stovetop", durationMillis = 480000)),
            event("on", "mac", 10000, "Food on", CookNoteMetadata(action = "food_on")), event("private", "pork", 12000, "Pork only"),
            event("shared", null, 20000, "Shared check", CookNoteMetadata(dishIds = listOf("mac", "pork")), context), event("global", null, 19000, "Changed smoker setting"))
        val data = CookDetailData(cook, dishes, emptyList(), events, readings, emptyList(), emptyList(), emptyList(), emptyList(),
            logReadingLinks = listOf(LogReadingLink("temp-mac", "shared"), LogReadingLink("temp-pork", "shared")))
        val extracted = RecipeExtraction.fromMeal(data, "mac")
        assertEquals(setOf("prep", "on", "shared"), extracted.snapshot.observations.map { it.sourceEventId }.toSet())
        assertEquals(-9000L, extracted.snapshot.observations.first().offsetMillis)
        assertEquals(480000L, extracted.snapshot.instructions.first().durationMillis)
        val shared = extracted.snapshot.observations.last()
        assertNull(shared.temperatureContextJson)
        assertEquals("temp-mac", shared.recordedTemperatures.single().sourceReadingId)
        assertEquals(165.0, shared.recordedTemperatures.single().value, 0.0)
        assertEquals(listOf("global"), extracted.contextEvents.map { it.id })
        assertEquals(extracted.snapshot, RecipeSnapshotCodec.decode(RecipeSnapshotCodec.encode(extracted.snapshot)))
        assertTrue(RecipeExtraction.fromMeal(data, "mac", setOf("global")).snapshot.observations.any { it.sourceEventId == "global" })
    }
    @Test fun sharedPhysicalActionsRemainFactsButAnotherDishsPitChannelDoesNot() {
        val cook = CookEntity("meal", "Meal", "completed", 1000, "UTC", createdAtUtcMillis = 1000, updatedAtUtcMillis = 20000)
        val dishes = listOf("mac", "pork").map { DishEntity(it, "meal", it, "Other", null, createdAtUtcMillis = 1000, updatedAtUtcMillis = 1000) }
        val actions = listOf("food_on", "wrap", "remove").mapIndexed { i, action -> event(action, null, 10000L + i * 1000, action, CookNoteMetadata(action = action)) }
        val reading = SensorReadingEntity("private-pit", "meal", "pork", probeName = "Pit", measurementType = "pit", value = 225.0, unit = "°F", measuredAtUtcMillis = 11000, timeZoneId = "UTC", source = "manual", recordedAtUtcMillis = 11000)
        val data = CookDetailData(cook, dishes, emptyList(), actions, listOf(reading), emptyList(), emptyList(), emptyList(), emptyList(), logReadingLinks = listOf(LogReadingLink(reading.id, "wrap")))
        val result = RecipeExtraction.fromMeal(data, "mac").snapshot
        assertEquals(listOf("food_on", "wrap", "remove"), result.observations.map { it.action })
        assertTrue(result.observations.flatMap { it.recordedTemperatures }.isEmpty())
    }
    @Test fun anIncompleteAttemptRetainsUnreplacedIngredientsAndInstructions() {
        val original = RecipeSnapshot("Mac", DishDraft("Mac", "Pasta", prepNotes = "Salt pasta water", preparationItems = listOf(IngredientDraft("Gouda", amountText = "4.00", amountUnit = "oz"))),
            publicSource = PublicRecipeSource("12345678-1234-4234-8234-123456789abc", "23456789-1234-4234-8234-123456789abc", "a PitTech cook"),
            instructions = listOf(RecipeInstruction("boil", "Boil", durationMillis = 480000), RecipeInstruction("sauce", "Make sauce")))
        val current = RecipeSnapshot("Mac again", DishDraft("Mac", "Pasta"), instructions = listOf(RecipeInstruction("new-boil", "Boil", durationMillis = 420000)))
        val merged = RecipeExtraction.mergeAttempt(original, current)
        assertEquals(original.publicSource, merged.publicSource)
        assertEquals("4.00", merged.dish.preparationItems.single().amountText)
        assertEquals("Salt pasta water", merged.dish.prepNotes)
        assertEquals(420000L, merged.instructions.first().durationMillis)
        assertEquals("Make sauce", merged.instructions.last().title)
        assertTrue(merged.observations.isEmpty())
    }
    @Test fun inheritedFreeTextQuantitySurvivesNumericOnlyCookStorageButNewQuantitiesWin() {
        val original=RecipeSnapshot("Pork",DishDraft("Pork","Pork",preparationItems=listOf(IngredientDraft("Salt",amountUnit="To taste"))))
        val recorded=original.copy(dish=original.dish.copy(preparationItems=listOf(IngredientDraft("Salt",amountUnit="count"))))
        assertEquals("To taste",RecipeExtraction.mergeAttempt(original,recorded).dish.preparationItems.single().amountUnit)
        val measured=recorded.copy(dish=recorded.dish.copy(preparationItems=listOf(IngredientDraft("Salt",amountText="2",amountUnit="tsp"))))
        assertEquals("2",RecipeExtraction.mergeAttempt(original,measured).dish.preparationItems.single().amountText)
        assertEquals("tsp",RecipeExtraction.mergeAttempt(original,measured).dish.preparationItems.single().amountUnit)
    }

}
