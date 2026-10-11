package com.pittech.ui

import com.pittech.data.SensorReadingEntity
import org.junit.Assert.*
import org.junit.Test

class CookChartMathTest {
    private fun r(id: String, value: Double, at: Long, unit: String = "°F", probe: String = "p1") = SensorReadingEntity(id, "cook", probeId = probe, probeName = "Same name", measurementType = "food", value = value, unit = unit, measuredAtUtcMillis = at, timeZoneId = "UTC", source = "manual", recordedAtUtcMillis = at)
    @Test fun numericAxisTicksCoverFlatAndNegativeRanges() {
        val ticks = CookChartMath.ticks(165.0, 165.0)
        assertTrue(ticks.size >= 2)
        assertTrue(ticks.first() <= 165 && ticks.last() >= 165)
        assertTrue(CookChartMath.ticks(-30.0, 200.0).first() <= -30)
    }
    @Test fun slopeNormalizesMixedUnitsAndRequiresContiguousDuration() {
        val values = listOf(r("a", 100.0, 0), r("b", 40.0, 300_000, "°C"), r("c", 108.0, 600_000))
        assertEquals(.8, CookChartMath.slopePerMinute(values, "°F")!!, .00001)
        assertEquals(.8 * 5 / 9, CookChartMath.slopePerMinute(values, "°C")!!, .00001)
        assertNull(CookChartMath.slopePerMinute(values.take(2), "°F"))
        assertNull(CookChartMath.slopePerMinute(listOf(r("a", 100.0, 0), r("b", 110.0, 1_800_000), r("c", 120.0, 3_600_000)), "°F"))
    }
    @Test fun identicalNamesStaySeparateAndRenamingKeepsIdentity() {
        val first = r("a", 160.0, 0)
        assertNotEquals(TemperaturePlot.identity(first), TemperaturePlot.identity(first.copy(probeId = "p2")))
        assertEquals(TemperaturePlot.identity(first), TemperaturePlot.identity(first.copy(probeName = "Renamed")))
        assertNotEquals(TemperaturePlot.identity(first), TemperaturePlot.identity(first.copy(dishId = "other")))
        assertNotEquals(TemperaturePlot.identity(first), TemperaturePlot.identity(first.copy(measurementType = "pit")))
    }
    @Test fun downsamplingPreservesNormalizedExtremaAndAnnotationsClusterWithoutLosingIds() {
        val values = (0..600).map { r("$it", if (it == 302) 150.0 else 200.0, it * 1000L, if (it == 302) "°C" else "°F") }
        assertTrue(TemperaturePlot.reduce(values).any { it.id == "302" })
        val clusters = CookChartMath.clusterTimes(listOf("a" to 10.0, "b" to 11.0, "c" to 90.0), 0.0, 100.0, 300.0)
        assertEquals(listOf("a", "b"), clusters.first().map { it.first })
        assertEquals(3, clusters.flatten().size)
    }
}
