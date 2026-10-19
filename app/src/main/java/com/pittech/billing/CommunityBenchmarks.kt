package com.pittech.billing

import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** Typed client-side contract for the merged aggregate read API
 * (`GET /v1/community/aggregates` and `GET /v1/community/job-runs`).
 * Figures render exactly as recorded — with eligible n and compute date on
 * every figure — and the client never invents, interpolates, or partially
 * renders a figure. Mirrors the iOS CommunityBenchmarks meanings. */
internal object CommunityBenchmarks {
    const val AGGREGATES_PATH = "/v1/community/aggregates"
    const val JOB_RUNS_PATH = "/v1/community/job-runs"
    const val MINIMUM_N = 10

    val METRIC_IDS = setOf(
        "median-heating-time-v1",
        "pit-food-curve-percentiles-v1",
        "stall-summary-v1",
        "wrap-spritz-prevalence-v1",
    )

    val GROUPS = setOf(
        "beef-brisket",
        "pork-butt-shoulder",
        "pork-spare-ribs",
        "pork-baby-back-ribs",
        "pork-loin",
        "chicken-whole",
        "chicken-pieces",
        "turkey-whole",
        "other",
    )

    val BANDS = setOf("small", "medium", "large", "unknown")

    /** Strict request filter: mirrors the server's bucket allowlist. Requests
     * with any other bucket never reach the network. */
    fun bucketAllowed(bucket: String): Boolean {
        if (bucket.length !in 1..320) return false
        val parts = bucket.split("|")
        if (parts.size !in 3..4) return false
        if (parts[0] !in GROUPS || parts[1] !in BANDS) return false
        if (!parts[2].startsWith("method:") || parts[2].length !in 8..120) return false
        if (parts.size == 4) {
            if (!parts[3].startsWith("equipment:") || parts[3].length !in 12..100) return false
        }
        return true
    }

    /** Recorded buckets keep their shape even when the vocabulary grows: a
     * well-formed bucket with a newer group label still renders (raw), while
     * a misshapen bucket fails the whole response closed. */
    fun bucketShapeValid(bucket: String): Boolean {
        if (bucket.length !in 1..320) return false
        val parts = bucket.split("|")
        if (parts.size !in 3..4) return false
        if (parts[0].isEmpty() || parts[1].isEmpty()) return false
        if (!parts[2].startsWith("method:") || parts[2].length !in 8..120) return false
        if (parts.size == 4) {
            if (!parts[3].startsWith("equipment:") || parts[3].length !in 12..100) return false
        }
        return true
    }

    fun metricAllowed(metric: String): Boolean = metric in METRIC_IDS

    fun runIdValid(runId: String): Boolean = runCatching { UUID.fromString(runId) }.isSuccess

    fun jobRunsLimitValid(limit: Int): Boolean = limit in 1..100

    fun metricTitle(metricId: String): String = when (metricId) {
        "median-heating-time-v1" -> "Typical heating time"
        "pit-food-curve-percentiles-v1" -> "Temperature percentile bands"
        "stall-summary-v1" -> "Stall summary"
        "wrap-spritz-prevalence-v1" -> "Wrap and spritz prevalence"
        else -> "Community figure"
    }

    /** Pork loin and pork butt are different groups and stay different labels. */
    fun groupLabel(group: String): String = when (group) {
        "beef-brisket" -> "Beef brisket"
        "pork-butt-shoulder" -> "Pork butt / shoulder"
        "pork-spare-ribs" -> "Pork spare ribs"
        "pork-baby-back-ribs" -> "Pork baby back ribs"
        "pork-loin" -> "Pork loin"
        "chicken-whole" -> "Whole chicken"
        "chicken-pieces" -> "Chicken pieces"
        "turkey-whole" -> "Whole turkey"
        "other" -> "Other cooks"
        else -> group
    }

    fun bandLabel(band: String): String = when (band) {
        "small" -> "Small"
        "medium" -> "Medium"
        "large" -> "Large"
        "unknown" -> "Unknown weight"
        else -> band
    }

