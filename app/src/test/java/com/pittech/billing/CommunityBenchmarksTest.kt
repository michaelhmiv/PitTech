package com.pittech.billing

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Slice 17 native lane: benchmark cards consume the merged aggregate API with
 * the same meanings, counts, and fixtures as iOS. Every figure renders with
 * its eligible n and compute date; suppressed cells stay honest; unknown or
 * malformed figures fail closed to the unavailable state. */
class CommunityBenchmarksTest {
    private class FakeAPI : CommunityBenchmarksAPI {
        val aggregateCalls = mutableListOf<Triple<String?, String?, String?>>()
        var result: Result<JSONObject> = Result.failure(CommunityBenchmarksFailure())

        override suspend fun aggregates(bucket: String?, metric: String?, runId: String?, access: String?): JSONObject {
            aggregateCalls.add(Triple(bucket, metric, runId))
            if (access == null) throw CommunityBenchmarksFailure(401)
            return result.getOrThrow()
        }

        override suspend fun jobRuns(limit: Int?, cursor: String?, access: String?): JSONObject {
            if (access == null) throw CommunityBenchmarksFailure(401)
            return JSONObject().put("runs", org.json.JSONArray()).put("nextCursor", JSONObject.NULL)
        }
    }

    private class Mismatches(var count: Int = 0)

    private fun fixturePayload(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/community-benchmarks-v1.json")).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        assertEquals("community-benchmarks-v1", root.getString("contractVersion"))
        return root.getJSONObject("payloads").getJSONObject(name)
    }

    private fun loader(api: FakeAPI = FakeAPI(), token: String? = "A".repeat(43), mismatches: Mismatches = Mismatches()) =
        CommunityBenchmarksLoader(api = api, session = { token }, onAuthMismatch = { mismatches.count++ })

