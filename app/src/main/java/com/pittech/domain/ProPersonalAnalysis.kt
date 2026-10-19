package com.pittech.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Local library input for the Pro personal views: the versioned v1 archive,
 * cook titles (which the archive itself does not carry), and the evaluation
 * time for open cooks. */
data class ProPersonalLibrary(val archive: InsightArchive, val titles: Map<String, String>, val asOfUtcMillis: Long) {
    companion object {
        val EMPTY = ProPersonalLibrary(
            InsightArchive(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            emptyMap(),
            System.currentTimeMillis(),
        )
    }
}

/** Pure content builders for the Personal analysis Pro view. Every figure comes
 * from the PR 15 v1 engine ([InsightReport], `CALCULATION_VERSION` 1) over the
 * user's LOCAL library plus the slice-16 cached verified entitlement: no
 * publishing, no account session beyond entitlement, no network. Missing
 * anchors, weights, and ratings stay missing — never invented, never
 * interpolated. Bests follow the user's declared rating, never an opaque
 * score. iOS `ProPersonalAnalysis` pins the same strings. */
object ProPersonalAnalysis {
    /** "8h", "7h 30m", "50m". Whole minutes, rounded. */
    fun formatDuration(millis: Long): String = formatDuration(millis.toDouble())

    fun formatDuration(millis: Double): String {
        val totalMinutes = (millis / 60000).roundToLong()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        if (hours > 0 && minutes > 0) return "${hours}h ${minutes}m"
        if (hours > 0) return "${hours}h"
        return "${minutes}m"
    }

    /** Elapsed anchor offsets: "+3:20", "+1:00", "-2:20". */
    fun formatElapsed(millis: Long): String {
        val sign = if (millis < 0) "-" else "+"
        val totalMinutes = (abs(millis.toDouble()) / 60000).roundToLong()
        return "$sign${totalMinutes / 60}:${(totalMinutes % 60).toString().padStart(2, '0')}"
    }

    /** Declared ratings only: "5 / 5", "4.5 / 5". Never a computed score. */
    fun formatRating(value: Double): String {
        val whole = value.roundToLong()
        val text = if (abs(value - whole) < 1e-9) whole.toString() else value.toString()
        return "$text / 5"
    }

    /** Normalized °F values: integral when whole, one decimal otherwise. */
    fun formatTempF(value: Double): String {
        val whole = value.roundToLong()
        if (abs(value - whole) < 1e-9) return "$whole °F"
        return String.format(Locale.US, "%.1f °F", value)
    }

    fun formatWeight(value: Double, unit: String?): String {
        val whole = value.roundToLong()
        val text = if (abs(value - whole) < 1e-9) whole.toString() else value.toString()
        if (unit.isNullOrEmpty()) return text
        return "$text $unit"
    }

    enum class LibraryState { EMPTY, SPARSE, FULL }

    data class CutGroup(val cut: String, val header: String, val lines: List<String>, val coverage: String)
    data class Best(val lines: List<String>, val notes: List<String>, val coverage: String)
    data class Timing(val lines: List<String>, val coverage: String)
    data class Methods(val lines: List<String>, val coverage: String)

    data class Snapshot(
        val state: LibraryState,
        val cookCount: Int,
        val dishCount: Int,
        val cutGroups: List<CutGroup>,
        val best: Best?,
        val timing: Timing?,
        val methods: Methods?,
        val emptyTitle: String?,
        val emptyBody: String?,
        /** Shown in place of cut comparisons for a one-cook library. */
        val comparisonNote: String?,
    )

    data class DishRow(val cookId: String, val title: String, val cut: String, val metrics: InsightDishMetrics, val cookUnits: List<String>)

