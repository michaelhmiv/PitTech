package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookPlaybookTest {
    @Test fun snapshotRoundTripPreservesDishMappingAndUnitsWithoutTransientState() {
        val draft = NewCookDraft("Weekend brisket", smokerName = "Offset", setpointText = "125", setpointUnit = "°C", dishes = listOf(DishDraft("Brisket", "Beef", "Brisket", preparationItems = listOf(IngredientDraft("Salt", amountText = "2", amountUnit = "tbsp")))), recordGrill = true, playbookId = "old")
        val book = CookPlaybook("Favorite", draft, listOf(PlaybookStep(id = "wrap", dishIndex = 0, action = "wrap", title = "Check bark", trigger = "temperature", temperatureF = 165.0)), listOf(PlaybookTarget(0, "personal_finish", 94.0, "°C", "Check tenderness")), "source")
        val decoded = PlaybookCodec.decode(PlaybookCodec.encode(book))
        assertEquals(book.steps, decoded.steps)
        assertEquals(book.targets, decoded.targets)
        assertEquals(draft.dishes, decoded.draft.dishes)
        assertEquals("°C", decoded.draft.setpointUnit)
        assertFalse(decoded.draft.recordGrill)
        assertNull(decoded.draft.playbookId)
        assertFalse(PlaybookCodec.encode(book).contains("controllerKey"))
    }
    @Test fun rejectsInvalidDishAndZeroRecurrence() {
        val draft = NewCookDraft("Cook", dishes = listOf(DishDraft("Ribs", "Pork")))
        for (step in listOf(PlaybookStep(dishIndex = 2), PlaybookStep(repeatMinutes = 0), PlaybookStep(trigger = "temperature", temperatureF = Double.NaN))) {
            assertThrows(IllegalArgumentException::class.java) { PlaybookCodec.encode(CookPlaybook("Cook", draft, listOf(step))) }
        }
    }
    @Test fun existingTimelineActionsAreMapped() {
        assertEquals("food_on", PlaybookCodec.action("meat_on"))
        assertEquals("rest", PlaybookCodec.eventType("rest_start"))
    }
}
