package com.pittech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookEntryValidationTest {
    @Test
    fun blankOptionalMeasurementsAreAllowed() {
        assertNull(CookEntryValidation.optionalPositiveNumber(""))
        assertTrue(CookEntryValidation.isOptionalPositiveNumberValid("   "))
    }

    @Test
    fun positiveMeasurementsAreParsedWithoutRounding() {
        assertEquals(5.25, CookEntryValidation.optionalPositiveNumber("5.25")!!, 0.0)
        assertTrue(CookEntryValidation.isOptionalPositiveNumberValid("0.01"))
    }

    @Test
    fun zeroNegativeAndNonNumericMeasurementsAreRejected() {
        listOf("0", "-2", "many", "NaN", "Infinity").forEach { value ->
            assertNull("Expected $value to be rejected", CookEntryValidation.optionalPositiveNumber(value))
            assertFalse(CookEntryValidation.isOptionalPositiveNumberValid(value))
        }
    }
}
