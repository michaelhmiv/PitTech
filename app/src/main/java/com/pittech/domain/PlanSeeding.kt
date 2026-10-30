package com.pittech.domain

import kotlin.math.roundToLong

/** A library recipe offered to the plan editor as a seed source (3.4). */
data class PlanSeedOption(
    val recipeId: String,
    val recipeName: String,
    val snapshot: RecipeSnapshot,
    val provenance: RecipeProvenance?,
    /** [PlanSeeding.fromRecipe] precomputed by the caller; null when the
     * recipe has no recorded durations to seed from. */
    val seeded: SeededLane?,
)

/** One seeded lane: the dish and its pre-filled schedule, durations attached. */
data class SeededLane(val dish: DishDraft, val schedule: DishSchedule)

/**
 * Plan seeding (Flowing spec 3.4, consuming the §4.3 provenance payload):
 * a lane arrives from a recipe with its durations attached — the source
 * cook's recorded total and rest, its pit — never typed from memory. A
 * recipe whose source cook recorded no total has nothing honest to seed
 * a duration from, so it seeds nothing: the caller keeps the manual
 * path rather than inventing numbers (§4.3: absence is data).
 */
object PlanSeeding {
    fun fromRecipe(snapshot: RecipeSnapshot, provenance: RecipeProvenance?): SeededLane? {
        val source = provenance ?: return null
        val total = source.totalMinutes?.roundToLong()?.takeIf { it > 0 } ?: return null
        val rest = source.restMinutes?.roundToLong()?.coerceAtLeast(0) ?: 0
        val note = buildString {
            append("Seeded from your ${source.cookedDateText} cook — ${formatDuration(total)} on the pit")
            if (source.restMinutes != null) append(", ${formatDuration(rest)} rest")
            append(" (one recorded cook; adjust if this one runs different)")
        }
        return SeededLane(
            dish = snapshot.dish,
            schedule = DishSchedule(
                dishIndex = 0, // the caller assigns the lane's real index
                cookMinMinutes = total,
                cookMaxMinutes = total,
                restMinutes = rest,
                pitF = snapshot.preferredPitTemperatureF ?: source.setpointF,
                evidenceCount = 1,
                evidenceNote = note,
            ),
        )
    }

    /**
     * Slice 5 (element 4.4): the same seeding for a recipe whose durations
     * live in its own frozen story rather than a local source cook — a
     * forked publication above all. The snapshot's observations are the
     * source cook's recorded milestones pinned to the smoking anchor
     * (food-on), so the lane's numbers are read off them with the
     * provenance definitions mirrored exactly: total = dish done (or
     * removal) minus food-on, rest = rest start to dish done when both
     * were recorded. A snapshot with no anchor or no off-the-pit
     * milestone recorded nothing honest to seed from and seeds nothing.
     */
    fun fromSnapshotStory(snapshot: RecipeSnapshot): SeededLane? {
        val anchor = snapshot.smokingAnchorUtcMillis ?: return null
        fun offsetOf(observation: RecipeObservation): Long =
            observation.offsetMillis ?: (observation.occurredAtUtcMillis - anchor)
        val done = snapshot.observations.lastOrNull { it.action == "dish_done" }
        val end = done ?: snapshot.observations.lastOrNull { it.action == "remove" } ?: return null
        val totalMillis = offsetOf(end)
        if (totalMillis <= 0) return null
        val total = (totalMillis / 60_000.0).roundToLong()
        val restStart = snapshot.observations.lastOrNull { it.action == "rest_start" }
        val rest = if (restStart != null && done != null) {
            ((offsetOf(done) - offsetOf(restStart)).coerceAtLeast(0) / 60_000.0).roundToLong()
        } else 0
        val note = buildString {
            val credit = snapshot.publicSource?.displayName
            if (credit != null) append("Durations from $credit's recorded cook — ")
            else append("Seeded from this recipe's recorded cook — ")
            append("${formatDuration(total)} on the pit")
            if (restStart != null && done != null) append(", ${formatDuration(rest)} rest")
            append(" (adjust anytime in the plan)")
        }
        return SeededLane(
            dish = snapshot.dish,
            schedule = DishSchedule(
                dishIndex = 0, // the caller assigns the lane's real index
                cookMinMinutes = total,
                cookMaxMinutes = total,
                restMinutes = rest,
                pitF = snapshot.preferredPitTemperatureF,
                evidenceCount = 1,
                evidenceNote = note,
            ),
        )
    }

    /** "9 h 38 min" / "45 min" / "2 h" — the note's duration voice. */
    fun formatDuration(minutes: Long): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> "$hours h $rest min"
            hours > 0 -> "$hours h"
            else -> "$minutes min"
        }
    }
}
