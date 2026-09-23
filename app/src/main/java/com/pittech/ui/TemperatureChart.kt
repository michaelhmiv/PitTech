package com.pittech.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pittech.data.SensorReadingEntity

@Composable
fun TemperatureChart(
    readings: List<SensorReadingEntity>,
    modifier: Modifier = Modifier,
    cookNames: Map<String, String> = emptyMap(),
    cookStartTimes: Map<String, Long> = emptyMap(),
) {
    val valid = readings.filter { it.qualityStatus == "valid" && it.value.isFinite() }
    if (valid.size < 2) {
        Text(
            "Add at least two temperature readings to see a chart.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 16.dp),
        )
        return
    }
    val unit = valid.first().unit
    val data = valid.filter { it.unit == unit }.map { reading ->
        val elapsed = reading.measuredAtUtcMillis - (cookStartTimes[reading.cookId] ?: valid.minOf { it.measuredAtUtcMillis })
        val series = if (cookNames.isEmpty()) reading.probeName else "${cookNames[reading.cookId] ?: "Cook"} · ${reading.probeName}"
        PlotPoint(series, elapsed.coerceAtLeast(0L).toDouble(), reading.value, reading.measuredAtUtcMillis)
    }
    val minX = data.minOf { it.x }
    val maxX = data.maxOf { it.x }.let { if (it <= minX) minX + 1 else it }
    val minY = data.minOf { it.y }
    val maxY = data.maxOf { it.y }.let { if (it <= minY) minY + 1 else it }
    val series = data.groupBy { it.name }.toSortedMap()
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        Color(0xFF416C8A),
        Color(0xFF805E8B),
        Color(0xFF9A6B21),
        Color(0xFF365A43),
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Temperature over time · $unit", style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            val left = 12.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 12.dp.toPx()
            repeat(4) { index ->
                val y = top + (bottom - top) * index / 3f
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
            series.values.forEachIndexed { index, points ->
                val color = colors[index % colors.size]
                val sorted = points.sortedBy { it.x }
                sorted.zipWithNext().forEach { (a, b) ->
                    val start = Offset(
                        left + ((a.x - minX) / (maxX - minX) * (right - left)).toFloat(),
                        bottom - ((a.y - minY) / (maxY - minY) * (bottom - top)).toFloat(),
                    )
                    val end = Offset(
                        left + ((b.x - minX) / (maxX - minX) * (right - left)).toFloat(),
                        bottom - ((b.y - minY) / (maxY - minY) * (bottom - top)).toFloat(),
                    )
                    drawLine(color, start, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
                sorted.forEach { point ->
                    val position = Offset(
                        left + ((point.x - minX) / (maxX - minX) * (right - left)).toFloat(),
                        bottom - ((point.y - minY) / (maxY - minY) * (bottom - top)).toFloat(),
                    )
                    drawCircle(color, radius = 3.dp.toPx(), center = position)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.keys.forEachIndexed { index, name ->
                Text(
                    "● $name",
                    color = colors[index % colors.size],
                    fontSize = 13.sp,
                    maxLines = 2,
                )
            }
        }
        if (valid.map { it.unit }.distinct().size > 1) {
            Text("This chart shows $unit readings. Other units remain in the log and exports.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class PlotPoint(val name: String, val x: Double, val y: Double, val timestamp: Long)