    /** Recorded bucket key rendered for display. Unknown groups fall back to
     * the raw key segment — shown exactly, never merged into a named group. */
    fun bucketLine(bucket: String): String {
        val parts = bucket.split("|")
        if (parts.size < 3) return bucket
        var line = groupLabel(parts[0]) + " · " + bandLabel(parts[1])
        val method = if (parts[2].startsWith("method:")) parts[2].removePrefix("method:") else parts[2]
        line += if (method == "unknown" || method.isEmpty()) " · Method not recorded" else " · Method: $method"
        if (parts.size == 4 && parts[3].startsWith("equipment:")) {
            line += " · Equipment: " + parts[3].removePrefix("equipment:")
        }
        return line
    }

    fun cardTag(metricId: String, bucket: String, insufficient: Boolean): String {
        val group = bucket.split("|").firstOrNull().orEmpty().ifEmpty { "other" }
        return (if (insufficient) "community-insufficient-" else "community-card-") + metricId + "-" + group
    }

    fun coverageLine(n: Int, computedDate: String): String = "$n eligible cooks · Computed $computedDate"

    /** Strict response decode. Unknown metric IDs, misshapen buckets, bad
     * dates, negative counts, and status/summary mismatches throw, and the
     * loader maps every throw to the unavailable state. */
    fun parseAggregates(o: JSONObject): CommunityAggregatesPayload {
        val run = if (o.isNull("run")) null else CommunityJobRun.parse(o.getJSONObject("run"))
        val figures = o.getJSONArray("figures")
        val date = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val parsed = (0 until figures.length()).map { CommunityFigure.parse(figures.getJSONObject(it)) }
        for (figure in parsed) {
            if (!bucketShapeValid(figure.bucket)) throw CommunityDecodeException("bucket")
            if (!metricAllowed(figure.metricId)) throw CommunityUnknownMetricException(figure.metricId)
            if (figure.n < 0 || figure.metricVersion.isEmpty()) throw CommunityDecodeException("figure")
            if (!date.matches(figure.computedDate)) throw CommunityDecodeException("date")
            when (figure.status) {
                CommunityFigureStatus.VISIBLE -> if (figure.summary == null) throw CommunityDecodeException("summary")
                CommunityFigureStatus.INSUFFICIENT -> if (figure.summary != null) throw CommunityDecodeException("summary")
            }
        }
        return CommunityAggregatesPayload(run, parsed)
    }

    /** Builds one card per recorded figure. Summaries the client cannot
     * represent become omitted notes with their coverage intact. */
    fun cards(figures: List<CommunityFigure>): Pair<List<CommunityCardContent>, List<CommunityOmittedFigure>> {
        val cards = mutableListOf<CommunityCardContent>()
        val omitted = mutableListOf<CommunityOmittedFigure>()
        for (figure in figures) {
            val bucket = bucketLine(figure.bucket)
            if (figure.status == CommunityFigureStatus.INSUFFICIENT) {
                cards.add(
                    CommunityCardContent(
                        testTag = cardTag(figure.metricId, figure.bucket, insufficient = true),
                        title = metricTitle(figure.metricId),
                        bucketLine = bucket,
                        lines = listOf(
                            "Not enough cooks yet",
                            "${figure.n} of $MINIMUM_N eligible cooks recorded. Figures appear when $MINIMUM_N distinct eligible cooks are recorded.",
                        ),
                        notes = emptyList(),
                        coverageLine = coverageLine(figure.n, figure.computedDate),
                        insufficient = true,
                    ),
                )
                continue
            }
            val summary = figure.summary
            val card = if (summary == null) null else runCatching { visibleCard(figure, summary, bucket) }.getOrNull()
            if (card == null) omitted.add(omittedNote(figure, bucket)) else cards.add(card)
        }
        return cards to omitted
    }

    private fun omittedNote(figure: CommunityFigure, bucket: String): CommunityOmittedFigure {
        val group = figure.bucket.split("|").firstOrNull().orEmpty().ifEmpty { "other" }
        return CommunityOmittedFigure(
            testTag = "community-omitted-" + figure.metricId + "-" + group,
            title = metricTitle(figure.metricId),
            bucketLine = bucket,
            note = "One figure could not be displayed (" + coverageLine(figure.n, figure.computedDate) + ").",
        )
    }

