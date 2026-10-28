package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookInputsTest {
    private fun rowsOf(inputs: CookInputs, cookId: String = "c1", dishId: String? = "d1"): List<InsightResultInput> =
        CookInputVocabulary.rows(inputs).mapIndexed { i, row ->
            InsightResultInput("r$i", cookId, dishId, row.resultType, null, row.textValue, 1_000L + i)
        }

    @Test fun roundTripPreservesEveryField() {
        val inputs = CookInputs(WrapMaterial.FOIL, "Killer Hogs AP", SpritzState.YES, "Apple juice")
        assertEquals(inputs, CookInputVocabulary.fromResults(rowsOf(inputs), "c1", "d1"))
    }

    @Test fun absenceIsNotRecordedNeverAValue() {
        val read = CookInputVocabulary.fromResults(emptyList(), "c1", "d1")
        assertEquals(CookInputs.NOT_RECORDED, read)
        assertEquals("Not recorded", CookInputVocabulary.NOT_RECORDED_LABEL)
    }

    @Test fun dishScopedRowsWinOverCookScopedRows() {
        val rows = rowsOf(CookInputs(wrapMaterial = WrapMaterial.FOIL), dishId = null) +
            rowsOf(CookInputs(wrapMaterial = WrapMaterial.BUTCHER_PAPER), dishId = "d1")
        assertEquals(WrapMaterial.BUTCHER_PAPER, CookInputVocabulary.fromResults(rows, "c1", "d1").wrapMaterial)
    }

    @Test fun aLoggedSpritzActionMeansYesEvenWithoutAMedium() {
        val read = CookInputVocabulary.fromResults(emptyList(), "c1", "d1", hasSpritzEvent = true)
        assertEquals(SpritzState.YES, read.spritz)
        assertNull(read.spritzMedium)
    }

    @Test fun anExplicitNoOutranksTheEvent() {
        val rows = rowsOf(CookInputs(spritz = SpritzState.NO), dishId = "d1")
        assertEquals(SpritzState.NO, CookInputVocabulary.fromResults(rows, "c1", "d1", hasSpritzEvent = true).spritz)
    }

    @Test fun wrapHintsComeFromWholeWordsOnly() {
        assertEquals(WrapMaterial.FOIL, CookInputVocabulary.wrapMaterialHint(listOf("Wrap in foil at the stall")))
        assertEquals(WrapMaterial.FOIL, CookInputVocabulary.wrapMaterialHint(listOf("foil boat")))
        assertEquals(WrapMaterial.BUTCHER_PAPER, CookInputVocabulary.wrapMaterialHint(listOf("Butcher paper at 165")))
        assertEquals(WrapMaterial.NONE, CookInputVocabulary.wrapMaterialHint(listOf("No wrap, hot and fast")))
        assertNull(CookInputVocabulary.wrapMaterialHint(listOf("Rest in a cooler")))
        assertNull(CookInputVocabulary.wrapMaterialHint(listOf("Rest tented on the counter")))
    }

    @Test fun rubComesFromTheRubStagedIngredient() {
        val ingredients = listOf(
            IngredientDraft("Salt", "seasoning"),
            IngredientDraft("Killer Hogs AP", "rub"),
        )
        assertEquals("Killer Hogs AP", CookInputVocabulary.rubFromIngredients(ingredients))
        assertNull(CookInputVocabulary.rubFromIngredients(listOf(IngredientDraft("Salt", "seasoning"))))
    }
}