    const val EMPTY_TITLE = "No recorded cooks yet"
    const val EMPTY_BODY = "Personal analysis appears here as you record cooks on this device. Your first finished cook unlocks bests, timing groups, and wrap notes."
    const val SPARSE_COMPARISON_NOTE = "Record a second cook to compare cuts side by side."
    const val SPARSE_BODY = "Comparisons need at least two recorded cooks. What you recorded so far is below; timing groups and wrap notes grow as you cook."

    fun snapshot(report: InsightReport, titles: Map<String, String>): Snapshot {
        check(report.calculationVersion == InsightsCalculations.CALCULATION_VERSION)
        val rows = dishRows(report, titles)
        if (rows.isEmpty()) {
            return Snapshot(LibraryState.EMPTY, report.cooks.size, 0, emptyList(), null, null, null, EMPTY_TITLE, EMPTY_BODY, null)
        }
        val sparse = report.cooks.size < 2
        return Snapshot(
            if (sparse) LibraryState.SPARSE else LibraryState.FULL,
            report.cooks.size,
            rows.size,
            cutGroups(rows),
            best(rows),
            timing(rows),
            methods(rows),
            null,
            if (sparse) SPARSE_BODY else null,
            if (sparse) SPARSE_COMPARISON_NOTE else null,
        )
    }

    fun dishRows(report: InsightReport, titles: Map<String, String>): List<DishRow> =
        report.cooks.flatMap { cook ->
            cook.dishes.map { dish ->
                val cut = dish.cut?.trim().orEmpty()
                DishRow(
                    cook.cookId,
                    titles[cook.cookId] ?: cook.cookId,
                    if (cut.isEmpty()) "Cut not recorded" else cut,
                    dish,
                    cook.channels.flatMap { it.units }.distinct().sorted(),
                )
            }
        }

    fun cutGroups(rows: List<DishRow>): List<CutGroup> {
        val order = rows.map { it.cut }.distinct()
        return order.sorted().map { cut ->
            val group = rows.filter { it.cut == cut }
            val lines = group.map { row ->
                val heating = positive(row.metrics.heatingMillis)?.let { "heating ${formatDuration(it)}" } ?: "heating not recorded"
                val weight = row.metrics.weightValue?.let { formatWeight(it, row.metrics.weightUnit) } ?: "weight not recorded"
                val method = if (row.metrics.methods.isEmpty()) "method not recorded" else row.metrics.methods.joinToString(", ")
                val readings = if (row.cookUnits.isEmpty()) "no recorded readings" else "${row.cookUnits.joinToString(", ")} readings"
                "${row.title}: $heating · $weight · $method · $readings"
            }
            val heated = group.count { positive(it.metrics.heatingMillis) != null }
            val weighed = group.count { it.metrics.weightValue != null }
            CutGroup(
                cut,
                "$cut · ${group.size} recorded ${if (group.size == 1) "cook" else "cooks"}",
                lines,
                "Heating time recorded for $heated of ${group.size} dishes · weights recorded for $weighed of ${group.size} dishes",
            )
        }
    }

    fun best(rows: List<DishRow>): Best {
        val rated = rows.filter { it.metrics.rating != null }
        val coverage = "${rated.size} of ${rows.size} dishes have a recorded rating"
        // Kotlin's sortedByDescending is stable: rating ties keep cook order.
        val winner = rated.sortedByDescending { it.metrics.rating ?: 0.0 }.firstOrNull()
        val rating = winner?.metrics?.rating
        if (winner == null || rating == null) {
            return Best(
                listOf("No recorded ratings yet"),
                listOf("Rate a finished cook to see your best-rated attempt here."),
                coverage,
            )
        }
        val lines = mutableListOf("${winner.title} — ${winner.cut}", "You rated this ${formatRating(rating)}")
        when (winner.metrics.cookAgain) {
            true -> lines.add("Cook again: Yes")
            false -> lines.add("Cook again: No")
            null -> lines.add("Cook-again choice not recorded")
        }
        if (winner.metrics.hasResultNotes) lines.add("Includes your result note")
        return Best(lines, emptyList(), coverage)
    }

