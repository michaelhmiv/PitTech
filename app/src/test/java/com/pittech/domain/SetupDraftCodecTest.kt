package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * PL-13 (#172): the kept Start Cook setup draft must survive a round trip
 * with every field intact — a resumed setup is the same setup, including
 * its source attachments, or "Resume setup" would quietly start a
 * different cook than the one that was left.
 */
class SetupDraftCodecTest {
    @Test fun roundTripPreservesEveryStartCookField() {
        val draft = NewCookDraft(
            title = "Saturday brisket",
            smokerName = "Offset",
            setpointText = "250",
            setpointUnit = "°F",
            notes = "Wind from the north",
            fuelType = "Oak splits",
            woodOrPelletBlend = "Post oak",
            dishes = listOf(
                DishDraft("Brisket", "Beef", "Packer", weightText = "12", weightUnit = "lb",
                    preparationItems = listOf(IngredientDraft("Salt", amountText = "2", amountUnit = "tbsp")),
                    photoUris = listOf("content://photos/1")),
                DishDraft("Beans", "Sides"),
            ),
            outdoorTemperatureText = "61",
            outdoorTemperatureUnit = "°F",
            weatherNotes = "Clear",
            windNotes = "Breezy",
            followPlaybook = false,
            alreadyCooking = true,
            foodOnAtUtcMillis = 1_700_000_000_000,
            sourceLabel = "Cook again · copied from Sunday ribs",
            recipeRevisionIds = listOf("rev-1", null),
            recipeAttemptSnapshots = listOf("{\"snapshot\":true}", null),
        )
        assertEquals(draft, SetupDraftCodec.decode(SetupDraftCodec.encode(draft)))
    }

    @Test fun roundTripPreservesABlankSetup() {
        val draft = NewCookDraft("")
        assertEquals(draft, SetupDraftCodec.decode(SetupDraftCodec.encode(draft)))
    }

    @Test fun roundTripPreservesPlaybookAndPlanSources() {
        val draft = NewCookDraft(
            title = "Planned cook",
            dishes = listOf(DishDraft("Ribs", "Pork")),
            playbookId = "book-1",
            scheduledPlanId = "plan-1",
            sourceLabel = "From a saved meal combination · Weeknight",
        )
        val decoded = SetupDraftCodec.decode(SetupDraftCodec.encode(draft))
        assertEquals("book-1", decoded.playbookId)
        assertEquals("plan-1", decoded.scheduledPlanId)
        assertEquals("From a saved meal combination · Weeknight", decoded.sourceLabel)
        assertEquals(draft.dishes, decoded.dishes)
    }
}