    @Test
    fun sharedFixtureVisibleFiguresDecodeWithNAndDate() {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("visible"))
        assertEquals("12345678-1234-4234-8234-123456789abc", payload.run!!.runId)
        assertEquals(4, payload.figures.size)
        val (cards, omitted) = CommunityBenchmarks.cards(payload.figures)
        assertEquals(4, cards.size)
        assertTrue(omitted.isEmpty())
        val heating = cards.single { it.title == "Typical heating time" }
        assertEquals("community-card-median-heating-time-v1-pork-butt-shoulder", heating.testTag)
        assertEquals("Pork butt / shoulder · Medium · Method: smoke", heating.bucketLine)
        assertEquals(
            listOf(
                "Typical (median): 5h 30m",
                "Middle half: 3h 15m – 7h 45m",
                "Recorded range: 1h – 10h",
            ),
            heating.lines,
        )
        assertEquals("10 eligible cooks · Computed 2026-10-18", heating.coverageLine)
        assertFalse(heating.insufficient)
        for (card in cards) {
            assertTrue(card.title, card.coverageLine.contains("10 eligible cooks"))
            assertTrue(card.title, card.coverageLine.contains("2026-10-18"))
        }
    }

    @Test
    fun insufficientDataCardShowsNAndDateWithoutFigure() {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("suppressed"))
        val (cards, _) = CommunityBenchmarks.cards(payload.figures)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertTrue(card.insufficient)
        assertEquals("community-insufficient-median-heating-time-v1-pork-loin", card.testTag)
        assertEquals("Typical heating time", card.title)
        assertEquals("Pork loin · Medium · Method: smoke", card.bucketLine)
        assertEquals(
            listOf(
                "Not enough cooks yet",
                "9 of 10 eligible cooks recorded. Figures appear when 10 distinct eligible cooks are recorded.",
            ),
            card.lines,
        )
        assertEquals("9 eligible cooks · Computed 2026-10-18", card.coverageLine)
        for (line in card.lines) {
            assertFalse("a suppressed cell must never partially render: $line", line.contains("Typical (median)"))
        }
    }

    @Test
    fun runNullMapsToEmptyState() = runBlocking {
        val api = FakeAPI()
        api.result = Result.success(fixturePayload("empty"))
        val loader = loader(api)
        loader.load()
        assertEquals(CommunityBenchmarksState.Empty, loader.state.value)
    }

    @Test
    fun curveIntervalSuppressionShowsNAndCoverage() {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("visible"))
        val (cards, _) = CommunityBenchmarks.cards(payload.figures)
        val curve = cards.single { it.title == "Temperature percentile bands" }
        assertEquals(
            listOf(
                "+0:00–0:15",
                "Pit: p10 100.9 °C · p50 104.5 °C · p90 108.1 °C (n=10, 100% of cooks)",
                "Food: p10 50.9 °C · p50 54.5 °C · p90 58.1 °C (n=10, 100% of cooks)",
                "+0:15–0:30",
                "Pit: p10 110.0 °C · p50 115.5 °C · p90 120.2 °C (n=10, 100% of cooks)",
                "Food band hidden: 3 of 10 cooks in this interval (30%). Bands need 10 cooks.",
            ),
            curve.lines,
        )
        assertEquals(listOf("Percentile bands summarize many cooks. They are not one cook's readings."), curve.notes)
        assertEquals("10 eligible cooks · Computed 2026-10-18", curve.coverageLine)
    }

    @Test
    fun stallCardCarriesSparseExclusionNote() {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("visible"))
        val (cards, _) = CommunityBenchmarks.cards(payload.figures)
        val stall = cards.single { it.title == "Stall summary" }
        assertEquals(
            listOf(
                "Stalled: 6 of 10 assessed cooks (60%)",
                "Stall start (median): +0:00",
                "Stall length (median): 2h",
                "Stall temperature (median): 72.5 °C",
            ),
            stall.lines,
        )
        assertEquals(listOf("Measured stall windows only; sparse attempts are excluded from n, never interpolated."), stall.notes)
        assertTrue(stall.notes.single().contains("excluded"))
        assertTrue(stall.notes.single().contains("never interpolated"))
        assertEquals("10 eligible cooks · Computed 2026-10-18", stall.coverageLine)
    }

    @Test
    fun wrapCardShowsPrevalenceWithoutCausation() {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("visible"))
        val (cards, _) = CommunityBenchmarks.cards(payload.figures)
        val wrap = cards.single { it.title == "Wrap and spritz prevalence" }
        assertEquals(
            listOf(
                "Wrapped: 6 of 10 (60%)",
                "Spritzed or mopped: 3 of 10 (30%)",
                "Method smoke: 10 of 10 (100%)",
            ),
            wrap.lines,
        )
        assertEquals(listOf("Prevalence only: these shares describe what cooks recorded."), wrap.notes)
        assertEquals("10 eligible cooks · Computed 2026-10-18", wrap.coverageLine)
    }

    @Test
    fun bucketValidationRejectsUnknownGroupsAndBadShapes() {
        assertTrue(CommunityBenchmarks.bucketAllowed("pork-butt-shoulder|medium|method:smoke"))
        assertTrue(CommunityBenchmarks.bucketAllowed("pork-loin|unknown|method:unknown"))
        assertTrue(CommunityBenchmarks.bucketAllowed("beef-brisket|large|method:smoke|equipment:offset"))
        assertFalse(CommunityBenchmarks.bucketAllowed("evil"))
        assertFalse(CommunityBenchmarks.bucketAllowed("pork-butt-shoulder|medium"))
        assertFalse(CommunityBenchmarks.bucketAllowed("brisket|medium|method:smoke"))
        assertFalse(CommunityBenchmarks.bucketAllowed("pork-loin|xl|method:smoke"))
        assertFalse(CommunityBenchmarks.bucketAllowed("pork-loin|medium|smoke"))
        assertFalse(CommunityBenchmarks.bucketAllowed("pork-loin|medium|method:smoke|equipment:offset|extra"))
        assertTrue(CommunityBenchmarks.metricAllowed("median-heating-time-v1"))
        assertTrue(CommunityBenchmarks.metricAllowed("pit-food-curve-percentiles-v1"))
        assertTrue(CommunityBenchmarks.metricAllowed("stall-summary-v1"))
        assertTrue(CommunityBenchmarks.metricAllowed("wrap-spritz-prevalence-v1"))
        assertFalse(CommunityBenchmarks.metricAllowed("stub-heating-presence"))
        assertFalse(CommunityBenchmarks.metricAllowed("future-metric-v99"))
        assertTrue(CommunityBenchmarks.runIdValid("12345678-1234-4234-8234-123456789abc"))
        assertFalse(CommunityBenchmarks.runIdValid("not-a-uuid"))
        assertTrue(CommunityBenchmarks.jobRunsLimitValid(1))
        assertTrue(CommunityBenchmarks.jobRunsLimitValid(100))
        assertFalse(CommunityBenchmarks.jobRunsLimitValid(0))
        assertFalse(CommunityBenchmarks.jobRunsLimitValid(101))
    }

    @Test
    fun unknownMetricFailsClosedToUnavailable() = runBlocking {
        val payload = fixturePayload("unknownMetric")
        try {
            CommunityBenchmarks.parseAggregates(payload)
            fail("an unknown metric ID must fail parsing")
        } catch (e: CommunityUnknownMetricException) {
            assertEquals("future-metric-v99", e.metricId)
        }
        val api = FakeAPI()
        api.result = Result.success(payload)
        val loader = loader(api)
        loader.load()
        assertEquals(CommunityBenchmarksState.Unavailable, loader.state.value)
    }

    @Test
    fun malformedFigureFailsClosedToUnavailable() = runBlocking {
        val payload = fixturePayload("malformed")
        try {
            CommunityBenchmarks.parseAggregates(payload)
            fail("a malformed figure must fail parsing")
        } catch (_: Exception) { }
        val api = FakeAPI()
        api.result = Result.success(payload)
        val loader = loader(api)
        loader.load()
        assertEquals(CommunityBenchmarksState.Unavailable, loader.state.value)
    }

    @Test
    fun unsupportedSummaryOmitsFigureWithCoverageNoteIntact() = runBlocking {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("unsupportedKind"))
        val (cards, omitted) = CommunityBenchmarks.cards(payload.figures)
        assertEquals(1, cards.size)
        assertEquals("Typical heating time", cards.single().title)
        assertEquals(1, omitted.size)
        val note = omitted.single()
        assertEquals("Temperature percentile bands", note.title)
        assertTrue(note.note, note.note.contains("10 eligible cooks"))
        assertTrue(note.note, note.note.contains("2026-10-18"))
        val api = FakeAPI()
        api.result = Result.success(fixturePayload("unsupportedKind"))
        val loader = loader(api)
        loader.load()
        val snapshot = (loader.state.value as? CommunityBenchmarksState.Loaded)?.snapshot
        assertEquals(1, snapshot?.cards?.size)
        assertEquals(1, snapshot?.omitted?.size)
    }

    @Test
    fun missingBandOmitsOnlyThatBandWithNoteIntact() {
        val figure = CommunityFigure.parse(
            JSONObject(
                """{"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"pit-food-curve-percentiles-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"intervalMillis":900000,"kind":"percentile-bands","intervals":[{"startElapsedMillis":0,"endElapsedMillis":900000,"pit":{"n":10,"coverage":1,"p10":100.9,"p50":104.5,"p90":108.1}}]},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"}""",
            ),
        )
        val (cards, _) = CommunityBenchmarks.cards(listOf(figure))
        val card = cards.single()
        assertEquals(
            listOf(
                "+0:00–0:15",
                "Pit: p10 100.9 °C · p50 104.5 °C · p90 108.1 °C (n=10, 100% of cooks)",
                "Food band unavailable for this interval.",
            ),
            card.lines,
        )
        assertEquals("10 eligible cooks · Computed 2026-10-18", card.coverageLine)
    }

    @Test
    fun emptyStalledSubsetShowsNoMedians() {
        val figure = CommunityFigure.parse(
            JSONObject(
                """{"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"stall-summary-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"nStalled":0,"prevalence":0,"startElapsedMillis":{"p25":null,"median":null,"p75":null},"durationMillis":{"p25":null,"median":null,"p75":null},"tempCelsius":{"p25":null,"median":null,"p75":null}},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"}""",
            ),
        )
        val (cards, _) = CommunityBenchmarks.cards(listOf(figure))
        val card = cards.single()
        assertEquals(
            listOf(
                "Stalled: 0 of 10 assessed cooks (0%)",
                "No stalled cooks in this group.",
            ),
            card.lines,
        )
        assertEquals(listOf("Sparse attempts are excluded from n, never interpolated."), card.notes)
    }

    @Test
    fun signedOutPerformsNoNetworkCall() = runBlocking {
        val api = FakeAPI()
        val mismatches = Mismatches()
        val loader = loader(api, token = null, mismatches = mismatches)
        loader.load()
        assertEquals(CommunityBenchmarksState.SignedOut, loader.state.value)
        assertTrue("a signed-out loader must not touch the network", api.aggregateCalls.isEmpty())
        assertEquals(1, mismatches.count)
    }

    @Test
    fun api401MapsToSignedOutAnd403ToForbidden() = runBlocking {
        for ((status, expected) in listOf(401 to CommunityBenchmarksState.SignedOut, 403 to CommunityBenchmarksState.Forbidden)) {
            val api = FakeAPI()
            api.result = Result.failure(CommunityBenchmarksFailure(status))
            val mismatches = Mismatches()
            val loader = loader(api, mismatches = mismatches)
            loader.load()
            assertEquals("status $status", expected, loader.state.value)
            assertEquals("one convergence refresh per view lifetime", 1, mismatches.count)
            loader.load()
            assertEquals("the refresh must not loop", 1, mismatches.count)
        }
    }

    @Test
    fun offlineErrorMapsToUnavailable() = runBlocking {
        for (error in listOf(CommunityBenchmarksFailure(), CommunityBenchmarksFailure(404), CommunityBenchmarksFailure(500))) {
            val api = FakeAPI()
            api.result = Result.failure(error)
            val loader = loader(api)
            loader.load()
            assertEquals("$error", CommunityBenchmarksState.Unavailable, loader.state.value)
        }
        val loader = loader()
        loader.reset()
        assertEquals(CommunityBenchmarksState.Loading, loader.state.value)
    }

    @Test
    fun wordingGuardsForbidRankingDonenessAndCausationClaims() {
        val payload = CommunityBenchmarks.parseAggregates(fixturePayload("mixed"))
        val (cards, _) = CommunityBenchmarks.cards(payload.figures)
        val strings = mutableListOf(ProPlacement.BENCHMARKS.preview).apply { addAll(proPreviewBullets(ProPlacement.BENCHMARKS)) }
        for (card in cards) {
            strings.add(card.title)
            strings.add(card.bucketLine)
            strings.add(card.coverageLine)
            strings.addAll(card.lines)
            strings.addAll(card.notes)
        }
        val banned = listOf("faster", "slower", "because", "caus", "predict", "safe", "doneness", "undercook", "overcook", "rank", "leaderboard")
        for (string in strings) {
            for (word in banned) {
                assertFalse("$string contains $word", string.lowercase().contains(word))
            }
            // "should" is a whole-word check: the cut name "shoulder" in a
            // bucket line is a label, not a directive claim.
            assertFalse("$string contains should", Regex("\\bshould\\b").containsMatchIn(string.lowercase()))
        }
        val curve = cards.single { it.title == "Temperature percentile bands" }
        assertTrue(curve.title.contains("percentile bands"))
        assertTrue(curve.notes.joinToString().contains("Percentile bands"))
        for (string in listOf(curve.title, curve.bucketLine, curve.coverageLine) + curve.lines + curve.notes) {
            assertFalse("$string must never read as one cook's measurements", string.lowercase().contains("individual"))
        }
    }

    @Test
    fun loinAndButtNeverShareAGroupLabel() {
        assertEquals("Pork loin", CommunityBenchmarks.groupLabel("pork-loin"))
        assertEquals("Pork butt / shoulder", CommunityBenchmarks.groupLabel("pork-butt-shoulder"))
        assertTrue(CommunityBenchmarks.groupLabel("pork-loin") != CommunityBenchmarks.groupLabel("pork-butt-shoulder"))
        val labels = CommunityBenchmarks.GROUPS.map(CommunityBenchmarks::groupLabel)
        assertEquals("every taxonomy group needs its own label", labels.size, labels.toSet().size)
        assertTrue(CommunityBenchmarks.bucketLine("pork-loin|medium|method:smoke").contains("Pork loin"))
        assertTrue(CommunityBenchmarks.bucketLine("pork-butt-shoulder|medium|method:smoke").contains("Pork butt / shoulder"))
    }

    @Test
    fun durationFormattingPinsSharedRules() {
        assertEquals("5h 30m", CommunityBenchmarks.formatHours(19800000))
        assertEquals("3h 15m", CommunityBenchmarks.formatHours(11700000))
        assertEquals("7h 45m", CommunityBenchmarks.formatHours(27900000))
        assertEquals("1h", CommunityBenchmarks.formatHours(3600000))
        assertEquals("10h", CommunityBenchmarks.formatHours(36000000))
        assertEquals("45m", CommunityBenchmarks.formatHours(2700000))
        assertEquals("2h", CommunityBenchmarks.formatHours(7200000))
        assertEquals("4h 30m", CommunityBenchmarks.formatHours(16200000))
        assertEquals("+0:00", CommunityBenchmarks.formatElapsed(0))
        assertEquals("+0:15", CommunityBenchmarks.formatElapsed(900000))
        assertEquals("+0:30", CommunityBenchmarks.formatElapsed(1800000))
        assertEquals("+2:00", CommunityBenchmarks.formatElapsed(7200000))
        assertEquals("60%", CommunityBenchmarks.formatPercent(0.6))
        assertEquals("100%", CommunityBenchmarks.formatPercent(1.0))
        assertEquals("30%", CommunityBenchmarks.formatPercent(0.3))
        assertEquals("0%", CommunityBenchmarks.formatPercent(0.0))
        assertEquals("104.5 °C", CommunityBenchmarks.formatTemp(104.5))
        assertEquals("72.5 °C", CommunityBenchmarks.formatTemp(72.5))
        assertEquals("110.0 °C", CommunityBenchmarks.formatTemp(110.0))
    }

    @Test
    fun clientRejectsInvalidAggregatesFiltersWithoutNetwork() = runBlocking {
        var calls = 0
        val client = CommunityBenchmarksClient(
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build(),
        )
        val token = "A".repeat(43)
        val invalid = listOf(
            suspend { client.aggregates("evil", null, null, token) },
            suspend { client.aggregates(null, "stub-heating-presence", null, token) },
            suspend { client.aggregates(null, null, "not-a-uuid", token) },
            suspend { client.aggregates(null, null, null, "short") },
            suspend { client.aggregates(null, null, null, null) },
        )
        for ((index, call) in invalid.withIndex()) {
            try {
                call()
                fail("invalid aggregates call accepted: $index")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }

    @Test
    fun jobRunsValidationRejectsBadLimitAndCursor() = runBlocking {
        var calls = 0
        val client = CommunityBenchmarksClient(
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build(),
        )
        val token = "A".repeat(43)
        val invalid = listOf(
            suspend { client.jobRuns(0, null, token) },
            suspend { client.jobRuns(101, null, token) },
            suspend { client.jobRuns(null, "bad", token) },
            suspend { client.jobRuns(null, null, null) },
        )
        for ((index, call) in invalid.withIndex()) {
            try {
                call()
                fail("invalid job-runs call accepted: $index")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }
}