    fun timing(rows: List<DishRow>): Timing {
        val order = rows.map { it.cut }.distinct().sorted()
        val lines = order.map { cut ->
            val group = rows.filter { it.cut == cut }
            val values = group.mapNotNull { positive(it.metrics.heatingMillis) }.sorted()
            if (group.size == 1) {
                val single = values.singleOrNull()
                if (single != null) "$cut: ${formatDuration(single)} across 1 recorded cook"
                else "$cut: heating not recorded · 1 recorded cook"
            } else if (values.isEmpty()) {
                "$cut: heating not recorded · ${group.size} recorded cooks"
            } else {
                val median = if (values.size % 2 == 1) values[values.size / 2].toDouble()
                else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
                var line = "$cut: typical (median) ${formatDuration(median)} across ${group.size} recorded cooks · range ${formatDuration(values.first())} – ${formatDuration(values.last())}"
                if (values.size < group.size) line += " · heating recorded for ${values.size} of ${group.size}"
                line
            }
        }
        val heated = rows.count { positive(it.metrics.heatingMillis) != null }
        val weighed = rows.count { it.metrics.weightValue != null }
        return Timing(lines, "Heating time recorded for $heated of ${rows.size} dishes · weights recorded for $weighed of ${rows.size} dishes")
    }

    fun methods(rows: List<DishRow>): Methods {
        val lines = rows.map { row ->
            val wrap = when {
                row.metrics.wrapAt != null && row.metrics.foodOnAt != null ->
                    "wrapped ${formatElapsed(row.metrics.wrapAt!! - row.metrics.foodOnAt!!)} after Food on"
                row.metrics.wrapAt != null -> "wrapped (Food on not recorded)"
                else -> "wrap not recorded"
            }
            val rest = positive(row.metrics.restMillis)?.let { "rested ${formatDuration(it)}" } ?: "rest not recorded"
            val hold = positive(row.metrics.holdMillis)?.let { "held ${formatDuration(it)}" } ?: "holding not recorded"
            "${row.title}: $wrap · $rest · $hold"
        }
        val wrapped = rows.count { it.metrics.wrapAt != null }
        val rested = rows.count { positive(it.metrics.restMillis) != null }
        val held = rows.count { positive(it.metrics.holdMillis) != null }
        return Methods(
            lines,
            "Wrap recorded for $wrapped of ${rows.size} dishes · rest recorded for $rested of ${rows.size} dishes · holding recorded for $held of ${rows.size} dishes",
        )
    }

    /** v1 reports 0 (not null) for a measured phase with no intervals; a zero
     * span means nothing was recorded there, so it stays out of figures. */
    fun positive(millis: Long?): Long? = if (millis != null && millis > 0) millis else null
}

/** Aligned curve tables for the Advanced curves Pro view. The user picks the
 * anchor (Food on or Wrap) and which measured channels to compare; points are
 * recorded readings only, normalized to °F so °C and °F cooks compare
 * directly. Channels whose cook lacks the anchor are excluded with a note —
 * never estimated. */
object ProAdvancedCurves {
    enum class Anchor(val title: String) { FOOD_ON("Food on"), WRAP("Wrap") }

    data class ChannelOption(val channelId: String, val label: String, val testTag: String, val readingCount: Int)
    data class AlignedPoint(val elapsedMillis: Long, val valueF: Double) {
        val line: String get() = "${ProPersonalAnalysis.formatElapsed(elapsedMillis)} · ${ProPersonalAnalysis.formatTempF(valueF)}"
    }
    data class AlignedSeries(val channelId: String, val label: String, val testTag: String, val points: List<AlignedPoint>, val coverage: String)
    data class AlignedSnapshot(val anchor: Anchor, val series: List<AlignedSeries>, val excluded: List<String>)

    private fun tag(prefix: String, channelId: String) = prefix + channelId.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

