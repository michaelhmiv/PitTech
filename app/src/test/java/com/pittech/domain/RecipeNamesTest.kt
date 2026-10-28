package com.pittech.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecipeNamesTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 27)

    @Test fun freshNameIsUntouched() {
        assertEquals("Brisket", differentiatedRecipeName("Brisket", listOf("Boston Butt"), day))
        assertEquals("Boston Butt", differentiatedRecipeName("Boston Butt", emptyList(), day))
    }

    @Test fun takenNameGetsTheDateSuffix() {
        assertEquals("Boston Butt · Oct 27", differentiatedRecipeName("Boston Butt", listOf("Boston Butt"), day))
    }

    @Test fun matchingIgnoresCaseAndSurroundingSpace() {
        assertEquals("boston butt · Oct 27", differentiatedRecipeName("boston butt", listOf("  Boston Butt "), day))
    }

    @Test fun sameDayRepeatGetsACounter() {
        assertEquals(
            "Boston Butt · Oct 27 (2)",
            differentiatedRecipeName("Boston Butt", listOf("Boston Butt", "Boston Butt · Oct 27"), day),
        )
        assertEquals(
            "Boston Butt · Oct 27 (3)",
            differentiatedRecipeName("Boston Butt", listOf("Boston Butt", "Boston Butt · Oct 27", "Boston Butt · Oct 27 (2)"), day),
        )
    }
}