    private fun visibleCard(figure: CommunityFigure, summary: JSONObject, bucket: String): CommunityCardContent {
        val tag = cardTag(figure.metricId, figure.bucket, insufficient = false)
        val coverage = coverageLine(figure.n, figure.computedDate)
        val title = metricTitle(figure.metricId)
        return when (figure.metricId) {
            "median-heating-time-v1" -> {
                val parsed = CommunityHeatingSummary.parse(summary)
                CommunityCardContent(
                    testTag = tag, title = title, bucketLine = bucket,
                    lines = listOf(
                        "Typical (median): " + formatHours(parsed.medianMillis),
                        "Middle half: " + formatHours(parsed.p25Millis) + " – " + formatHours(parsed.p75Millis),
                        "Recorded range: " + formatHours(parsed.minMillis) + " – " + formatHours(parsed.maxMillis),
                    ),
                    notes = emptyList(),
                    coverageLine = coverage,
                    insufficient = false,
                )
            }
            "pit-food-curve-percentiles-v1" -> {
                val parsed = CommunityCurveSummary.parse(summary)
                val lines = mutableListOf<String>()
                for (interval in parsed.intervals) {
                    lines.add(intervalHeader(interval.startMillis, interval.endMillis))
                    lines.add(bandLine("Pit", interval.pit, parsed.n))
                    lines.add(bandLine("Food", interval.food, parsed.n))
                }
                if (parsed.intervals.isEmpty()) lines.add("No intervals could be displayed.")
                CommunityCardContent(
                    testTag = tag, title = title, bucketLine = bucket,
                    lines = lines,
                    notes = listOf("Percentile bands summarize many cooks. They are not one cook's readings."),
                    coverageLine = coverage,
                    insufficient = false,
                )
            }
            "stall-summary-v1" -> {
                val parsed = CommunityStallSummary.parse(summary)
                val lines = mutableListOf("Stalled: ${parsed.nStalled} of ${parsed.n} assessed cooks (" + formatPercent(parsed.prevalence) + ")")
                if (parsed.nStalled == 0) {
                    lines.add("No stalled cooks in this group.")
                } else {
                    parsed.start.median?.let { millisOrNull(it) }?.let { lines.add("Stall start (median): " + formatElapsed(it)) }
                    parsed.duration.median?.let { millisOrNull(it) }?.let { lines.add("Stall length (median): " + formatHours(it)) }
                    parsed.temp.median?.let { lines.add("Stall temperature (median): " + formatTemp(it)) }
                }
                CommunityCardContent(
                    testTag = tag, title = title, bucketLine = bucket,
                    lines = lines,
                    notes = listOf(parsed.note ?: "Sparse attempts are excluded from n, never interpolated."),
                    coverageLine = coverage,
                    insufficient = false,
                )
            }
            "wrap-spritz-prevalence-v1" -> {
                val parsed = CommunityWrapSummary.parse(summary)
                val lines = mutableListOf(
                    "Wrapped: ${parsed.nWrapped} of ${parsed.n} (" + formatPercent(parsed.wrapPrevalence) + ")",
                    "Spritzed or mopped: ${parsed.nSpritzed} of ${parsed.n} (" + formatPercent(parsed.spritzPrevalence) + ")",
                )
                if (parsed.methods.isEmpty()) {
                    lines.add("Methods recorded: none listed")
                } else {
                    for (method in parsed.methods) {
                        lines.add("Method ${method.label}: ${method.n} of ${parsed.n} (" + formatPercent(method.prevalence) + ")")
                    }
                }
                CommunityCardContent(
                    testTag = tag, title = title, bucketLine = bucket,
                    lines = lines,
                    notes = listOf("Prevalence only: these shares describe what cooks recorded."),
                    coverageLine = coverage,
                    insufficient = false,
                )
            }
            else -> throw CommunityDecodeException("metric")
        }
    }

    private fun millisOrNull(value: Double): Long? {
        if (!value.isFinite() || value != kotlin.math.floor(value) || value < 0 || value > 9e15) return null
        return value.toLong()
    }

    private fun intervalHeader(start: Long, end: Long): String = "+" + elapsedCore(start) + "–" + elapsedCore(end)

    private fun bandLine(name: String, band: CommunityCurveBand?, figureN: Int): String {
        if (band == null) return "$name band unavailable for this interval."
        if (band.suppressed) {
            return "$name band hidden: ${band.n} of $figureN cooks in this interval (" + formatPercent(band.coverage) + "). Bands need $MINIMUM_N cooks."
        }
        return "$name: p10 " + formatTemp(band.p10!!) + " · p50 " + formatTemp(band.p50!!) + " · p90 " + formatTemp(band.p90!!) +
            " (n=${band.n}, " + formatPercent(band.coverage) + " of cooks)"
    }

