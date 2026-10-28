package com.pittech.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

enum class ProvenancePhotoRole(val rank: Int) { ON_PIT(0), AT_WRAP(1), SLICED(2), OTHER(3) }

data class ProvenancePhoto(val photoId: String, val role: ProvenancePhotoRole, val caption: String?, val capturedAtUtcMillis: Long)

data class ProvenanceCurvePoint(val offsetMinutes: Double, val fahrenheit: Double)

/**
 * The provenance payload (Flowing spec §4.3): everything a recipe's story
 * needs from the cook that earned it, assembled once from the archive so
 * Spread 06 (recipe story), Spread 03 (plan seeding) and Spread 04
 * (plan-this) all render the same facts. A slot is null/empty when the
 * source cook didn't record it — absence is data, never filled in.
 * The iOS RecipeProvenance mirrors this file rule for rule.
 */
data class RecipeProvenance(
    val sourceCookId: String,
    val sourceDishId: String,
    val cookTitle: String,
    /** The cook's date with the year — "October 12, 2026" (Michael's rule). */
    val cookedDateText: String,
    val cookedAtUtcMillis: Long,
    val timeZoneId: String,
    val pitName: String?,
    val setpointF: Double?,
    val wood: String?,
    val fuel: String?,
    val dishName: String,
    val cut: String?,
    val weightText: String?,
    val totalMinutes: Double?,
    val stallMinutes: Double?,
    val wrappedAtF: Double?,
    val restMinutes: Double?,
    val curve: List<ProvenanceCurvePoint>,
    val photos: List<ProvenancePhoto>,
    val note: String?,
    val rating: Double?,
    val cookAgain: Boolean?,
    val keepChips: List<String>,
    val changeChips: List<String>,
)

object RecipeProvenance {
    const val MAX_CURVE_POINTS = 60
    private val dateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

