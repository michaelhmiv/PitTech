package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SharingDraftTest {
    @Test fun referenceIdentityAndUnknownVerdictSurviveInstructionEditsAndSerialization() {
        val snapshot = RecipeSnapshot("Pork", DishDraft("Pork", "Pork"), instructions = listOf(RecipeInstruction("instruction", "Check bark")))
        val story = JSONObject().put("version", 1).put("attemptId", "actual-dish").put("anchorUtcMillis", JSONObject.NULL).put("observations", org.json.JSONArray()).put("readings", org.json.JSONArray()).put("results", org.json.JSONArray()).toString()
        val doc = SharingDocument("Pork only", listOf(SharingEntry(snapshot = snapshot, storyJson = story)))
        val edited = doc.copy(entries = doc.entries.map { it.copy(snapshot = it.snapshot.copy(instructions = listOf(RecipeInstruction("instruction", "Use more pepper")))) })
        val restored = SharingDraftCodec.decode(SharingDraftCodec.encode(edited))
        assertTrue(SharingDraftCodec.sameStory(story, restored.entries[0].storyJson))
        assertNull(restored.entries[0].snapshot.rating); assertNull(restored.entries[0].snapshot.cookAgain)
        assertEquals("Use more pepper", restored.entries[0].snapshot.instructions[0].title)
    }
    private fun categoryDocument(foodType: String, categoryId: String = FoodCategories.DEFAULT_ID): SharingDocument {
        val snapshot = RecipeSnapshot("Dish", DishDraft("Dish", foodType))
        val story = JSONObject().put("version", 1).put("attemptId", "actual-dish").put("anchorUtcMillis", JSONObject.NULL).put("observations", org.json.JSONArray()).put("readings", org.json.JSONArray()).put("results", org.json.JSONArray()).toString()
        return SharingDocument("Dish only", listOf(SharingEntry(snapshot = snapshot, storyJson = story)), categoryId = categoryId)
    }
    @Test fun categorySurvivesCodecRoundTrip() {
        val restored = SharingDraftCodec.decode(SharingDraftCodec.encode(categoryDocument("Salmon", "seafood")))
        assertEquals("seafood", restored.categoryId)
        assertEquals("seafood", JSONObject(SharingDraftCodec.encode(categoryDocument("Salmon", "seafood"))).getString("category"))
    }
    @Test fun legacyDraftWithoutCategoryDerivesFromFirstDishFoodType() {
        val legacy = JSONObject(SharingDraftCodec.encode(categoryDocument("Pork"))).apply { remove("category") }.toString()
        assertEquals("pork", SharingDraftCodec.decode(legacy).categoryId)
        val unrecognized = JSONObject(SharingDraftCodec.encode(categoryDocument("Something else"))).apply { remove("category") }.toString()
        assertEquals("other", SharingDraftCodec.decode(unrecognized).categoryId)
    }
    @Test fun invalidStoredCategoryFallsBackToTheDerivedSuggestion() {
        val stored = JSONObject(SharingDraftCodec.encode(categoryDocument("Pork"))).apply { put("category", "barbecue") }.toString()
        assertEquals("pork", SharingDraftCodec.decode(stored).categoryId)
    }
    @Test fun validationRejectsACategoryOutsideTheTaxonomy() {
        assertThrows(IllegalArgumentException::class.java) { SharingDraftCodec.encode(categoryDocument("Pork", "barbecue")) }
        assertThrows(IllegalArgumentException::class.java) { SharingDraftCodec.validate(categoryDocument("Pork", "")) }
    }
    @Test fun forgedMeasurementsAndUnselectedHeroAreRejected() {
        val snapshot = RecipeSnapshot("Pork", DishDraft("Pork", "Pork"))
        val story = """{"version":1,"attemptId":null,"readings":[{"value":1}]}"""
        assertThrows(IllegalArgumentException::class.java) { SharingDraftCodec.encode(SharingDocument("Pork", listOf(SharingEntry(snapshot = snapshot, storyJson = story)))) }
        val empty = """{"version":1,"attemptId":null,"readings":[]}"""
        assertThrows(IllegalArgumentException::class.java) { SharingDraftCodec.encode(SharingDocument("Pork", listOf(SharingEntry(snapshot = snapshot, storyJson = empty)), heroPhotoId = "unselected")) }
    }
}