    /** 19800000 -> "5h 30m"; 3600000 -> "1h"; 2700000 -> "45m". */
    fun formatHours(millis: Long): String {
        val hours = millis / 3_600_000
        val minutes = (millis % 3_600_000) / 60_000
        if (hours > 0 && minutes > 0) return "${hours}h ${minutes}m"
        if (hours > 0) return "${hours}h"
        return "${minutes}m"
    }

    /** 0 -> "+0:00"; 900000 -> "+0:15"; 7200000 -> "+2:00". */
    fun formatElapsed(millis: Long): String = "+" + elapsedCore(millis)

    private fun elapsedCore(millis: Long): String {
        val hours = millis / 3_600_000
        val minutes = ((millis % 3_600_000) / 60_000).toString().padStart(2, '0')
        return "$hours:$minutes"
    }

    /** 0.6 -> "60%"; 1.0 -> "100%". */
    fun formatPercent(share: Double): String = "${(share * 100).roundToInt()}%"

    /** Recorded Celsius, exactly as recorded. */
    fun formatTemp(celsius: Double): String = "$celsius °C"
}

internal class CommunityDecodeException(message: String) : Exception(message)
internal class CommunityUnknownMetricException(val metricId: String) : Exception(metricId)

internal enum class CommunityFigureStatus {
    VISIBLE,
    INSUFFICIENT,
}

internal data class CommunityMetricVersion(val metricId: String, val metricVersion: String)

internal data class CommunityJobRun(
    val runId: String,
    val cutoffMillis: Long,
    val computedAtMillis: Long,
    val algorithmVersion: String,
    val taxonomyVersion: String,
    val bandVersion: String,
    val metricVersions: List<CommunityMetricVersion>,
    val inputCount: Int,
    val eligibleCount: Int,
    val unbucketedCount: Int,
    val cellCount: Int,
    val visibleCount: Int,
    val outputSha256: String,
) {
    companion object {
        fun parse(o: JSONObject): CommunityJobRun {
            val versions = o.getJSONArray("metricVersions")
            return CommunityJobRun(
                runId = o.getString("runId"),
                cutoffMillis = o.reqMillis("cutoffMillis"),
                computedAtMillis = o.reqMillis("computedAtMillis"),
                algorithmVersion = o.getString("algorithmVersion"),
                taxonomyVersion = o.getString("taxonomyVersion"),
                bandVersion = o.getString("bandVersion"),
                metricVersions = (0 until versions.length()).map {
                    val v = versions.getJSONObject(it)
                    CommunityMetricVersion(v.getString("metricId"), v.getString("metricVersion"))
                },
                inputCount = o.reqInt("inputCount"),
                eligibleCount = o.reqInt("eligibleCount"),
                unbucketedCount = o.reqInt("unbucketedCount"),
                cellCount = o.reqInt("cellCount"),
                visibleCount = o.reqInt("visibleCount"),
                outputSha256 = o.getString("outputSha256"),
            )
        }
    }
}

internal data class CommunityFigure(
    val bucket: String,
    val metricId: String,
    val metricVersion: String,
    val n: Int,
    val status: CommunityFigureStatus,
    val summary: JSONObject?,
    val computedDate: String,
    val cutoffMillis: Long,
    val algorithmVersion: String,
    val taxonomyVersion: String,
    val bandVersion: String,
) {
    companion object {
        fun parse(o: JSONObject): CommunityFigure {
            val status = when (o.getString("status")) {
                "visible" -> CommunityFigureStatus.VISIBLE
                "insufficient_data" -> CommunityFigureStatus.INSUFFICIENT
                else -> throw CommunityDecodeException("status")
            }
            return CommunityFigure(
                bucket = o.getString("bucket"),
                metricId = o.getString("metricId"),
                metricVersion = o.getString("metricVersion"),
                n = o.reqInt("n"),
                status = status,
                summary = if (o.isNull("summary")) null else o.getJSONObject("summary"),
                computedDate = o.getString("computedDate"),
                cutoffMillis = o.reqMillis("cutoffMillis"),
                algorithmVersion = o.getString("algorithmVersion"),
                taxonomyVersion = o.getString("taxonomyVersion"),
                bandVersion = o.getString("bandVersion"),
            )
        }
    }
}

