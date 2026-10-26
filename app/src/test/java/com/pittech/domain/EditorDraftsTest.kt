package com.pittech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PL-24 (#172): the editor draft codecs round-trip losslessly —
 * including in-progress values the validating codecs would reject,
 * because keeping an invalid half-typed edit is the point of a draft.
 */
class EditorDraftsTest {

    @Test
    fun dishDraftRoundTripsEveryFieldIncludingPhotos() {
        val draft = DishDraft(
            name = "Brisket", foodType = "Beef", cut = "Packer", weightText = "12.5",
            weightUnit = "lb", startingCondition = "Chilled", boneIn = true,
            placement = "Top rack", gradeOrSource = "Prime", thicknessNotes = "Thick flat",
            prepNotes = "Trim hard fat",
            preparationItems = listOf(
                IngredientDraft("Salt", "seasoning", "Diamond Crystal", "2", "tbsp"),
                IngredientDraft("", "marinade", "", "", "cup"),
            ),
            photoUris = listOf("content://photos/1", "content://photos/2"),
        )
        assertEquals(draft, DishDraftCodec.decode(DishDraftCodec.encode(draft)))
    }

    @Test
    fun dishDraftDecodeRejectsGarbage() {
        assertNull(DishDraftCodec.decode("not json"))
        assertNull(DishDraftCodec.decode("{}"))
    }

    @Test
    fun recipeDraftRoundTripsRawInvalidNumericText() {
        val draft = RecipeEditorDraft(
            name = "Weeknight ribs", description = "Fast", servings = "4", resultNotes = "Less sugar",
            dish = DishDraft("Ribs", "Pork", weightText = "3"),
            tags = "pork, quick", pit = "not-a-number",
            instructions = listOf(
                RecipeInstructionDraft("i1", "Wrap", "Foil + butter", "wrap", null, "wrap", "12.", "abc"),
            ),
            targets = listOf(RecipeTargetDraft("personal_finish", "20x", "°F", "Probe tender")),
            keptAssets = setOf("a1"), keptPhotos = setOf("p1"),
            captions = mapOf("a1" to "Before", "content://new" to "After"),
            addedPhotos = listOf("content://new"),
            contextIds = setOf("e1"), keptFacts = setOf("f1", "f2"),
        )
        assertEquals(draft, RecipeEditorDraftCodec.decode(RecipeEditorDraftCodec.encode(draft)))
    }

    @Test
    fun stepDraftRoundTripsStepAndRawFields() {
        val step = PlaybookStep(
            id = "s1", dishIndex = 2, action = "wrap", title = "Wrap the brisket",
            instructions = "Butcher paper", trigger = "temperature", minutes = 45,
            temperatureF = 165.0, anchor = "food_on", clockAtUtcMillis = 1_700_000_000_000,
            repeatMinutes = 30, stage = "cooking", sourceObservations = "Last time it stalled",
            probeName = "Probe 1",
        )
        val draft = StepEditorDraft(step, "45", "165.0", "°F", "30", "18:30", "2026-10-25")
        assertEquals(draft, StepEditorDraftCodec.decode(StepEditorDraftCodec.encode(draft)))
    }

    @Test
    fun alertDraftRoundTripsAnInvalidInProgressRule() {
        // A target alert with no temperature typed yet fails
        // CookAlertEngine.validate — the draft codec must keep it anyway.
        val rule = CookAlertRule(id = "a1", type = "target", dishId = "d1", targetF = null, enabled = false)
        val draft = AlertEditorDraft(rule, "Food target", "°C", "", "93.", "148", "2", "3", timed = true)
        assertEquals(draft, AlertEditorDraftCodec.decode(AlertEditorDraftCodec.encode(draft)))
    }

    @Test
    fun feedbackDraftRoundTrips() {
        val draft = FeedbackDraft("FEATURE", "Dark mode graphs", "Please…", includeCrashReport = true)
        assertEquals(draft, FeedbackDraftCodec.decode(FeedbackDraftCodec.encode(draft)))
        assertNull(FeedbackDraftCodec.decode("{}"))
    }

    @Test
    fun memoryStoreSavesLoadsAndClearsByKey() {
        val store = MemoryEditorDraftStore()
        assertNull(store.load(EditorDraftKeys.FEEDBACK))
        store.save(EditorDraftKeys.FEEDBACK, "one")
        store.save(EditorDraftKeys.dish("startcook"), "two")
        assertEquals("one", store.load(EditorDraftKeys.FEEDBACK))
        assertEquals("two", store.load(EditorDraftKeys.dish("startcook")))
        store.clear(EditorDraftKeys.FEEDBACK)
        assertNull(store.load(EditorDraftKeys.FEEDBACK))
        assertTrue(store.load(EditorDraftKeys.dish("startcook")) != null)
    }

    @Test
    fun draftKeysAreScopedAndStable() {
        assertEquals("recipe:new", EditorDraftKeys.recipe(null, null))
        assertEquals("recipe:r1", EditorDraftKeys.recipe("r1", null))
        assertEquals("recipe:attempt:d1", EditorDraftKeys.recipe(null, "d1"))
        assertEquals("alert:cook1:new", EditorDraftKeys.alert("cook1:new"))
    }
}
