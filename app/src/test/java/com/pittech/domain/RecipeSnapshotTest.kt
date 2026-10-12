package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class RecipeSnapshotTest {
    private val base = RecipeSnapshot("Mac", DishDraft("Mac", "Pasta", preparationItems = listOf(IngredientDraft("Gouda", amountText = "4", amountUnit = "oz"))))
    @Test fun retainsRecordedFactsAndNegativePreparationOffsetsAcrossEditing() {
        val source = RecipeObservation("prep", 1000, "America/New_York", "Boiled macaroni", "Eight minutes", null, -1000, null)
        val snapshot = base.copy(smokingAnchorUtcMillis = 2000, observations = listOf(source),
            instructions = listOf(RecipeInstruction("check", "Check bark", offsetMillis = 3600000)), targets = listOf(PlaybookTarget(0, "personal_finish", 90.0, "°C", "Check texture")))
        val edited = snapshot.copy(instructions = snapshot.instructions.map { it.copy(offsetMillis = 3000000) })
        assertEquals(snapshot, RecipeSnapshotCodec.decode(RecipeSnapshotCodec.encode(snapshot)))
        assertEquals(source, RecipeSnapshotCodec.decode(RecipeSnapshotCodec.encode(edited)).observations.single())
    }
    @Test fun missingAnchorStaysMissingAndFabricatedOffsetsAreRejected() {
        val fact = RecipeObservation("note", 1000, "UTC", "Sauce", "Milk and butter", null, null, null)
        assertNull(RecipeSnapshotCodec.decode(RecipeSnapshotCodec.encode(base.copy(observations = listOf(fact)))).observations.single().offsetMillis)
        assertThrows(IllegalArgumentException::class.java) { RecipeSnapshotCodec.encode(base.copy(observations = listOf(fact.copy(offsetMillis = 0)))) }
        assertThrows(IllegalArgumentException::class.java) { RecipeSnapshotCodec.encode(base.copy(targets = listOf(PlaybookTarget(2, "personal_finish", 90.0, "°C", null)))) }
        assertThrows(IllegalArgumentException::class.java) { RecipeSnapshotCodec.encode(base.copy(dish = base.dish.copy(preparationItems = listOf(IngredientDraft("Salt", amountText = "NaN"))))) }
    }
}