internal data class CommunityAggregatesPayload(val run: CommunityJobRun?, val figures: List<CommunityFigure>)

/** One benchmark card's rendered strings. [coverageLine] always carries the
 * eligible n and the UTC compute date; figures never render without them. */
internal data class CommunityCardContent(
    val testTag: String,
    val title: String,
    val bucketLine: String,
    val lines: List<String>,
    val notes: List<String>,
    val coverageLine: String,
    val insufficient: Boolean,
)

/** A visible figure the client cannot represent. The values are omitted — never
 * zeroed — while the coverage note (n + compute date) stays intact. */
internal data class CommunityOmittedFigure(
    val testTag: String,
    val title: String,
    val bucketLine: String,
    val note: String,
)

internal data class CommunityBenchmarksSnapshot(
    val runId: String,
    val cards: List<CommunityCardContent>,
    val omitted: List<CommunityOmittedFigure>,
)

internal data class CommunityHeatingSummary(
    val n: Int,
    val medianMillis: Long,
    val p25Millis: Long,
    val p75Millis: Long,
    val minMillis: Long,
    val maxMillis: Long,
) {
    companion object {
        fun parse(o: JSONObject): CommunityHeatingSummary = CommunityHeatingSummary(
            n = o.reqInt("n"),
            medianMillis = o.reqMillis("medianHeatingMillis"),
            p25Millis = o.reqMillis("p25HeatingMillis"),
            p75Millis = o.reqMillis("p75HeatingMillis"),
            minMillis = o.reqMillis("minHeatingMillis"),
            maxMillis = o.reqMillis("maxHeatingMillis"),
        )
    }
}

internal data class CommunityCurveBand(
    val n: Int,
    val coverage: Double,
    val p10: Double?,
    val p50: Double?,
    val p90: Double?,
) {
    val suppressed: Boolean get() = p10 == null || p50 == null || p90 == null

    companion object {
        /** A band with null percentiles is suppressed but representable: n and
         * coverage still render. A structurally broken band is null, and the
         * card omits that band with its note intact. */
        fun parseOrNull(o: JSONObject): CommunityCurveBand? = runCatching {
            CommunityCurveBand(
                n = o.reqInt("n"),
                coverage = o.reqShare("coverage"),
                p10 = o.optTemp("p10"),
                p50 = o.optTemp("p50"),
                p90 = o.optTemp("p90"),
            )
        }.getOrNull()
    }
}

internal data class CommunityCurveInterval(
    val startMillis: Long,
    val endMillis: Long,
    val pit: CommunityCurveBand?,
    val food: CommunityCurveBand?,
) {
    companion object {
        /** Intervals the client cannot represent are null, and the card skips
         * them while its n/date/coverage lines stay intact. */
        fun parseOrNull(o: JSONObject): CommunityCurveInterval? = runCatching {
            CommunityCurveInterval(
                startMillis = o.reqMillis("startElapsedMillis"),
                endMillis = o.reqMillis("endElapsedMillis"),
                pit = if (o.isNull("pit")) null else CommunityCurveBand.parseOrNull(o.getJSONObject("pit")),
                food = if (o.isNull("food")) null else CommunityCurveBand.parseOrNull(o.getJSONObject("food")),
            )
        }.getOrNull()
    }
}

internal data class CommunityCurveSummary(val n: Int, val intervals: List<CommunityCurveInterval>) {
    companion object {
        fun parse(o: JSONObject): CommunityCurveSummary {
            if (o.optString("kind", null) != "percentile-bands") throw CommunityDecodeException("kind")
            val intervals = o.getJSONArray("intervals")
            return CommunityCurveSummary(
                n = o.reqInt("n"),
                intervals = (0 until intervals.length()).mapNotNull {
                    runCatching { intervals.getJSONObject(it) }.getOrNull()?.let { item -> CommunityCurveInterval.parseOrNull(item) }
                },
            )
        }
    }
}

internal data class CommunityStallBand(val p25: Double?, val median: Double?, val p75: Double?) {
    val isEmpty: Boolean get() = p25 == null && median == null && p75 == null

    companion object {
        fun parse(o: JSONObject): CommunityStallBand = CommunityStallBand(
            p25 = o.optFinite("p25"),
            median = o.optFinite("median"),
            p75 = o.optFinite("p75"),
        )
    }
}

