package com.pittech.domain

import com.pittech.ads.AdEntitlement
import com.pittech.ads.shouldAttemptAdRequest
import com.pittech.billing.ProPlacement
import com.pittech.billing.preview
import com.pittech.billing.proDestination
import com.pittech.billing.proPreviewBullets
import com.pittech.billing.ProDestination
import com.pittech.billing.SubscriptionCache
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Slice 18 native lane: Pro personal analysis, advanced curves, Year in Smoke
 * recaps, and customization consume the local v1 report with the same
 * meanings, values, and fixtures as iOS. Missing data stays missing; bests
 * follow declared ratings; wording carries no causation, doneness, or
 * opaque-score claims. */
class ProPersonalTest {
    private fun fixtureRoot(): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/pro-personal-v1.json")).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        assertEquals("pro-personal-v1", root.getString("contractVersion"))
        return root
    }

    private data class Fixture(val archive: InsightArchive, val titles: Map<String, String>, val asOf: Long)

    private fun fixtureArchive(name: String): Fixture {
        val root = fixtureRoot()
        val entry = root.getJSONObject("libraries").getJSONObject(name).getJSONObject("archive")
        val archive = InsightsCalculations.archiveWithLifecycles(entry)
        val titlesJson = root.getJSONObject("titles")
        val titles = titlesJson.keys().asSequence().associateWith { titlesJson.getString(it) }
        return Fixture(archive, titles, root.getLong("asOfUtcMillis"))
    }

    private data class Snapshot(val snapshot: ProPersonalAnalysis.Snapshot, val report: InsightReport, val archive: InsightArchive, val titles: Map<String, String>)

    private fun snapshot(name: String): Snapshot {
        val (archive, titles, asOf) = fixtureArchive(name)
        val report = InsightsCalculations.compute(archive, asOf)
        assertEquals(InsightsCalculations.CALCULATION_VERSION, report.calculationVersion)
        return Snapshot(ProPersonalAnalysis.snapshot(report, titles), report, archive, titles)
    }

    @Test
    fun emptyLibraryShowsHonestEmptyState() {
        val (snapshot, _, _, _) = snapshot("empty")
        assertEquals(ProPersonalAnalysis.LibraryState.EMPTY, snapshot.state)
        assertEquals(0, snapshot.cookCount)
        assertTrue(snapshot.cutGroups.isEmpty())
        assertNull(snapshot.best)
        assertNull(snapshot.timing)
        assertNull(snapshot.methods)
        assertEquals("No recorded cooks yet", snapshot.emptyTitle)
        assertEquals(
            "Personal analysis appears here as you record cooks on this device. Your first finished cook unlocks bests, timing groups, and wrap notes.",
            snapshot.emptyBody,
        )
    }

    @Test
    fun singleCookShowsSparseStateWithAvailableSections() {
        val (snapshot, _, _, _) = snapshot("single")
        assertEquals(ProPersonalAnalysis.LibraryState.SPARSE, snapshot.state)
        assertEquals(1, snapshot.cookCount)
        assertEquals("Record a second cook to compare cuts side by side.", snapshot.comparisonNote)
        assertNotNull(snapshot.emptyBody)
        val best = checkNotNull(snapshot.best)
        assertEquals(
            listOf(
                "First ribs — Baby back",
                "You rated this 4 / 5",
                "Cook-again choice not recorded",
            ),
            best.lines,
        )
        assertEquals("1 of 1 dishes have a recorded rating", best.coverage)
        val timing = checkNotNull(snapshot.timing)
        assertEquals(listOf("Baby back: 5h across 1 recorded cook"), timing.lines)
        assertEquals("Heating time recorded for 1 of 1 dishes · weights recorded for 0 of 1 dishes", timing.coverage)
        val methods = checkNotNull(snapshot.methods)
        assertEquals(listOf("First ribs: wrap not recorded · rest not recorded · holding not recorded"), methods.lines)
        assertEquals("Wrap recorded for 0 of 1 dishes · rest recorded for 0 of 1 dishes · holding recorded for 0 of 1 dishes", methods.coverage)
    }

    @Test
    fun cutComparisonShowsRecordedDifferencesWithCoverage() {
        val (snapshot, _, _, _) = snapshot("many")
        assertEquals(ProPersonalAnalysis.LibraryState.FULL, snapshot.state)
        assertEquals(listOf("Boston butt", "Thighs", "Whole packer"), snapshot.cutGroups.map { it.cut })
        val butt = snapshot.cutGroups.single { it.cut == "Boston butt" }
        assertEquals("Boston butt · 2 recorded cooks", butt.header)
        assertEquals(
            listOf(
                "Saturday pork butt: heating 8h · 8 lb · Smoked · °F readings",
                "February pork butt: heating 7h · 3.5 kg · Pellet · °C readings",
            ),
            butt.lines,
        )
        assertEquals("Heating time recorded for 2 of 2 dishes · weights recorded for 2 of 2 dishes", butt.coverage)
        val thighs = snapshot.cutGroups.single { it.cut == "Thighs" }
        assertEquals(listOf("Weeknight chicken: heating 1h 30m · weight not recorded · Grilled · °F readings"), thighs.lines)
        assertEquals("Heating time recorded for 1 of 1 dishes · weights recorded for 0 of 1 dishes", thighs.coverage)
        val packer = snapshot.cutGroups.single { it.cut == "Whole packer" }
        assertEquals(listOf("Whole packer brisket: heating 11h 30m · 14 lb · Oak smoke · °F readings"), packer.lines)
        assertEquals("Heating time recorded for 1 of 1 dishes · weights recorded for 1 of 1 dishes", packer.coverage)
    }

    @Test
    fun timingAcrossCutsAndWeightsShowsMedianRangeAndCoverage() {
        val (snapshot, _, _, _) = snapshot("many")
        val timing = checkNotNull(snapshot.timing)
        assertEquals(
            listOf(
                "Boston butt: typical (median) 7h 30m across 2 recorded cooks · range 7h – 8h",
                "Thighs: 1h 30m across 1 recorded cook",
                "Whole packer: 11h 30m across 1 recorded cook",
            ),
            timing.lines,
        )
        assertEquals("Heating time recorded for 4 of 4 dishes · weights recorded for 3 of 4 dishes", timing.coverage)
    }

    @Test
    fun personalBestFollowsUserRating() {
        val (snapshot, _, _, _) = snapshot("many")
        val best = checkNotNull(snapshot.best)
        assertEquals(
            listOf(
                "Saturday pork butt — Boston butt",
                "You rated this 5 / 5",
                "Cook again: Yes",
                "Includes your result note",
            ),
            best.lines,
        )
        assertEquals("3 of 4 dishes have a recorded rating", best.coverage)
    }

    @Test
    fun missingAnchorsAndWeightsStayMissing() {
        val (snapshot, _, _, _) = snapshot("many")
        val methods = checkNotNull(snapshot.methods)
        assertEquals(
            listOf(
                "Saturday pork butt: wrapped +3:20 after Food on · rested 50m · holding not recorded",
                "February pork butt: wrapped +3:00 after Food on · rested 10m · held 20m",
                "Whole packer brisket: wrap not recorded · rested 20m · holding not recorded",
                "Weeknight chicken: wrap not recorded · rest not recorded · holding not recorded",
            ),
            methods.lines,
        )
        assertEquals("Wrap recorded for 2 of 4 dishes · rest recorded for 3 of 4 dishes · holding recorded for 1 of 4 dishes", methods.coverage)
        for (line in methods.lines) {
            assertFalse("a zero span must read as not recorded: $line", line.contains("0m"))
        }
    }

    @Test
    fun analysisReusesV1CalculationValues() {
        val (_, report, _, _) = snapshot("many")
        assertEquals(1, report.calculationVersion)
        val heating = report.cooks.flatMap { it.dishes }.associate { it.dishId to it.heatingMillis }
        assertEquals(28800000L, heating["dish-a"])
        assertEquals(25200000L, heating["dish-b"])
        assertEquals(41400000L, heating["dish-c"])
        assertEquals(5400000L, heating["dish-d"])
        // No Pro metric redefines a v1 meaning: the same archive yields the
        // same engine outputs the free surfaces already consume.
        val rest = report.cooks.flatMap { it.dishes }.associate { it.dishId to it.restMillis }
        assertEquals(3000000L, rest["dish-a"])
        assertEquals(600000L, rest["dish-b"])
        assertEquals(1200000L, rest["dish-c"])
        assertEquals(0L, rest["dish-d"])
    }

    @Test
    fun curvesAlignAtFoodOnWithMeasuredSourceSelection() {
        val (_, report, archive, titles) = snapshot("many")
        val options = ProAdvancedCurves.channelOptions(report, archive, titles)
        assertEquals(7, options.size)
        assertTrue(options.any { it.label == "Saturday pork butt · food · Probe 1 · 4 readings (°F)" })
        assertTrue(options.any { it.label == "February pork butt · food · Probe 1 · 3 readings (°C)" })
        assertTrue(options.any { it.label == "Saturday pork butt · food · Probe 1 · 1 reading (°F)" })
        val food = options.single { it.label == "Saturday pork butt · food · Probe 1 · 4 readings (°F)" }
        val celsius = options.single { it.label == "February pork butt · food · Probe 1 · 3 readings (°C)" }
        val aligned = ProAdvancedCurves.align(report, archive, titles, ProAdvancedCurves.Anchor.FOOD_ON, setOf(food.channelId, celsius.channelId))
        assertTrue(aligned.excluded.isEmpty())
        val fahrenheitSeries = aligned.series.single { it.channelId == food.channelId }
        assertEquals(listOf("+1:00 · 150 °F", "+2:00 · 165 °F", "+4:00 · 195 °F", "+8:00 · 203 °F"), fahrenheitSeries.points.map { it.line })
        assertEquals("4 recorded points · anchor recorded · values in °F", fahrenheitSeries.coverage)
        // The °C cook normalizes into the same °F comparison: 70 °C is 158 °F.
        val celsiusSeries = aligned.series.single { it.channelId == celsius.channelId }
        assertEquals(listOf("+1:00 · 158 °F", "+3:00 · 176 °F", "+6:00 · 194 °F"), celsiusSeries.points.map { it.line })
        assertEquals("3 recorded points · anchor recorded · values in °F", celsiusSeries.coverage)
    }

    @Test
    fun curvesAlignAtWrapWithNegativePreWrapOffsets() {
        val (_, report, archive, titles) = snapshot("many")
        val options = ProAdvancedCurves.channelOptions(report, archive, titles)
        val food = options.single { it.label == "Saturday pork butt · food · Probe 1 · 4 readings (°F)" }
        val aligned = ProAdvancedCurves.align(report, archive, titles, ProAdvancedCurves.Anchor.WRAP, setOf(food.channelId))
        assertTrue(aligned.excluded.isEmpty())
        val series = aligned.series.single()
        assertEquals(listOf("-2:20 · 150 °F", "-1:20 · 165 °F", "+0:40 · 195 °F", "+4:40 · 203 °F"), series.points.map { it.line })
    }

    @Test
    fun curvesExcludeChannelsWithoutTheAnchor() {
        val (_, report, archive, titles) = snapshot("many")
        val options = ProAdvancedCurves.channelOptions(report, archive, titles)
        val brisket = options.single { it.label == "Whole packer brisket · food · Probe 1 · 3 readings (°F)" }
        val chicken = options.single { it.label == "Weeknight chicken · food · Probe 1 · 2 readings (°F)" }
        val aligned = ProAdvancedCurves.align(report, archive, titles, ProAdvancedCurves.Anchor.WRAP, setOf(brisket.channelId, chicken.channelId))
        assertTrue(aligned.series.isEmpty())
        assertEquals(
            listOf(
                "Whole packer brisket · food · Probe 1: Wrap not recorded for this cook — excluded, never estimated.",
                "Weeknight chicken · food · Probe 1: Wrap not recorded for this cook — excluded, never estimated.",
            ),
            aligned.excluded,
        )
    }

    @Test
    fun recapFullScopeMatchesFixtureExpectation() {
        val (_, report, _, titles) = snapshot("many")
        val scope = ProRecap.Scope("2026", listOf("cook-a", "cook-b", "cook-c", "cook-d"), true, true, true, "")
        val card = checkNotNull(ProRecap.build(report, titles, scope, 4))
        assertEquals("Year in Smoke · 2026", card.title)
        assertEquals(listOf("Saturday pork butt", "February pork butt", "Whole packer brisket", "Weeknight chicken"), card.dishes)
        assertEquals(listOf("Recorded cooks: 4", "Heating time: 28h", "Best recorded rating: 5 / 5"), card.stats.map { "${it.label}: ${it.value}" })
        assertEquals(5, card.milestones.size)
        assertEquals(6, card.totalMilestones)
        assertEquals("Wrapped +3:20 after Food on", card.milestones.first().title)
        assertEquals("°F", card.unit)
        assertEquals("4 of 4 recorded cooks selected · 6 recorded milestones · showing 5", card.coverageNote)
        assertEquals("Only the 4 selected cooks appear in this recap. Sharing is a separate explicit step; nothing here changes what is public.", card.privacyNote)
        val expected = fixtureRoot().getJSONObject("recap").getJSONObject("expectedFull")
        assertEquals(card.title, expected.getString("title"))
        assertEquals(card.dishes.size, expected.getInt("dishCount"))
        assertEquals(card.stats.map { "${it.label}: ${it.value}" }, (0 until expected.getJSONArray("stats").length()).map { expected.getJSONArray("stats").getString(it) })
        assertEquals(card.milestones.size, expected.getInt("milestoneCount"))
    }

    @Test
    fun recapScopeExcludesUnselectedCooks() {
        val (_, report, _, titles) = snapshot("many")
        val scope = ProRecap.Scope("2026", listOf("cook-a", "cook-b", "cook-c"), true, true, true, "")
        val card = checkNotNull(ProRecap.build(report, titles, scope, 4))
        assertEquals(listOf("Saturday pork butt", "February pork butt", "Whole packer brisket"), card.dishes)
        assertFalse(card.dishes.contains("Weeknight chicken"))
        assertEquals(listOf("Recorded cooks: 3", "Heating time: 26h 30m", "Best recorded rating: 5 / 5"), card.stats.map { "${it.label}: ${it.value}" })
        assertFalse(card.milestones.any { it.dish == "Weeknight chicken" })
        assertEquals("3 of 4 recorded cooks selected · 5 recorded milestones", card.coverageNote)
        assertEquals("Only the 3 selected cooks appear in this recap. Sharing is a separate explicit step; nothing here changes what is public.", card.privacyNote)
        val expected = fixtureRoot().getJSONObject("recap").getJSONObject("expectedExcluded")
        assertEquals(card.dishes.size, expected.getInt("dishCount"))
        assertEquals(card.stats.map { "${it.label}: ${it.value}" }, (0 until expected.getJSONArray("stats").length()).map { expected.getJSONArray("stats").getString(it) })
    }

    @Test
    fun recapCardUsesShareCardSchemaAndEmptyScopeBuildsNothing() {
        val (_, report, _, titles) = snapshot("many")
        val scope = ProRecap.Scope("2026", listOf("cook-a", "cook-d"), true, false, false, "")
        val card = checkNotNull(ProRecap.build(report, titles, scope, 4))
        assertEquals(listOf("Recorded cooks", "Heating time"), card.stats.map { it.label })
        assertTrue(card.milestones.isEmpty())
        assertEquals("2 of 4 recorded cooks selected", card.coverageNote)
        val content = ProRecap.shareCardContent(card, "ember", "color-vision-safe")
        assertEquals(1, content.getInt("version"))
        assertEquals("none", content.getString("hero"))
        assertEquals("ember", content.getString("theme"))
        assertEquals("color-vision-safe", content.getString("palette"))
        assertTrue(content.isNull("relay"))
        assertEquals(2, content.getJSONArray("dishes").length())
        assertTrue((content.getJSONArray("milestones").length()) <= 5)
        assertTrue((content.getJSONArray("stats").length()) <= 3)
        val empty = ProRecap.Scope("2026", emptyList(), true, true, true, "")
        assertNull(ProRecap.build(report, titles, empty, 4))
    }

    @Test
    fun customizationPreviewApplyPersists() {
        val store = ProCustomizationStore(MemoryCustomizationBacking())
        assertEquals("classic", store.themeId)
        assertEquals("classic", store.paletteId)
        assertNull(store.profileAccentId)
        assertEquals("standard", store.profileLayout)
        assertEquals("classic", store.iconId)
        store.previewThemeId = "ember"
        assertEquals("classic", store.themeId)
        store.applyTheme("ember")
        assertEquals("ember", store.themeId)
        assertNull(store.previewThemeId)
        store.applyPalette("color-vision-safe")
        assertEquals("color-vision-safe", store.paletteId)
        store.applyProfile("slate", "compact")
        assertEquals("slate", store.profileAccentId)
        assertEquals("compact", store.profileLayout)
    }

    @Test
    fun expiredEntitlementFallsBackToClassicWithSelectionsRetained() {
        val store = ProCustomizationStore(MemoryCustomizationBacking())
        store.applyTheme("ember")
        store.applyPalette("color-vision-safe")
        store.applyProfile("slate", "compact")
        assertEquals("classic", ProCustomization.effectiveTheme(store.themeId, false).id)
        assertEquals("classic", ProCustomization.effectivePalette(store.paletteId, false).id)
        assertNull(ProCustomization.effectiveProfileAccent(ProCustomization.PublicIdentity.NAMED, store.profileAccentId, false))
        assertEquals("standard", ProCustomization.effectiveProfileLayout(ProCustomization.PublicIdentity.NAMED, store.profileLayout, false))
        assertEquals("classic", ProCustomization.effectiveIcon("ember", false).id)
        // The fallback never clears the stored selections: resubscribing restores them.
        assertEquals("ember", store.themeId)
        assertEquals("color-vision-safe", store.paletteId)
        assertEquals("slate", store.profileAccentId)
        assertEquals("ember", ProCustomization.effectiveTheme(store.themeId, true).id)
        assertEquals("color-vision-safe", ProCustomization.effectivePalette(store.paletteId, true).id)
        // Unknown ids fail safe to Classic whether or not the account is entitled.
        assertEquals("classic", ProCustomization.effectiveTheme("nope", true).id)
        assertEquals("classic", ProCustomization.effectivePalette("nope", true).id)
        assertEquals("classic", ProCustomization.effectiveIcon("nope", true).id)
    }

    @Test
    fun paletteContrastMeetsThresholds() {
        val customization = fixtureRoot().getJSONObject("customization")
        val themes = customization.getJSONArray("themes")
        assertEquals(ProCustomization.cardThemes.map { it.id }.toSet(), (0 until themes.length()).map { themes.getJSONObject(it).getString("id") }.toSet())
        for (i in 0 until themes.length()) {
            val theme = themes.getJSONObject(i)
            val pinned = ProCustomization.cardThemes.single { it.id == theme.getString("id") }
            assertEquals(theme.getString("background"), pinned.background)
            assertEquals(theme.getString("ink"), pinned.ink)
            assertEquals(theme.getString("supporting"), pinned.supporting)
            assertEquals(theme.getString("accent"), pinned.accent)
            val ink = checkNotNull(ProCustomization.contrastRatio(pinned.ink, pinned.background))
            val supporting = checkNotNull(ProCustomization.contrastRatio(pinned.supporting, pinned.background))
            assertTrue("${pinned.id} ink", ink >= 4.5)
            assertTrue("${pinned.id} supporting", supporting >= 4.5)
        }
        val palettes = customization.getJSONArray("chartPalettes")
        assertEquals(ProCustomization.chartPalettes.map { it.id }.toSet(), (0 until palettes.length()).map { palettes.getJSONObject(it).getString("id") }.toSet())
        for (i in 0 until palettes.length()) {
            val palette = palettes.getJSONObject(i)
            val pinned = ProCustomization.chartPalettes.single { it.id == palette.getString("id") }
            assertEquals((0 until palette.getJSONArray("colors").length()).map { palette.getJSONArray("colors").getString(it) }, pinned.colors)
            assertEquals(palette.getString("intendedBackground"), pinned.intendedBackground)
            for (hex in pinned.colors) {
                val ratio = checkNotNull(ProCustomization.contrastRatio(hex, pinned.intendedBackground))
                assertTrue("${pinned.id} $hex", ratio >= 3.0)
            }
        }
    }

    @Test
    fun anonymousProjectionCarriesNoProIdentity() {
        assertEquals("slate", ProCustomization.effectiveProfileAccent(ProCustomization.PublicIdentity.NAMED, "slate", true)?.id)
        assertEquals("compact", ProCustomization.effectiveProfileLayout(ProCustomization.PublicIdentity.NAMED, "compact", true))
        for (entitled in listOf(true, false)) {
            assertNull(
                "anonymous content must never carry a Pro accent (entitled: $entitled)",
                ProCustomization.effectiveProfileAccent(ProCustomization.PublicIdentity.ANONYMOUS, "slate", entitled),
            )
            assertEquals(
                "standard",
                ProCustomization.effectiveProfileLayout(ProCustomization.PublicIdentity.ANONYMOUS, "compact", entitled),
            )
        }
    }

    @Test
    fun iconVariantNamesUsePlatformMechanism() {
        assertEquals(listOf("classic", "ember", "midnight"), ProCustomization.iconVariants.map { it.id })
        assertNull(ProCustomization.icon("classic").aliasSuffix)
        assertEquals("ProIconEmber", ProCustomization.icon("ember").aliasSuffix)
        assertEquals("ProIconMidnight", ProCustomization.icon("midnight").aliasSuffix)
        assertNull("unknown ids reset to the primary alias", ProCustomization.icon("nope").aliasSuffix)
    }

    @Test
    fun shareCardThemeValidationAcceptsCuratedSet() {
        assertEquals(setOf("classic", "ember", "sage-night", "parchment"), ProCustomization.shareCardThemeIds)
        for (id in ProCustomization.shareCardThemeIds) {
            assertEquals(id, ProCustomization.theme(id).id)
        }
        assertEquals("classic", ProCustomization.theme("nope").id)
        assertEquals("classic", ProCustomization.palette("nope").id)
    }

    @Test
    fun entitledOfflineCacheOpensProViews() {
        val now = System.currentTimeMillis()
        fun instant(millis: Long) = Instant.ofEpochMilli(millis).toString()
        val cache = SubscriptionCache.parse(
            JSONObject(
                """{"version":"entitlement-cache-v1","environment":"production","verified":true,"state":"active","verifiedUntil":"${instant(now + 30 * 86400000)}","lastVerifiedAt":"${instant(now - 86400000)}","offlineValidUntil":"${instant(now + 6 * 86400000)}","contractVersion":"store-verification-v1"}""",
            ),
        )
        assertTrue(cache.offlineUsable(now))
        assertEquals(AdEntitlement.ENTITLED, cache.entitlement(now))
        assertEquals(ProDestination.PRO, proDestination(true, AdEntitlement.ENTITLED, "active", true))
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, null, true))
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, null, false))
        assertFalse(shouldAttemptAdRequest(AdEntitlement.ENTITLED, previouslyEntitled = false))
        assertFalse(shouldAttemptAdRequest(AdEntitlement.UNKNOWN, previouslyEntitled = true))
    }

    @Test
    fun wordingGuardsForbidCausationDonenessAndOpaqueScores() {
        val (snapshot, report, archive, titles) = snapshot("many")
        val strings = mutableListOf(
            ProPlacement.ANALYSIS.preview, ProPlacement.CURVES.preview,
            ProPlacement.THEMES.preview, ProPlacement.MORE.preview,
        )
        strings.addAll(proPreviewBullets(ProPlacement.ANALYSIS) + proPreviewBullets(ProPlacement.CURVES))
        strings.addAll(proPreviewBullets(ProPlacement.THEMES) + proPreviewBullets(ProPlacement.MORE))
        for (group in snapshot.cutGroups) {
            strings.add(group.header)
            strings.add(group.coverage)
            strings.addAll(group.lines)
        }
        snapshot.best?.let { strings.addAll(it.lines + it.notes + it.coverage) }
        snapshot.timing?.let { strings.addAll(it.lines + it.coverage) }
        snapshot.methods?.let { strings.addAll(it.lines + it.coverage) }
        val options = ProAdvancedCurves.channelOptions(report, archive, titles)
        strings.addAll(options.map { it.label })
        val aligned = ProAdvancedCurves.align(report, archive, titles, ProAdvancedCurves.Anchor.FOOD_ON, options.map { it.channelId }.toSet())
        for (series in aligned.series) {
            strings.add(series.label)
            strings.add(series.coverage)
            strings.addAll(series.points.map { it.line })
        }
        strings.addAll(aligned.excluded)
        val scope = ProRecap.Scope.all(report.cooks.map { it.cookId }, "2026")
        ProRecap.build(report, titles, scope, 4)?.let { card ->
            strings.add(card.title)
            strings.add(card.coverageNote)
            strings.add(card.privacyNote)
            strings.add(card.note)
            strings.addAll(card.dishes)
            strings.addAll(card.stats.map { "${it.label}: ${it.value}" })
            strings.addAll(card.milestones.map { "${it.dish} ${it.title}" })
        }
        strings.addAll(ProCustomization.cardThemes.map { it.name } + ProCustomization.chartPalettes.map { it.name })
        strings.addAll(ProCustomization.profileAccents.map { it.name } + ProCustomization.iconVariants.map { it.name })
        val banned = listOf("faster", "slower", "because", "caus", "predict", "doneness", "undercook", "overcook", "rank", "leaderboard", "score", "quality")
        for (string in strings) {
            for (word in banned) {
                assertFalse("$string contains $word", string.lowercase().contains(word))
            }
            // "safe" is banned except inside the required "color-vision safe"
            // palette name: no safe-doneness language anywhere.
            val scrubbed = string.lowercase().replace("color-vision safe", "")
            assertFalse("$string contains safe", scrubbed.contains("safe"))
            assertFalse("$string contains should", Regex("\\bshould\\b").containsMatchIn(string.lowercase()))
        }
        // Bests are declared ratings, never a computed score: the word "rated"
        // appears, the word "score" never does.
        assertTrue(checkNotNull(snapshot.best).lines.joinToString().contains("rated"))
    }
}
