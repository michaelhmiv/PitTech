package com.pittech.data

import com.pittech.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class ShareCardOptions(val hero: String = "chart", val cropX: Double = 0.5, val cropY: Double = 0.5,
    val zoom: Double = 1.0, val note: String = "", val includeVerdict: Boolean = false, val unit: String = "auto", val theme: String = "classic") {
    fun encode(): JSONObject {
        require(hero in setOf("photo", "chart", "none") && cropX.isFinite() && cropY.isFinite() && cropX in 0.0..1.0 && cropY in 0.0..1.0 && zoom.isFinite() && zoom in 1.0..3.0 && note.codePointCount(0,note.length) <= 2000 && unit in setOf("auto", "°C", "°F") && theme == "classic") { "Review the card's photo crop, note and units." }
        return JSONObject().put("version", 1).put("hero", hero).put("cropX", cropX).put("cropY", cropY).put("zoom", zoom)
            .put("note", note).put("includeVerdict", includeVerdict).put("unit", unit).put("theme", theme)
    }
    companion object {
        fun decode(o: JSONObject?): ShareCardOptions {
            if (o == null) return ShareCardOptions()
            require(o.getInt("version") == 1)
            return ShareCardOptions(o.getString("hero"), o.getDouble("cropX"), o.getDouble("cropY"), o.getDouble("zoom"), o.getString("note"), o.getBoolean("includeVerdict"), o.getString("unit"), o.getString("theme")).also { it.encode() }
        }
    }
}
data class PreparedShareCard(val content: String, val photo: ByteArray?)
/** Only the relay client may supply this after a successful upload. Drafts have no code field. */
data class ShareCardRelay internal constructor(val code: String, val url: String) {
    fun validate() { require(code.matches(Regex("[A-Z0-9]+(?:-[A-Z0-9]+){2,5}")) && url == "https://pittech.app/s/$code") }
}

