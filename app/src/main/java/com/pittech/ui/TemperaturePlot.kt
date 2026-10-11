package com.pittech.ui

import com.pittech.data.SensorReadingEntity
import com.pittech.data.TimelineEventEntity
import com.pittech.devices.GrillSamplingPolicy

internal object TemperaturePlot {
    fun identity(r: SensorReadingEntity) = listOf(r.cookId, r.probeId ?: r.probeName, r.sourceDeviceId.orEmpty(), r.dishId.orEmpty(), r.measurementType, r.source).joinToString(":")
    /** Stable probe/dish identities separate curves; unavailable samples split them. */
    fun segments(readings: List<SensorReadingEntity>, gapEvents: List<TimelineEventEntity> = emptyList()): List<List<SensorReadingEntity>> = readings
        .groupBy { identity(it) }.values.flatMap { rows ->
            val gaps = gapEvents.filter { it.cookId == rows.first().cookId && it.source == "controller_cloud" && it.eventType == "connection_gap" }.map { it.occurredAtUtcMillis }
            val segments = mutableListOf<List<SensorReadingEntity>>()
            var current = mutableListOf<SensorReadingEntity>()
            fun flush() { if (current.isNotEmpty()) segments += current.toList(); current = mutableListOf() }
            rows.sortedBy { it.measuredAtUtcMillis }.forEach { row ->
                val previous = current.lastOrNull()
                if (row.qualityStatus != "valid" || !row.value.isFinite() || row.unit !in setOf("°F", "°C")) flush()
                else {
                    if (previous != null && (previous.dishId != row.dishId ||
                        (row.source == "controller_cloud" && (gaps.any { it > previous.measuredAtUtcMillis && it <= row.measuredAtUtcMillis } ||
                            row.samplingIntervalMillis == 0L || previous.samplingIntervalMillis == 0L ||
                            row.measuredAtUtcMillis - previous.measuredAtUtcMillis > GrillSamplingPolicy.receiptWindow(maxOf(row.samplingIntervalMillis, previous.samplingIntervalMillis)))))) flush()
                    current += row
                }
            }
            flush()
            segments
        }

    fun reduce(segment: List<SensorReadingEntity>): List<SensorReadingEntity> {
        if (segment.size <= 400) return segment
        val bucketSize = (segment.size + 199) / 200
        return (listOf(segment.first()) + segment.chunked(bucketSize).flatMap { bucket ->
            listOf(bucket.minBy { com.pittech.domain.CookPlanEngine.fahrenheit(it.value, it.unit) }, bucket.maxBy { com.pittech.domain.CookPlanEngine.fahrenheit(it.value, it.unit) }).distinctBy { it.id }.sortedBy { it.measuredAtUtcMillis }
        } + segment.last()).distinctBy { it.id }.sortedBy { it.measuredAtUtcMillis }
    }
}
