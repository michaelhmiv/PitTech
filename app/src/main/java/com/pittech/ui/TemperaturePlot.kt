package com.pittech.ui

import com.pittech.data.SensorReadingEntity
import com.pittech.devices.GrillSamplingPolicy

internal object TemperaturePlot {
    /** Stable probe/dish identities separate curves; unavailable samples split them. */
    fun segments(readings: List<SensorReadingEntity>): List<List<SensorReadingEntity>> = readings
        .groupBy { "${it.cookId}:${it.probeId ?: it.probeName}:${it.source}" }.values.flatMap { rows ->
            val segments = mutableListOf<List<SensorReadingEntity>>()
            var current = mutableListOf<SensorReadingEntity>()
            fun flush() { if (current.isNotEmpty()) segments += current.toList(); current = mutableListOf() }
            rows.sortedBy { it.measuredAtUtcMillis }.forEach { row ->
                val previous = current.lastOrNull()
                if (row.qualityStatus != "valid" || !row.value.isFinite() || row.unit !in setOf("°F", "°C")) flush()
                else {
                    if (previous != null && (previous.dishId != row.dishId ||
                        (row.source == "controller_cloud" && (row.samplingIntervalMillis == 0L || previous.samplingIntervalMillis == 0L ||
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
            listOf(bucket.minBy { it.value }, bucket.maxBy { it.value }).distinctBy { it.id }.sortedBy { it.measuredAtUtcMillis }
        } + segment.last()).distinctBy { it.id }.sortedBy { it.measuredAtUtcMillis }
    }
}
