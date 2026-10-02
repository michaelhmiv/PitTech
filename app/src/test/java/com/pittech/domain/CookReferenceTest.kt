package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookReferenceTest {
    @Test fun alignmentUsesActualDishAnchorAndConvertsUnitsWithoutInterpolation() {
        val events = listOf(PlanEvent("other", "food_on", "other", 1_000), PlanEvent("on", "food_on", "flat", 100_000))
        val anchor = CookReferenceCodec.anchor(events, "flat", "food_on")!!
        val points = CookReferenceCodec.align(listOf(CurveSample(50.0, "°C", 160_000), CurveSample(160.0, "°F", 220_000), CurveSample(99.0, "°F", 90_000), CurveSample(180.0, "°F", 280_000, false)), anchor)
        assertEquals(listOf(AlignedSample(1.0, 122.0), AlignedSample(2.0, 160.0)), points)
        val reference = CookReference("flat", "source", "source-flat", "Probe 1", "Probe 2", "wrap")
        assertEquals(reference, CookReferenceCodec.decode(CookReferenceCodec.encode(reference)))
    }
}
