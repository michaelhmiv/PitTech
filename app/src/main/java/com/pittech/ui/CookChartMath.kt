package com.pittech.ui

import com.pittech.data.SensorReadingEntity
import com.pittech.domain.CookPlanEngine
import kotlin.math.*

internal object CookChartMath {
    fun ticks(minimum: Double, maximum: Double, count: Int = 5): List<Double> {
        val span = (maximum - minimum).coerceAtLeast(1.0)
        val base = 10.0.pow(floor(log10(span / count)))
        val raw = span / count / base
        val step = (when { raw <= 1 -> 1.0; raw <= 2 -> 2.0; raw <= 5 -> 5.0; else -> 10.0 }) * base
        val low = floor(minimum / step) * step; val high = ceil(maximum / step) * step
        return (0..((high - low) / step).roundToInt()).map { low + it * step }
    }
    /** OLS on accepted contiguous samples only; no prediction or gap interpolation. */
    fun slopePerMinute(segment: List<SensorReadingEntity>, unit: String): Double? {
        val sorted = segment.filter { it.qualityStatus == "valid" && it.value.isFinite() }.sortedBy { it.measuredAtUtcMillis }
        if (sorted.size < 3 || sorted.last().measuredAtUtcMillis - sorted.first().measuredAtUtcMillis < 600_000) return null
        val start = sorted.first().measuredAtUtcMillis
        val xs = sorted.map { (it.measuredAtUtcMillis - start) / 60_000.0 }
        val ys = sorted.map { val f = CookPlanEngine.fahrenheit(it.value, it.unit); if (unit == "°C") (f - 32) * 5 / 9 else f }
        val mx = xs.average(); val my = ys.average()
        val denominator = xs.sumOf { (it - mx).pow(2) }
        return if (denominator == 0.0) null else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / denominator
    }
    fun clusterTimes(times: List<Pair<String, Double>>, min: Double, max: Double, pixels: Double): List<List<Pair<String, Double>>> {
        val groups = mutableListOf<MutableList<Pair<String, Double>>>()
        times.filter { it.second in min..max }.sortedWith(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first }).forEach { item ->
            val last = groups.lastOrNull()
            if (last != null && (item.second - last.first().second) / (max - min).coerceAtLeast(1.0) * pixels < 24) last += item
            else groups += mutableListOf(item)
        }
        return groups
    }
}