internal data class CommunityStallSummary(
    val n: Int,
    val nStalled: Int,
    val prevalence: Double,
    val start: CommunityStallBand,
    val duration: CommunityStallBand,
    val temp: CommunityStallBand,
    val note: String?,
) {
    companion object {
        fun parse(o: JSONObject): CommunityStallSummary {
            val start = CommunityStallBand.parse(o.getJSONObject("startElapsedMillis"))
            val duration = CommunityStallBand.parse(o.getJSONObject("durationMillis"))
            val temp = CommunityStallBand.parse(o.getJSONObject("tempCelsius"))
            val nStalled = o.reqInt("nStalled")
            // A stalled subset with no medians contradicts the recorded counts,
            // so the figure is omitted rather than partially rendered.
            if (nStalled > 0 && (start.isEmpty || duration.isEmpty || temp.isEmpty)) throw CommunityDecodeException("stall")
            return CommunityStallSummary(
                n = o.reqInt("n"),
                nStalled = nStalled,
                prevalence = o.reqShare("prevalence"),
                start = start,
                duration = duration,
                temp = temp,
                note = if (o.isNull("note")) null else o.getString("note"),
            )
        }
    }
}

internal data class CommunityMethodShare(val label: String, val n: Int, val prevalence: Double)

internal data class CommunityWrapSummary(
    val n: Int,
    val nWrapped: Int,
    val wrapPrevalence: Double,
    val nSpritzed: Int,
    val spritzPrevalence: Double,
    val methods: List<CommunityMethodShare>,
) {
    companion object {
        fun parse(o: JSONObject): CommunityWrapSummary {
            val methods = o.getJSONArray("methods")
            return CommunityWrapSummary(
                n = o.reqInt("n"),
                nWrapped = o.reqInt("nWrapped"),
                wrapPrevalence = o.reqShare("wrapPrevalence"),
                nSpritzed = o.reqInt("nSpritzed"),
                spritzPrevalence = o.reqShare("spritzPrevalence"),
                methods = (0 until methods.length()).mapNotNull {
                    runCatching {
                        val m = methods.getJSONObject(it)
                        val label = m.getString("label")
                        if (label.isEmpty()) throw CommunityDecodeException("method")
                        CommunityMethodShare(label, m.reqInt("n"), m.reqShare("prevalence"))
                    }.getOrNull()
                },
            )
        }
    }
}

/** Strict numbers: org.json truncates doubles in getInt/getLong, so integral
 * fields validate their shape instead of silently rounding recorded values. */
private fun JSONObject.reqNumber(key: String): Double {
    if (isNull(key)) throw JSONException("missing $key")
    val value = opt(key) ?: throw JSONException("missing $key")
    // Any Number: the org.json test classpath parses fractions as BigDecimal
    // while the Android framework returns Double — both are recorded values.
    val number = when (value) {
        is Number -> value.toDouble()
        else -> throw JSONException("bad $key")
    }
    if (!number.isFinite()) throw JSONException("bad $key")
    return number
}

internal fun JSONObject.reqInt(key: String): Int {
    val number = reqNumber(key)
    if (number != kotlin.math.floor(number) || number < 0 || number > Int.MAX_VALUE) throw JSONException("bad $key")
    return number.toInt()
}

internal fun JSONObject.reqMillis(key: String): Long {
    val number = reqNumber(key)
    if (number != kotlin.math.floor(number) || number < 0 || number > 9e15) throw JSONException("bad $key")
    return number.toLong()
}

internal fun JSONObject.reqShare(key: String): Double {
    val number = reqNumber(key)
    if (number < 0 || number > 1) throw JSONException("bad $key")
    return number
}

internal fun JSONObject.optTemp(key: String): Double? {
    if (isNull(key)) return null
    // Present but unusable percentiles invalidate the band (never defaulted).
    return opt(key)?.let { if (it is Number && (it.toDouble().isFinite())) it.toDouble() else throw JSONException("bad $key") }
}

internal fun JSONObject.optFinite(key: String): Double? {
    if (isNull(key)) return null
    val value = opt(key) as? Number ?: throw JSONException("bad $key")
    val number = value.toDouble()
    if (!number.isFinite()) throw JSONException("bad $key")
    return number
}
