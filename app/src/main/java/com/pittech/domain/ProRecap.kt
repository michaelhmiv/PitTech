package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** Pure Year in Smoke / periodic recap builder over the LOCAL v1 report. The
 * user controls the scope (period plus exactly which cooks and sections are
 * included) and sees a full preview before anything can be shared. The card
 * reuses the share-card v1 content schema so the existing native renderer
 * draws it unchanged; recap inclusion changes no publication state — a private
 * cook never becomes public because it appears in a recap. iOS `ProRecap` pins
 * the same slots. */
object ProRecap {
    data class Scope(
        /** A calendar year ("2026") or [ALL_PERIODS]. */
        val period: String,
        val includedCookIds: List<String>,
        val showStats: Boolean,
        val showMilestones: Boolean,
        val showBest: Boolean,
        val note: String,
    ) {
        companion object {
            fun all(cookIds: List<String>, period: String) = Scope(period, cookIds, true, true, true, "")
        }
    }

    data class Milestone(val dish: String, val title: String)
    data class Stat(val label: String, val value: String)

    data class Card(
        val title: String,
        val dishes: List<String>,
        val milestones: List<Milestone>,
        val totalMilestones: Int,
        val stats: List<Stat>,
        val note: String,
        val unit: String,
        val coverageNote: String,
        val privacyNote: String,
    )

    const val ALL_PERIODS = "all"

    /** Distinct cook years, newest first, derived from startedAt millis in UTC. */
    fun availablePeriods(startedAt: Map<String, Long>): List<String> =
        startedAt.values.map { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }.distinct().sortedDescending().map { it.toString() }

    fun cooksInPeriod(report: InsightReport, archive: InsightArchive, period: String): List<String> {
        if (period == ALL_PERIODS) return report.cooks.map { it.cookId }
        val year = period.toIntOrNull() ?: return report.cooks.map { it.cookId }
        val starts = archive.cooks.associate { it.id to it.startedAtUtcMillis }
        return report.cooks.map { it.cookId }.filter { id ->
            val start = starts[id] ?: return@filter false
            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).year == year
        }
    }

    /** Builds the recap card for exactly the included cooks. Returns null when
     * nothing is selected — the UI shows its select-cooks state instead of a
     * card. Pure: building never publishes, uploads, or mutates anything. */
    fun build(report: InsightReport, titles: Map<String, String>, scope: Scope, totalCookCount: Int): Card? {
        val included = report.cooks.filter { it.cookId in scope.includedCookIds }
        if (included.isEmpty() || included.size > 20) return null
        val dishes = included.map { titles[it.cookId] ?: it.cookId }

        val milestones = mutableListOf<Milestone>()
        if (scope.showMilestones) {
            for (cook in included) {
                val title = titles[cook.cookId] ?: cook.cookId
                for (dish in cook.dishes) {
                    val wrap = dish.wrapAt
                    val foodOn = dish.foodOnAt
                    if (wrap != null && foodOn != null) {
                        milestones.add(Milestone(title, "Wrapped ${ProPersonalAnalysis.formatElapsed(wrap - foodOn)} after Food on"))
                    }
                    ProPersonalAnalysis.positive(dish.heatingMillis)?.let { heating ->
                        milestones.add(Milestone(title, "Finished after ${ProPersonalAnalysis.formatDuration(heating)} of heating"))
                    }
                }
            }
        }
        val total = milestones.size
        val shown = milestones.take(5)

        val stats = mutableListOf<Stat>()
        if (scope.showStats) {
            stats.add(Stat("Recorded cooks", "${included.size}"))
            val heated = included.flatMap { it.dishes }.mapNotNull { ProPersonalAnalysis.positive(it.heatingMillis) }
            if (heated.isNotEmpty()) stats.add(Stat("Heating time", ProPersonalAnalysis.formatDuration(heated.sum())))
            if (scope.showBest) {
                included.flatMap { it.dishes }.mapNotNull { it.rating }.maxOrNull()?.let { best ->
                    stats.add(Stat("Best recorded rating", ProPersonalAnalysis.formatRating(best)))
                }
            }
        }

        val units = included.flatMap { it.channels.flatMap { channel -> channel.units } }.toSet()
        val title = if (scope.period == ALL_PERIODS) "PitTech recap · All recorded cooks" else "Year in Smoke · ${scope.period}"
        val coverage = if (scope.showMilestones) {
            if (total > shown.size) "${included.size} of $totalCookCount recorded cooks selected · $total recorded milestones · showing ${shown.size}"
            else "${included.size} of $totalCookCount recorded cooks selected · $total recorded milestones"
        } else {
            "${included.size} of $totalCookCount recorded cooks selected"
        }
        return Card(
            title,
            dishes,
            shown,
            total,
            stats.take(3),
            scope.note,
            if (units == setOf("°C")) "°C" else "°F",
            coverage,
            "Only the ${included.size} selected ${if (included.size == 1) "cook" else "cooks"} appear in this recap. Sharing is a separate explicit step; nothing here changes what is public.",
        )
    }

    /** Emits the share-card v1 content object for the recap so the existing
     * renderer draws it (hero none: photo-free cards render correctly; long
     * titles and notes truncate in the image only, as with share cards). */
    fun shareCardContent(card: Card, themeId: String, paletteId: String): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("title", card.title)
            .put("dishes", JSONArray(card.dishes))
            .put("hero", "none")
            .put("unit", card.unit)
            .put("curves", JSONArray())
            .put("channelCount", 0)
            .put("milestones", JSONArray(card.milestones.map { JSONObject().put("dish", it.dish).put("title", it.title).put("offsetMillis", JSONObject.NULL) }))
            .put("stats", JSONArray(card.stats.map { JSONObject().put("label", it.label).put("value", it.value) }))
            .put("note", card.note)
            .put("cropX", 0.5)
            .put("cropY", 0.5)
            .put("zoom", 1.0)
            .put("theme", themeId)
            .put("palette", paletteId)
            .put("relay", JSONObject.NULL)
}
