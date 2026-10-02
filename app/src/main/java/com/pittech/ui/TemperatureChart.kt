package com.pittech.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TimelineEventEntity
import com.pittech.devices.CookTelemetryPolicy
import java.util.Locale
import kotlin.math.abs

@Composable
fun TemperatureChart(
    readings: List<SensorReadingEntity>,
    modifier: Modifier = Modifier,
    cookNames: Map<String, String> = emptyMap(),
    cookStartTimes: Map<String, Long> = emptyMap(),
    dishNames: Map<String, String> = emptyMap(),
    markers: List<TimelineEventEntity> = emptyList(),
    onMarker: ((TimelineEventEntity) -> Unit)? = null,
    chartHeight: Dp = 230.dp,
) {
    val valid = readings.filter { it.qualityStatus == "valid" && it.value.isFinite() && it.unit in setOf("°F", "°C") }
    if (valid.size < 2) {
        Text("Add at least two temperature readings to see a chart.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 16.dp))
        return
    }
    val unit = valid.first().unit
    val firstTime = valid.minOf { it.measuredAtUtcMillis }
    fun elapsed(cookId: String, time: Long) = (time - (cookStartTimes[cookId] ?: firstTime)).coerceAtLeast(0L).toDouble()
    fun label(reading: SensorReadingEntity) = buildList {
        cookNames[reading.cookId]?.let(::add)
        add(reading.probeName)
        reading.dishId?.let { dishNames[it] }?.let(::add)
    }.joinToString(" · ")
    val segments = TemperaturePlot.segments(readings).map { TemperaturePlot.reduce(it).map { reading ->
        PlotPoint(label(reading), elapsed(reading.cookId, reading.measuredAtUtcMillis), CookTelemetryPolicy.convert(reading.value, reading.unit, unit))
    } }
    val data = segments.flatten()
    if (data.isEmpty()) return
    val markerPoints = markers.map { it to elapsed(it.cookId, it.occurredAtUtcMillis) }
    val minX = minOf(data.minOf { it.x }, markerPoints.minOfOrNull { it.second } ?: Double.MAX_VALUE)
    val maxX = maxOf(data.maxOf { it.x }, markerPoints.maxOfOrNull { it.second } ?: 0.0).let { if (it <= minX) minX + 1 else it }
    val minY = data.minOf { it.y }
    val maxY = data.maxOf { it.y }.let { if (it <= minY) minY + 1 else it }
    val names = data.map { it.name }.distinct().sorted()
    val chartColors = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, Color(0xFF83B6CE), Color(0xFFC29BCD), Color(0xFFE5BE6A), Color(0xFF91B58A))
    else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, Color(0xFF416C8A), Color(0xFF805E8B), Color(0xFF9A6B21), Color(0xFF365A43))
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Temperature over time · $unit", style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(chartHeight).testTag("temperature-chart").pointerInput(markerPoints, minX, maxX) {
            detectTapGestures { tap ->
                val x = minX + ((tap.x - 12.dp.toPx()) / (size.width - 20.dp.toPx())).coerceIn(0f, 1f) * (maxX - minX)
                markerPoints.minByOrNull { abs(it.second - x) }?.takeIf { abs(it.second - x) / (maxX - minX) < 0.06 }?.let { onMarker?.invoke(it.first) }
            }
        }) {
            val left = 12.dp.toPx(); val right = size.width - 8.dp.toPx()
            val top = 10.dp.toPx(); val bottom = size.height - 12.dp.toPx()
            fun position(point: PlotPoint) = Offset(left + ((point.x - minX) / (maxX - minX) * (right - left)).toFloat(), bottom - ((point.y - minY) / (maxY - minY) * (bottom - top)).toFloat())
            repeat(4) { index ->
                val y = top + (bottom - top) * index / 3f
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
            markerPoints.forEach { (_, time) ->
                val x = left + ((time - minX) / (maxX - minX) * (right - left)).toFloat()
                drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx())
                drawCircle(gridColor, 4.dp.toPx(), Offset(x, top))
            }
            segments.forEach { segment ->
                val color = chartColors[names.indexOf(segment.first().name) % chartColors.size]
                segment.zipWithNext().forEach { (a, b) -> drawLine(color, position(a), position(b), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                segment.forEach { drawCircle(color, 2.dp.toPx(), position(it)) }
            }
        }
        Text("${String.format(Locale.US, "%.0f", minY)}–${String.format(Locale.US, "%.0f", maxY)} $unit · ${((maxX - minX) / 60_000).toInt()} minutes shown", style = MaterialTheme.typography.labelSmall)
        names.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { name -> Text("● $name", color = chartColors[names.indexOf(name) % chartColors.size], fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f)) }
        } }
        if (markers.isNotEmpty() && onMarker != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            markers.sortedBy { it.occurredAtUtcMillis }.forEach { event ->
                TextButton(onClick = { onMarker(event) }, modifier = Modifier.testTag("chart-marker-${event.id}")) { Text(event.title, maxLines = 1) }
            }
        }
    }
}

private data class PlotPoint(val name: String, val x: Double, val y: Double)
