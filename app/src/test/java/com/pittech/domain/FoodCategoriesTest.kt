package com.pittech.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FoodCategoriesTest {
    @Test fun taxonomyMatchesTheSharedContractFixtureExactlyInOrder() {
        val raw = javaClass.classLoader!!.getResourceAsStream("food-categories-v1.json")!!.bufferedReader().use { it.readText() }
        val file = JSONObject(raw)
        assertEquals(1, file.getInt("version"))
        val categories = file.getJSONArray("categories")
        assertEquals(categories.length(), FoodCategories.all.size)
        for (i in 0 until categories.length()) {
            val row = categories.getJSONObject(i)
            val category = FoodCategories.all[i]
            assertEquals(row.getString("id"), category.id)
            assertEquals(row.getString("label"), category.label)
            val examples = row.getJSONArray("examples")
            assertEquals((0 until examples.length()).map { examples.getString(it) }, category.examples)
        }
        assertEquals("other", FoodCategories.all.last().id)
        assertEquals(FoodCategories.DEFAULT_ID, FoodCategories.all.last().id)
        assertEquals(FoodCategories.all.map { it.id }.toSet(), FoodCategories.ids)
    }

    @Test fun labelsFallBackToOtherForMissingOrUnknownIds() {
        assertEquals("Beef", FoodCategories.labelFor("beef"))
        assertEquals("Other", FoodCategories.labelFor(null))
        assertEquals("Other", FoodCategories.labelFor(""))
        assertEquals("Other", FoodCategories.labelFor("barbecue"))
        assertEquals("other", FoodCategories.normalizedId("barbecue"))
        assertEquals("pork", FoodCategories.normalizedId("pork"))
    }

    @Test fun suggestionMapsNativeAndFreeTextFoodTypes() {
        val cases = mapOf(
            "Beef" to "beef",
            "Pork" to "pork",
            "pork ribs" to "pork",
            "Chicken" to "chicken",
            "Turkey" to "turkey",
            "Poultry" to "chicken",
            "Seafood" to "seafood",
            "fish" to "seafood",
            "Salmon" to "seafood",
            "shrimp" to "seafood",
            "Wild game" to "lamb-game",
            "game" to "lamb-game",
            "Lamb" to "lamb-game",
            "venison" to "lamb-game",
            "duck" to "lamb-game",
            "Sausage" to "sausage-links",
            "Vegetables" to "veggies",
            "veggie tray" to "veggies",
            "Dessert" to "desserts",
            "side dish" to "sides",
            "Snack" to "apps-snacks",
            "appetizer" to "apps-snacks",
            "Other" to "other",
            "Tofu" to "other",
            "" to "other",
        )
        for ((foodType, expected) in cases) assertEquals("foodType=$foodType", expected, FoodCategories.suggestedIdFor(foodType))
        assertEquals("other", FoodCategories.suggestedIdFor(null))
    }
}