object ShareCardContent {
    private val actions = setOf("food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done", "season", "trim", "preheat_start")
    private fun objects(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun number(v: Double) = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    fun elapsed(ms: Long): String = "${ms / 3600000}h ${(ms / 60000) % 60}m"
    fun offset(ms: Long?): String = ms?.let { (if (it < 0) "−" else "+") + elapsed(abs(it)) } ?: "Recorded"
    fun build(document: SharingDocument, photo: ByteArray?, relay: ShareCardRelay? = null): PreparedShareCard {
        SharingDraftCodec.validate(document); document.card.encode(); relay?.validate()
        val entries = document.entries.filter { it.included }; require(entries.isNotEmpty()) { "Select at least one dish for the card." }
        val projection = CookPackProjection()
        val stories = entries.map(projection::story)
        val all = stories.flatMap { objects(it, "readings") }.filter { it.optString("qualityStatus", "valid") == "valid" }
        val unit = document.card.unit.takeUnless { it == "auto" } ?: if (all.isNotEmpty() && all.all { it.getString("unit") == "°C" }) "°C" else "°F"
        fun temperature(row: JSONObject): Double {
            val f = CookPlanEngine.fahrenheit(row.getDouble("value"), row.getString("unit")); return if (unit == "°C") (f - 32) * 5 / 9 else f
        }
        val milestones = entries.indices.flatMap { index -> objects(stories[index], "observations").filter { it.optString("action") in actions }.map { event ->
            JSONObject().put("dish", entries[index].snapshot.name).put("title", event.getString("title")).put("offsetMillis", event.opt("offsetMillis") ?: JSONObject.NULL)
        } }.sortedBy { if (it.isNull("offsetMillis")) Long.MAX_VALUE else it.getLong("offsetMillis") }.take(5)
        val curves = mutableListOf<JSONObject>(); var channelCount = 0
        for (i in entries.indices) {
            val story = stories[i]; if (story.isNull("anchorUtcMillis")) continue
            val anchor = story.getLong("anchorUtcMillis"); val gaps = story.optJSONArray("connectionGaps")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
            val channels = objects(story, "readings").groupBy { listOf(if (it.isNull("probeId")) it.getString("id") else it.getString("probeId"), it.getString("measurementType")).joinToString(":") }
            channelCount += channels.size
            for (rows in channels.values) {
                if (curves.size >= 6) continue
                val segments = mutableListOf<List<JSONObject>>(); var current = mutableListOf<JSONObject>()
                fun flush() { if (current.isNotEmpty()) segments += current; current = mutableListOf() }
                for (row in rows.sortedBy { it.getLong("measuredAtUtcMillis") }) {
                    if (row.optString("qualityStatus", "valid") != "valid") { flush(); continue }
                    current.lastOrNull()?.let { previous ->
                        val from = previous.getLong("measuredAtUtcMillis"); val at = row.getLong("measuredAtUtcMillis")
                        val interval = maxOf(row.optLong("samplingIntervalMillis", 15000), previous.optLong("samplingIntervalMillis", 15000))
                        if (row.getString("source") == "manual" || interval == 0L || at - from > maxOf(45000, interval + 30000) || gaps.any { it > from && it <= at }) flush()
                    }
                    current += row
                }
                flush()
                val retained = if (segments.size <= 50) segments else {
                    val indices = linkedSetOf(0,segments.lastIndex,segments.indices.minBy { s -> segments[s].minOf(::temperature) },segments.indices.maxBy { s -> segments[s].maxOf(::temperature) })
                    for (n in 0..45) indices += (n.toLong()*segments.lastIndex/45).toInt()
                    indices.sorted().map(segments::get)
                }
                val budget = 200 / retained.size.coerceAtLeast(1)
                val points = retained.map { segment ->
                    // Extrema preserve short spikes; inspection/full precision stays in the Cook Pack.
                    val buckets = ((budget-2)/2).coerceAtLeast(1); val size = (segment.size+buckets-1)/buckets
                    val reduced = if (segment.size <= budget) segment else (listOf(segment.first(), segment.last()) + segment.chunked(size).flatMap { bucket -> listOf(bucket.minBy(::temperature), bucket.maxBy(::temperature)) }).distinctBy { it.getString("id") }.sortedBy { it.getLong("measuredAtUtcMillis") }
                    JSONArray(reduced.map { JSONObject().put("x", (it.getLong("measuredAtUtcMillis") - anchor) / 60000.0).put("y", temperature(it)) })
                }
                if (points.isNotEmpty()) curves += JSONObject().put("label", "${entries[i].snapshot.name} · ${rows.first().getString("probeName")} · ${rows.first().getString("measurementType")}")
                    .put("segments", JSONArray(points))
            }
        }
        val stats = mutableListOf<JSONObject>()
        fun stat(label: String, value: String) { if (stats.size < 3) stats += JSONObject().put("label", label).put("value", value) }
        if (entries.size == 1) {
            val story = stories.single(); val result = objects(story, "results")
            if (document.card.includeVerdict) {
                result.firstOrNull { it.optString("type") in setOf("overall_rating", "overall") && !it.isNull("numericValue") }?.let { stat("Recorded rating", number(it.getDouble("numericValue")) + " / 5") }
                result.firstOrNull { it.optString("type") == "cook_again" && !it.isNull("numericValue") }?.let { stat("Cook again", if (it.getDouble("numericValue") == 1.0) "Yes" else "No") }
            }
            if (!story.isNull("anchorUtcMillis")) objects(story, "observations").filter { it.optString("action") == "dish_done" }.maxOfOrNull { it.getLong("occurredAtUtcMillis") }?.let { end ->
                val duration = end - story.getLong("anchorUtcMillis"); if (duration >= 0) stat("Food on → done", elapsed(duration))
            }
            all.filter { RecipeGuideProjection.role(it.getString("measurementType")) == "food" }.maxByOrNull { it.getLong("measuredAtUtcMillis") }?.let { stat("Last recorded food", number(temperature(it)) + " $unit") }
            entries[0].snapshot.dish.takeIf { it.weightText.isNotBlank() }?.let { stat("Weight", it.weightText + " " + it.weightUnit) }
        } else stat("Selected dishes", entries.size.toString())
        val hero = when (document.card.hero) { "photo" -> { require(photo != null) { "Choose an included cover photo, or use Chart / No hero." }; "photo" }; "chart" -> if (curves.isEmpty()) "none" else "chart"; else -> "none" }
        val content = JSONObject().put("version", 1).put("title", document.title).put("dishes", JSONArray(entries.map { it.snapshot.name })).put("hero", hero)
            .put("unit", unit).put("curves", JSONArray(curves)).put("channelCount", channelCount).put("milestones", JSONArray(milestones)).put("stats", JSONArray(stats))
            .put("note", document.card.note).put("cropX", document.card.cropX).put("cropY", document.card.cropY).put("zoom", document.card.zoom).put("theme", document.card.theme)
            .put("relay", relay?.let { JSONObject().put("code", it.code).put("url", it.url) } ?: JSONObject.NULL)
        return PreparedShareCard(content.toString(), photo)
    }
}

suspend fun SharingRepository.prepareShareCard(id: String, relay: ShareCardRelay? = null): PreparedShareCard {
    val record = get(id) ?: error("This sharing draft is unavailable."); val document = SharingDraftCodec.decode(record.payload)
    val photo = if (document.card.hero == "photo") {
        val hero = document.heroPhotoId ?: error("Choose an included cover photo.")
        val selected = document.entries.filter { it.included }.flatMap { it.photoIds }.toSet()
        require(hero in selected) { "Choose a photo included in this card." }
        val asset = assets(id).firstOrNull { it.id == hero } ?: error("This cover photo is unavailable.")
        CookPackPhoto.derivative(photoBytes(asset.relativePath) ?: error("This selected photo is unavailable."))
    } else null
    return ShareCardContent.build(document, photo, relay)
}
