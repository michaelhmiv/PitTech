package com.pittech.domain

import org.junit.Test
import org.junit.Assert.*

class CookingMethodsTest {
    @Test fun structuredPreparationKeepsOneNoteAndOriginalUnits() {
        val note = CookNoteDraft(cookId = "meal", dishId = "mac", description = "Slightly undercooked", method = "stovetop", durationMillis = 480000,
            ingredients = listOf(IngredientDraft("Cheddar", amountText = "8", amountUnit = "oz"), IngredientDraft("Gouda", amountText = "4.00", amountUnit = "oz")))
        note.validate()
        val decoded = CookNoteDraft.decode(note.encode())
        assertEquals(note, decoded)
        assertEquals(2, decoded.metadata.version)
        assertEquals("4.00", decoded.ingredients[1].amountText)
        assertEquals(1, CookNoteMetadata.decode(CookNoteMetadata().encode())!!.version)
        assertThrows(IllegalArgumentException::class.java) { note.copy(durationMillis = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { note.copy(startedAtUtcMillis = 1000, endedAtUtcMillis = 2000).validate() }
    }
    @Test fun smokerHoursExcludeOtherMethodsAndUnionSimultaneousDishes() {
        val phases = listOf(DishPhase("mac", "cooking", listOf(PhaseInterval("cooking", 0, 300)), 0),
            DishPhase("pork", "cooking", listOf(PhaseInterval("cooking", 50, 150)), 50))
        val methods = listOf(MethodEvent("smoke", "mac", 0, "smoker"), MethodEvent("oven", "mac", 100, "oven"), MethodEvent("return", "mac", 200, "smoker"))
        assertEquals(250L, CookingMethods.smokerMillis(phases, methods))
        assertEquals(300L, CookingMethods.smokerMillis(phases, emptyList()))
        assertEquals(100L, CookingMethods.smokerMillis(phases, listOf(MethodEvent("stove", "mac", 0, "stovetop"))))
    }
}
