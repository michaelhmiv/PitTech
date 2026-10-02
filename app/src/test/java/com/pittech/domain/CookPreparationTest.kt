package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookPreparationTest {
    @Test fun checklistCombinesOnlyMeasuredLikeIngredients() {
        val rows = CookPreparation.ingredientChecklist(listOf(
            IngredientDraft("Salt", amountText = "2", amountUnit = "tsp"), IngredientDraft("Salt", amountText = "3", amountUnit = "tsp"),
            IngredientDraft("Salt", amountText = "10", amountUnit = "g"), IngredientDraft("Salt", amountText = "", amountUnit = "tsp"), IngredientDraft("Salt", brand = "Other", amountText = "2", amountUnit = "tsp")))
        assertEquals(4, rows.size)
        assertTrue(rows.any { it.text == "Salt · 5.0 tsp" })
        assertEquals(rows, CookPreparation.decodeChecklist(CookPreparation.encodeChecklist(rows)))
    }
    @Test fun recipeScalingRequiresExplicitYieldAndMeasuredIngredients() {
        val combo = PrepCombo("Rub", listOf(IngredientDraft("Pepper", amountText = "2", amountUnit = "tbsp")), 4.0, "servings")
        assertEquals(combo, CookPreparation.decodeCombo(CookPreparation.encodeCombo(combo)))
        assertEquals("4.0", CookPreparation.scale(combo, 8.0, "servings").single().amountText)
        assertThrows(IllegalArgumentException::class.java) { CookPreparation.scale(combo, 8.0, "lb") }
        assertThrows(IllegalArgumentException::class.java) { CookPreparation.scale(combo.copy(items = listOf(IngredientDraft("Pepper", amountText = "", amountUnit = "tbsp"))), 8.0, "servings") }
    }
    @Test fun concurrentDishesCountSmokerTimeOnceAndRestIsExcluded() {
        val h = 3_600_000L
        val events = listOf(PlanEvent("a", "food_on", "a", 0), PlanEvent("b", "food_on", "b", h), PlanEvent("r", "rest_start", "a", 2*h), PlanEvent("off", "remove", "b", 3*h), PlanEvent("done", "dish_done", "a", 4*h))
        assertEquals(3.0, CookPreparation.cookingHours(events, listOf("a", "b"), 5*h), 0.001)
        assertEquals(0.0, CookPreparation.cookingHours(emptyList(), listOf("a"), 5*h), 0.001)
    }
    @Test fun fuelAndEquipmentRoundTripWithValidatedUnits() {
        val profile = EquipmentProfile("Backyard", "Pellets", "Oak", 20.0, 4.0, 1000)
        assertEquals(profile, CookPreparation.decodeEquipment(CookPreparation.encodeEquipment(profile)))
        val fuel = FuelEntry("Backyard", 4.0, "cook", 1000, "used")
        assertEquals(fuel, CookPreparation.decodeFuel(CookPreparation.encodeFuel(fuel)))
        assertEquals(1.0, ServeTimePlanner.weightKg(1000.0, "g")!!, 0.00001)
        assertEquals(1.0, ServeTimePlanner.weightKg(35.27396195, "oz")!!, 0.00001)
        assertThrows(IllegalArgumentException::class.java) { CookPreparation.encodeEquipment(profile.copy(cleanEveryHours = -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { CookPreparation.encodeFuel(fuel.copy(kilograms = Double.NaN)) }
    }
}
