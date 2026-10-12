package com.pittech.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pittech.data.*
import com.pittech.domain.*
import com.pittech.devices.CookTelemetryPolicy
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@Composable
fun TemperatureChart(readings: List<SensorReadingEntity>, modifier: Modifier = Modifier,
    cookNames: Map<String, String> = emptyMap(), cookStartTimes: Map<String, Long> = emptyMap(), dishNames: Map<String, String> = emptyMap(),
    markers: List<TimelineEventEntity> = emptyList(), onMarker: ((TimelineEventEntity) -> Unit)? = null, chartHeight: Dp = 260.dp,
    connectionGaps: List<TimelineEventEntity> = emptyList(), displayUnit: String? = null,
    targets: List<TargetEntity> = emptyList(), targetHistory: List<TargetHistory> = emptyList(),
    photos: List<PhotoEntity> = emptyList(), interactive: Boolean = true, timingMode: String = "legacy_preserved") {
    val valid = readings.filter { it.qualityStatus == "valid" && it.value.isFinite() && it.unit in setOf("°F", "°C") }
    val unit = displayUnit?.takeIf { it in setOf("°F", "°C") } ?: valid.firstOrNull()?.unit ?: "°F"
    var clockMode by rememberSaveable { mutableStateOf(false) }
    var markerFilter by rememberSaveable { mutableStateOf("All") }
    var selectedDish by rememberSaveable { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var windowStart by remember { mutableStateOf<Double?>(null) }
    var windowSpan by remember { mutableStateOf<Double?>(null) }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var plotWidth by remember { mutableFloatStateOf(300f) }
    val selectedMarkers = markers.filter { e ->
        val m = CookNoteMetadata.decode(e.noteMetadataJson)
        val scoped = selectedDish == null || e.dishId == null && (m?.dishIds.isNullOrEmpty() || selectedDish in m!!.dishIds) || e.dishId == selectedDish || selectedDish in m?.dishIds.orEmpty()
        scoped && when (markerFilter) { "Notes" -> e.eventType == "note"; "Photos" -> photos.any { it.eventId == e.id } || e.eventType == "photo"; "Actions" -> PlaybookCodec.action(e.eventType, e.noteMetadataJson) in CookNoteMetadata.actions; else -> true }
    }
    val plans = notePlanEvents(markers)
    val firstTime = valid.minOfOrNull { it.measuredAtUtcMillis } ?: markers.minOfOrNull { it.occurredAtUtcMillis } ?: System.currentTimeMillis()
    fun anchor(cookId: String): Long = if (cookNames.size > 1) cookStartTimes[cookId] ?: firstTime else
        plans.filter { it.action == "food_on" && (selectedDish == null || it.dishId == null || it.dishId == selectedDish) }.minOfOrNull { it.at } ?: cookStartTimes[cookId] ?: firstTime
    fun x(cookId: String, time: Long) = if (clockMode && cookNames.size <= 1) time.toDouble() else (time - anchor(cookId)).toDouble()
    fun y(value: Double, rawUnit: String) = CookTelemetryPolicy.convert(value, rawUnit, unit)
    fun label(r: SensorReadingEntity) = listOfNotNull(cookNames[r.cookId], r.probeName, r.dishId?.let { dishNames[it] }, r.measurementType.replace('_', ' ')).joinToString(" · ")
    val rawSegments = remember(readings, markers, connectionGaps) { TemperaturePlot.segments(readings, markers + connectionGaps) }
    val series = valid.filter { selectedDish == null || it.dishId == selectedDish || it.measurementType in setOf("pit", "pit_ambient", "ambient_temperature", "setpoint") }.groupBy { TemperaturePlot.identity(it) }
    val visibleSegments = rawSegments.filter { it.isNotEmpty() && TemperaturePlot.identity(it.first()) in series && TemperaturePlot.identity(it.first()) !in hidden }
    val points = visibleSegments.flatten()
    val allX = valid.map { x(it.cookId, it.measuredAtUtcMillis) } + markers.map { x(it.cookId, it.occurredAtUtcMillis) }
    val fullMin = allX.minOrNull() ?: x("", firstTime); val fullMax = maxOf(allX.maxOrNull() ?: fullMin, fullMin + 60_000)
    val fullSpan = fullMax - fullMin
    val span = (windowSpan ?: fullSpan).coerceIn(minOf(60_000.0, fullSpan), fullSpan)
    val minX = if (followLatest) fullMax - span else (windowStart ?: fullMin).coerceIn(fullMin, fullMax - span)
    val maxX = minX + span
    val applicableTargets = targets.filter { selectedDish == null || it.dishId == null || it.dishId == selectedDish }
    val applicableHistory = targetHistory.filter { !it.targetType.startsWith("removed_dish_") && (selectedDish == null || it.dishId == null || it.dishId == selectedDish) }
    val values = points.filter { x(it.cookId, it.measuredAtUtcMillis) in minX..maxX }.map { y(it.value, it.unit) } + applicableTargets.map { y(it.value, it.unit) } + applicableHistory.map { y(it.value, it.unit) }
    val yTicks = CookChartMath.ticks((values.minOrNull() ?: 0.0) - 5, (values.maxOrNull() ?: if (unit == "°F") 300.0 else 150.0) + 5)
    val minY = yTicks.first(); val maxY = yTicks.last()
    val clusters = CookChartMath.clusterTimes(selectedMarkers.map { it.id to x(it.cookId, it.occurredAtUtcMillis) }, minX, maxX, plotWidth.toDouble())
    var selectedCluster by remember { mutableStateOf(emptyList<String>()) }
    val chartColors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, Color(0xFF568CB7), Color(0xFFB779BD), Color(0xFFD3993C), Color(0xFF539674))
    fun color(id: String) = chartColors[(id.hashCode() and Int.MAX_VALUE) % chartColors.size]
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markerColor = MaterialTheme.colorScheme.tertiary
    val targetColor = MaterialTheme.colorScheme.secondary
    fun formatX(value: Double): String = if (clockMode && cookNames.size <= 1) SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value.toLong()))
        else if (span < 300_000) "${String.format(Locale.US, "%.0f", value / 1000)}s" else "${String.format(Locale.US, "%.0f", value / 60_000)}m"
    fun pan(amount: Double) { followLatest = false; windowStart = (minX + amount).coerceIn(fullMin, fullMax - span); windowSpan = span }
    fun zoom(factor: Double) { windowSpan = (span / factor).coerceIn(minOf(60_000.0, fullSpan), fullSpan); if (!followLatest) windowStart = (minX + span / 2 - windowSpan!! / 2).coerceIn(fullMin, fullMax - windowSpan!!) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Temperature ($unit)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("chart-y-axis"))
        if (interactive) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!clockMode, { clockMode = false; windowStart = null; windowSpan = null; scrub = null }, { Text("Elapsed") }, modifier = Modifier.testTag("chart-time-elapsed"))
                if (cookNames.size <= 1) FilterChip(clockMode, { clockMode = true; windowStart = null; windowSpan = null; scrub = null }, { Text("Clock time") }, modifier = Modifier.testTag("chart-time-clock"))
                FilterChip(followLatest, { followLatest = !followLatest; windowStart = minX }, { Text("Follow latest") }, modifier = Modifier.testTag("chart-follow-latest"))
                TextButton({ zoom(2.0) }, modifier = Modifier.testTag("chart-zoom-in")) { Text("Zoom +") }
                TextButton({ zoom(0.5) }, modifier = Modifier.testTag("chart-zoom-out")) { Text("Zoom −") }
                TextButton({ windowSpan = null; windowStart = null; followLatest = true; scrub = null }, modifier = Modifier.testTag("chart-reset")) { Text("Reset") }
            }
            if (dishNames.isNotEmpty()) SimpleDropdownField("Dish phases", dishNames[selectedDish] ?: "All dishes", listOf("All dishes") + dishNames.values.toList(), { name -> selectedDish = dishNames.entries.firstOrNull { it.value == name }?.key; scrub = null; windowStart = null; windowSpan = null })
        }
        Canvas(Modifier.fillMaxWidth().height(chartHeight).onSizeChanged { plotWidth = (it.width - 62).toFloat().coerceAtLeast(1f) }.testTag("temperature-chart")
            .semantics { contentDescription = "Temperature chart, $unit, ${series.size} channels. Time from ${formatX(minX)} to ${formatX(maxX)}. Recorded points with gaps preserved." }
            .pointerInput(minX, maxX, clusters) { detectTapGestures { tap ->
                val fraction = ((tap.x - 48.dp.toPx()) / (size.width - 60.dp.toPx())).coerceIn(0f, 1f)
                val at = minX + fraction * span
                val cluster = clusters.minByOrNull { abs(it.first().second - at) }
                if (tap.y < 36.dp.toPx() && cluster != null && abs(cluster.first().second - at) / span * size.width < 24.dp.toPx()) selectedCluster = cluster.map { it.first }
                else scrub = at
            } }
            .pointerInput(minX, maxX) { if (interactive) detectTransformGestures { _, movement, scale, _ -> if (scale != 1f) zoom(scale.toDouble()); if (movement.x != 0f) pan(-movement.x / size.width * span) } }) {
            val left = 48.dp.toPx(); val right = size.width - 12.dp.toPx(); val top = 34.dp.toPx(); val bottom = size.height - 34.dp.toPx()
            fun px(at: Double) = left + ((at - minX) / span * (right - left)).toFloat()
            fun py(v: Double) = bottom - ((v - minY) / (maxY - minY) * (bottom - top)).toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = 10.dp.toPx() }
            fun text(label: String, at: Float, baseline: Float) = drawContext.canvas.nativeCanvas.drawText(label, at, baseline, paint)
            if (cookNames.size <= 1) {
                val cookId = markers.firstOrNull()?.cookId ?: valid.firstOrNull()?.cookId
                if (cookId != null) {
                    val phases = if (timingMode != "explicit_phases") emptyList() else CookPhases.all(plans, selectedDish?.let { listOf(it) } ?: dishNames.keys.toList(), cookStartTimes[cookId] ?: firstTime, markers.firstOrNull { it.eventType == "cook_finished" }?.occurredAtUtcMillis ?: valid.maxOfOrNull { it.measuredAtUtcMillis } ?: System.currentTimeMillis())
                    phases.forEachIndexed { lane, p -> p.intervals.forEach { interval ->
                        val a = x(cookId, interval.start).coerceIn(minX, maxX); val b = x(cookId, interval.end).coerceIn(minX, maxX)
                        if (b > a) drawRect(color(interval.phase).copy(alpha = 0.13f), Offset(px(a), top + lane * (bottom - top) / phases.size.coerceAtLeast(1)), androidx.compose.ui.geometry.Size(px(b) - px(a), (bottom - top) / phases.size.coerceAtLeast(1)))
                    } }
                }
            }
            yTicks.forEach { tick -> drawLine(grid, Offset(left, py(tick)), Offset(right, py(tick))); text(String.format(Locale.US, "%.0f", tick), 1.dp.toPx(), py(tick) + 4.dp.toPx()) }
            repeat(if (clockMode) 3 else 5) { i -> val at = minX + span * i / (if (clockMode) 2 else 4); val label = formatX(at); drawLine(grid, Offset(px(at), top), Offset(px(at), bottom)); text(label, (px(at) - paint.measureText(label) / 2).coerceIn(0f, size.width - paint.measureText(label)), bottom + 19.dp.toPx()) }
            drawLine(Color(textColor), Offset(left, top), Offset(left, bottom), 1.5.dp.toPx())
            drawLine(Color(textColor), Offset(left, bottom), Offset(right, bottom), 1.5.dp.toPx())
            applicableTargets.forEach { t -> drawLine(targetColor.copy(alpha = .65f), Offset(left, py(y(t.value, t.unit))), Offset(right, py(y(t.value, t.unit))), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))) }
            applicableHistory.groupBy { listOf(it.cookId, it.dishId, it.targetType) }.values.forEach { rows -> rows.sortedBy { it.effectiveAt }.forEachIndexed { i, t ->
                val a = x(t.cookId, t.effectiveAt); val b = rows.sortedBy { it.effectiveAt }.getOrNull(i + 1)?.let { x(it.cookId, it.effectiveAt) } ?: maxX
                if (b >= minX && a <= maxX) drawLine(targetColor, Offset(px(a.coerceAtLeast(minX)), py(y(t.value, t.unit))), Offset(px(b.coerceAtMost(maxX)), py(y(t.value, t.unit))), 2.dp.toPx())
            } }
            clusters.forEach { cluster -> val at = px(cluster.first().second); drawLine(markerColor.copy(alpha = .55f), Offset(at, top), Offset(at, bottom)); drawCircle(markerColor, 7.dp.toPx(), Offset(at, 18.dp.toPx()));
                val label = if (cluster.size > 1) "${cluster.size}" else selectedMarkers.firstOrNull { it.id == cluster.first().first }?.title?.take(10).orEmpty()
                text(label, (at - paint.measureText(label) / 2).coerceIn(left, right - paint.measureText(label)), 9.dp.toPx())
            }
            clipRect(left, top, right, bottom) {
                visibleSegments.forEach { segment -> val reduced = TemperaturePlot.reduce(segment); val c = color(TemperaturePlot.identity(segment.first()))
                    fun point(r: SensorReadingEntity) = Offset(px(x(r.cookId, r.measuredAtUtcMillis)), py(y(r.value, r.unit)))
                    if (segment.first().source != "manual") reduced.zipWithNext().forEach { (a, b) -> drawLine(c, point(a), point(b), 2.dp.toPx(), StrokeCap.Round) }
                    reduced.forEach { drawCircle(c, if (it.source == "manual") 4.dp.toPx() else 2.dp.toPx(), point(it)) }
                }
                scrub?.takeIf { it in minX..maxX }?.let { drawLine(markerColor, Offset(px(it), top), Offset(px(it), bottom), 2.dp.toPx()) }
            }
        }
        Text(if (clockMode && cookNames.size <= 1) "Time (local date and clock)" else if (plans.any { it.action == "food_on" }) "Time (elapsed since Food on; negative = prep)" else "Time (elapsed since session start / comparison anchor)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("chart-x-axis"))
        if (dishNames.isNotEmpty()) {
            Text("Phase bands: " + listOf("prep", "cooking", "wrapped", "removed", "resting", "holding", "done").joinToString { CookPhases.label(it) }, style = MaterialTheme.typography.bodySmall)
        }
        if (valid.isEmpty()) Text("No temperatures recorded yet. Notes and phases still appear on the time axis.")
        if (interactive) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ pan(-span / 4) }, enabled = span < fullSpan, modifier = Modifier.testTag("chart-pan-left")) { Text("Earlier") }
                TextButton({ pan(span / 4) }, enabled = span < fullSpan, modifier = Modifier.testTag("chart-pan-right")) { Text("Later") }
            }
            Text("Scrub recorded values", style = MaterialTheme.typography.labelLarge)
            Slider(value = (((scrub ?: maxX) - minX) / span).toFloat().coerceIn(0f, 1f), onValueChange = { scrub = minX + it * span }, modifier = Modifier.testTag("chart-scrubber"))
            scrub?.let { selected ->
                Text("Selected ${formatX(selected)} · nearest recorded samples", style = MaterialTheme.typography.labelLarge)
                series.filterKeys { it !in hidden }.forEach { (_, rows) -> rows.minByOrNull { abs(x(it.cookId, it.measuredAtUtcMillis) - selected) }?.let { r ->
                    Text("${label(r)}: ${String.format(Locale.US, "%.1f", y(r.value, r.unit))} $unit · ${formatX(x(r.cookId, r.measuredAtUtcMillis))} · ${if (r.timestampBasis == "cloud_receipt") "cloud receipt; sensor age unknown" else "measured"}", style = MaterialTheme.typography.bodySmall)
                } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Notes", "Photos", "Actions").forEach { filter -> FilterChip(markerFilter == filter, { markerFilter = filter }, { Text(filter) }) }
            }
        }
        series.forEach { (id, rows) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (interactive) {
                    FilterChip(id !in hidden, { hidden = if (id in hidden) hidden - id else hidden + id }, { Text(label(rows.last()), color = color(id)) }, modifier = Modifier.weight(1f).testTag("chart-series-$id"))
                    TextButton({ hidden = series.keys - id }, modifier = Modifier.testTag("chart-isolate-$id")) { Text("Only") }
                } else Text(label(rows.last()), color = color(id), style = MaterialTheme.typography.labelSmall)
            }
            if (interactive && id !in hidden) {
                val segment = rawSegments.filter { TemperaturePlot.identity(it.first()) == id }.maxByOrNull { it.last().measuredAtUtcMillis }.orEmpty().takeLast(60)
                val slope = CookChartMath.slopePerMinute(segment, unit)
                if (slope != null) Text("Recent recorded trend: ${String.format(Locale.US, "%+.2f", slope)} $unit/min" + if (abs(slope) < if (unit == "°C") .055 else .1) " · flat over this recorded interval" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (interactive && hidden.isNotEmpty()) TextButton({ hidden = emptySet() }) { Text("Show all channels") }
        applicableTargets.forEach { target -> Text("${cookNames[target.cookId].orEmpty()} target · ${target.targetType.replace('_', ' ')}: ${String.format(Locale.US, "%.1f", y(target.value, target.unit))} $unit", style = MaterialTheme.typography.bodySmall) }
        if (applicableTargets.isNotEmpty()) Text("Dashed: current/reference targets. Solid: target or setpoint history from its recorded effective time.", style = MaterialTheme.typography.bodySmall)
        selectedMarkers.filter { x(it.cookId, it.occurredAtUtcMillis) in minX..maxX && (selectedCluster.isEmpty() || it.id in selectedCluster) }.forEach { event ->
            if (onMarker != null) TextButton({ onMarker(event) }, modifier = Modifier.testTag("chart-marker-${event.id}")) { Text("${formatX(x(event.cookId, event.occurredAtUtcMillis))} · ${event.title}" + if (photos.any { it.eventId == event.id }) " · photo" else "") }
        }
        if (selectedCluster.isNotEmpty()) TextButton({ selectedCluster = emptyList() }) { Text("Show all annotations") }
    }
}