    fun channelOptions(report: InsightReport, archive: InsightArchive, titles: Map<String, String>): List<ChannelOption> =
        report.cooks.flatMap { cook ->
            cook.channels.map { channel ->
                val rows = archive.readings.filter { matches(channel.channelId, cook.cookId, it) && isMeasured(it) }
                val probe = rows.firstOrNull()?.probeName ?: channel.channelId
                val units = rows.map { it.unit }.distinct().sorted()
                val unitText = if (units.isEmpty()) "no recorded readings" else units.joinToString(", ")
                val countText = if (channel.readingCount == 1) "1 reading" else "${channel.readingCount} readings"
                ChannelOption(
                    channel.channelId,
                    "${titles[cook.cookId] ?: cook.cookId} · ${channel.role} · $probe · $countText ($unitText)",
                    tag("curve-channel-", channel.channelId),
                    channel.readingCount,
                )
            }
        }

    fun align(report: InsightReport, archive: InsightArchive, titles: Map<String, String>, anchor: Anchor, channelIds: Set<String>): AlignedSnapshot {
        val series = mutableListOf<AlignedSeries>()
        val excluded = mutableListOf<String>()
        for (cook in report.cooks) {
            for (channel in cook.channels) {
                if (channel.channelId !in channelIds) continue
                val label = channelLabel(archive, titles, cook.cookId, channel)
                val anchorAt = anchorMillis(report, cook.cookId, channel.dishId, anchor)
                if (anchorAt == null) {
                    excluded.add("$label: ${anchor.title} not recorded for this cook — excluded, never estimated.")
                    continue
                }
                val points = archive.readings
                    .filter { matches(channel.channelId, cook.cookId, it) && isMeasured(it) }
                    .sortedBy { it.measuredAtUtcMillis }
                    .mapNotNull { row -> row.value?.let { AlignedPoint(row.measuredAtUtcMillis - anchorAt, InsightsCalculations.fahrenheit(it, row.unit)) } }
                series.add(
                    AlignedSeries(
                        channel.channelId,
                        label,
                        tag("curve-series-", channel.channelId),
                        points,
                        "${points.size} recorded ${if (points.size == 1) "point" else "points"} · anchor recorded · values in °F",
                    ),
                )
            }
        }
        return AlignedSnapshot(anchor, series, excluded)
    }

    private fun channelLabel(archive: InsightArchive, titles: Map<String, String>, cookId: String, channel: InsightChannel): String {
        val probe = archive.readings.firstOrNull { matches(channel.channelId, cookId, it) && isMeasured(it) }?.probeName ?: channel.channelId
        return "${titles[cookId] ?: cookId} · ${channel.role} · $probe"
    }

    /** Dish-scoped channels align to their dish's anchor; cook-scoped (pit)
     * channels align to the cook's earliest recorded anchor across dishes. */
    fun anchorMillis(report: InsightReport, cookId: String, dishId: String?, anchor: Anchor): Long? {
        val cook = report.cooks.firstOrNull { it.cookId == cookId } ?: return null
        fun value(dish: InsightDishMetrics): Long? = when (anchor) {
            Anchor.FOOD_ON -> dish.foodOnAt
            Anchor.WRAP -> dish.wrapAt
        }
        if (dishId != null) return cook.dishes.firstOrNull { it.dishId == dishId }?.let(::value)
        return cook.dishes.mapNotNull(::value).minOrNull()
    }

    private fun matches(channelId: String, cookId: String, row: InsightReadingInput): Boolean =
        InsightsCalculations.channelId(cookId, row.probeId, row.probeName, row.sourceDeviceId, row.dishId, row.measurementType, row.source) == channelId

    /** Mirrors the v1 measured-row rule: valid, finite, and a real °F/°C unit. */
    private fun isMeasured(row: InsightReadingInput): Boolean =
        row.qualityStatus == "valid" && row.value != null && row.value.isFinite() && (row.unit == "°F" || row.unit == "°C")
}
