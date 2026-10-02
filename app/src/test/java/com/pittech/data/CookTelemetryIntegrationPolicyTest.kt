package com.pittech.data

import com.pittech.devices.CookTelemetryPolicy
import com.pittech.devices.PolarisPayload
import com.pittech.devices.PolarisSample
import com.pittech.ui.TemperaturePlot
import org.junit.Assert.*
import org.junit.Test

class CookTelemetryIntegrationPolicyTest {
    private fun sample(values: Map<String, Double>) = PolarisSample(100_000, PolarisPayload(values, values.keys.toList(), 0, 0))
    private fun reading(id: String, time: Long, value: Double = 150.0, quality: String = "valid", dish: String? = "dish", source: String = "controller_cloud") =
        SensorReadingEntity(id, "cook", dish, "probe", "Probe 1", "food_probe", value, "°F", time, "UTC", source, qualityStatus = quality, recordedAtUtcMillis = time, timestampBasis = if (source == "controller_cloud") "cloud_receipt" else "measurement")

    @Test fun knownUnitsConvertAndUnknownUnitsDoNotBecomeMeasurements() {
        val data = sample(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to 212.0, "probeP1Measured" to 150.0))
        assertEquals(100.0, CookTelemetryPolicy.values(data, "°C").first { it.name == "Chamber" }.value, 0.0001)
        assertTrue(CookTelemetryPolicy.values(sample(mapOf("furnaceTempMeasured" to 212.0)), "°F").isEmpty())
        assertTrue(CookTelemetryPolicy.values(sample(mapOf("tempUnit" to 0.5, "furnaceTempMeasured" to 212.0)), "°F").isEmpty())
        assertEquals(212.0, CookTelemetryPolicy.convert(100.0, "°C", "°F"), 0.001)
    }
    @Test fun zeroAndMissingChannelsRemainUnavailableAndAlarmArmingDoesNotIndicateConnection() {
        val values = CookTelemetryPolicy.values(sample(mapOf("tempUnit" to 0.0, "probeP1Measured" to 0.0, "probeP1Status" to 1.0, "probeP2Measured" to 120.0, "probeP2Status" to 0.0)), "°F")
        assertEquals("unavailable", values.first { it.name == "Probe 1" }.quality)
        assertEquals("valid", values.first { it.name == "Probe 2" }.quality)
        assertEquals("unavailable", values.first { it.name == "Chamber" }.quality)
    }
    @Test fun localBindingIsDeterministicAndDoesNotContainTheCloudIdentifier() {
        val key = CookTelemetryPolicy.deviceKey("private-controller-id")
        assertEquals(64, key.length)
        assertFalse(key.contains("private"))
        assertEquals(key, CookTelemetryPolicy.deviceKey("private-controller-id"))
        assertNotEquals(key, CookTelemetryPolicy.deviceKey("other"))
    }
    @Test fun contextUsesOnlyPrecedingRecentValidReadingsForTheChosenDish() {
        val rows = listOf(reading("recent", 90_000), reading("future", 100_001, 190.0), reading("stale", 40_000, 80.0),
            reading("other", 99_000, 170.0, dish = "other").copy(probeId = "other-probe"),
            reading("invalid", 99_000, quality = "unavailable").copy(probeId = "unavailable-probe"))
        val chosen = TemperatureContext.select(rows, 100_000, "dish")
        assertEquals(1, chosen.size)
        assertEquals(150.0, chosen.single().value, 0.01)
        assertEquals(90_000, chosen.single().observedAtUtcMillis)
        assertTrue(TemperatureContext.select(rows, 30_000, "dish").isEmpty())
        assertTrue(TemperatureContext.select(rows + reading("unplugged", 99_999, quality = "unavailable"), 100_000, "dish").isEmpty())
        assertTrue(TemperatureContext.select(rows + reading("moved", 99_999, dish = "other"), 100_000, "dish").isEmpty())
        assertTrue(TemperatureContext.select(listOf(reading("unrepresentable", 90_000, 2_000.0)), 100_000, "dish").isEmpty())
    }
    @Test fun snapshotsKeepCopiedValuesAndSourceTimes() {
        val row = reading("sample", 90_000)
        val json = TemperatureContext.encode(TemperatureContext.select(listOf(row), 100_000, "dish"))!!
        assertEquals(150.0, TemperatureContext.decode(json).single().value, 0.01)
        assertFalse(json.contains("cook"))
        assertFalse(json.contains("probe\""))
        assertEquals("cloud_receipt", TemperatureContext.decode(json).single().timestampBasis)
        assertTrue(TemperatureContext.decode("{bad-json").isEmpty())
    }
    @Test fun graphSplitsForUnavailableReadingsGapsAndProbeMoves() {
        val rows = listOf(reading("1", 0), reading("2", 15_000, quality = "unavailable"), reading("3", 30_000), reading("4", 90_000), reading("5", 105_000, dish = "second"))
        assertEquals(listOf(listOf("1"), listOf("3"), listOf("4"), listOf("5")), TemperaturePlot.segments(rows).map { it.map { row -> row.id } })
    }
    @Test fun chartReductionKeepsExtremesAndEndpointsWithoutChangingStoredData() {
        val rows = (0..999).map { reading(it.toString(), it * 15_000L, if (it == 543) 300.0 else 150.0) }
        val reduced = TemperaturePlot.reduce(rows)
        assertTrue(reduced.size < 450)
        assertEquals(rows.first(), reduced.first())
        assertEquals(rows.last(), reduced.last())
        assertTrue(reduced.any { it.value == 300.0 })
        assertEquals(1000, rows.size)
    }
}