    private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.doubleOrNull(key: String) = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)

    /**
     * Assemble the payload for [sourceDishId] of [sourceCookId] from a full
     * library archive (the export vocabulary: cooks/dishes/events/readings/
     * results/photos). Returns null when the recipe has no source cook, or
     * the cook/dish is not in the archive — the honest-absence case the
     * surfaces render around.
     */
    fun fromArchive(root: JSONObject, sourceCookId: String?, sourceDishId: String?): RecipeProvenance? {
        if (sourceCookId == null || sourceDishId == null) return null
        fun objects(key: String): List<JSONObject> {
            val array = root.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        val cook = objects("cooks").firstOrNull { it.getString("id") == sourceCookId } ?: return null
        val dish = objects("dishes").firstOrNull { it.getString("id") == sourceDishId && it.optString("cookId") == sourceCookId } ?: return null

        val insights = InsightsCalculations.archiveWithLifecycles(root)
        val asOf = cook.longOrNull("endedAtUtcMillis")
            ?: insights.readings.filter { it.cookId == sourceCookId }.maxOfOrNull { it.measuredAtUtcMillis }
            ?: cook.getLong("startedAtUtcMillis")
        val report = InsightsCalculations.compute(insights, asOf)
        val metrics = report.cooks.firstOrNull { it.cookId == sourceCookId }?.dishes?.firstOrNull { it.dishId == sourceDishId }

        val series = insights.readings
            .filter { it.cookId == sourceCookId && (it.dishId == sourceDishId || it.dishId == null) &&
                InsightsCalculations.role(it.measurementType) == "food" && it.qualityStatus == "valid" && it.value != null }
            .map { PaceReading(it.measuredAtUtcMillis, InsightsCalculations.fahrenheit(it.value!!, it.unit)) }
            .sortedBy { it.atUtcMillis }

        val foodOn = metrics?.foodOnAt ?: series.firstOrNull()?.atUtcMillis
        val total = if (foodOn != null) {
            val end = metrics?.dishDoneAt ?: metrics?.removeAt
            end?.let { (it - foodOn) / 60_000.0 }
        } else null
        val stall = CookPaceEngine.stallSpans(series).maxByOrNull { it.durationMinutes }?.durationMinutes
        val wrappedAt = metrics?.wrapAt?.let { wrapAt ->
            series.minByOrNull { kotlin.math.abs(it.atUtcMillis - wrapAt) }
                ?.takeIf { kotlin.math.abs(it.atUtcMillis - wrapAt) <= 10 * 60_000L }?.fahrenheit
        }
        val curve = if (foodOn != null && series.isNotEmpty()) downsample(series, foodOn) else emptyList()

        val eventActions = insights.events.filter { it.cookId == sourceCookId }.associate { it.id to it.action }
        val photos = objects("photos")
            .filter { it.optString("cookId") == sourceCookId && (it.stringOrNull("dishId") == sourceDishId || it.stringOrNull("dishId") == null) }
            .map { photo ->
                val role = when (photo.stringOrNull("eventId")?.let { eventActions[it] }) {
                    "food_on" -> ProvenancePhotoRole.ON_PIT
                    "wrap" -> ProvenancePhotoRole.AT_WRAP
                    "remove", "rest_start", "dish_done" -> ProvenancePhotoRole.SLICED
                    else -> ProvenancePhotoRole.OTHER
                }
                ProvenancePhoto(photo.getString("id"), role, photo.stringOrNull("caption"), photo.longOrNull("capturedAtUtcMillis") ?: photo.longOrNull("addedAtUtcMillis") ?: 0L)
            }
            .sortedWith(compareBy<ProvenancePhoto> { it.role.rank }.thenBy { it.capturedAtUtcMillis })

        val results = insights.results.filter { it.cookId == sourceCookId }
        fun scopedTexts(type: String): List<String> {
            val dishRows = results.filter { it.resultType == type && it.dishId == sourceDishId }
            val rows = dishRows.ifEmpty { results.filter { it.resultType == type && it.dishId == null } }
            return rows.mapNotNull { it.textValue?.trim()?.takeIf { v -> v.isNotBlank() } }.distinct()
        }
        val note = scopedTexts("result_notes").lastOrNull() ?: cook.stringOrNull("notes")

        val startedAt = cook.getLong("startedAtUtcMillis")
        val zoneId = cook.stringOrNull("startedTimeZoneId") ?: "UTC"
        val weight = dish.doubleOrNull("weightValue")?.let { v ->
            val unit = dish.stringOrNull("weightUnit") ?: "lb"
            "${trimNumber(v)} $unit"
        }
        return RecipeProvenance(
            sourceCookId = sourceCookId,
            sourceDishId = sourceDishId,
            cookTitle = cook.stringOrNull("title") ?: "",
            cookedDateText = dateFormat.format(Instant.ofEpochMilli(startedAt).atZone(ZoneId.of(zoneId))),
            cookedAtUtcMillis = startedAt,
            timeZoneId = zoneId,
            pitName = cook.stringOrNull("smokerName"),
            setpointF = cook.doubleOrNull("initialSetpointValue")?.let { InsightsCalculations.fahrenheit(it, cook.stringOrNull("initialSetpointUnit") ?: "°F") },
            wood = cook.stringOrNull("woodOrPelletBlend"),
            fuel = cook.stringOrNull("fuelType"),
            dishName = dish.stringOrNull("name") ?: "",
            cut = dish.stringOrNull("cut"),
            weightText = weight,
            totalMinutes = total,
            stallMinutes = stall,
            wrappedAtF = wrappedAt,
            restMinutes = metrics?.restMillis?.let { it / 60_000.0 },
            curve = curve,
            photos = photos,
            note = note,
            rating = metrics?.rating,
            cookAgain = metrics?.cookAgain,
            keepChips = scopedTexts("keep_doing"),
            changeChips = scopedTexts("change_next_time"),
        )
    }

    private fun downsample(series: List<PaceReading>, foodOn: Long): List<ProvenanceCurvePoint> {
        val points = series.map { ProvenanceCurvePoint((it.atUtcMillis - foodOn) / 60_000.0, it.fahrenheit) }
        if (points.size <= MAX_CURVE_POINTS) return points
        val stride = (points.size - 1).toDouble() / (MAX_CURVE_POINTS - 1)
        return (0 until MAX_CURVE_POINTS).map { points[(it * stride).toInt().coerceAtMost(points.lastIndex)] }
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
