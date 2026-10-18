# PR 15 — Preserved free Insights and shared analysis foundation (in progress)

This slice is the analytical foundation for Pro (slices 16–18). It is NOT a
paywall slice: no subscription, entitlement, or paid screen appears here. The
two jobs are (1) audit-and-preserve every currently free comparison/history
tool, and (2) implement deterministic, versioned, cross-platform dish-level
calculations with golden fixtures that later engines consume.

Nothing in this slice changes an existing free chart's meaning. The v1 engine
is additive: the Android comparison card keeps its current durations, and the
iOS session-time card keeps its current label. CI proof is pending for every
gate below; nothing here is released.

## 1. Audited free-feature inventory

Every tool below works signed-out and offline (local Room library on Android,
local JSON library on iOS; no account, no network). "Show guide" keeps working
unchanged on both platforms.

### Android (InsightsScreen + detail tabs)

| Free tool | Surface | Free task | Proven by |
|---|---|---|---|
| Cook history | Insights "Learn from your cooks" cards | Browse past cooks, open any cook | Existing `test03_...InsightsWork`; new `InsightsFreeToolsTest.freeHistorySearchFiltersAndStatisticsWorkSignedOut` |
| History search | "Find a cook or dish" field | Search cut, smoker, weight, rub, method, tags | New history test (`insights-search`) |
| Favorites / Rated 4+ chips | Insights filter row | Narrow history to favorites or 4+ ratings | New history test |
| Smoker filter | Insights smoker dropdown | Narrow history to one smoker | New history test |
| Compare completed cooks | "Select cooks" + "Selected comparison" card with overlaid chart | Compare 2–4 finished cooks side by side | New `InsightsFreeToolsTest.freeComparisonShowsRecordedDifferences` |
| Cook statistics | "Cook statistics" cards | Completed count, average time/rating, reading count | New history test (`insights-statistics`) |
| Chart inspection | Detail Charts tab | Elapsed/clock axes, zoom, pan, scrub, series show/isolate, trend, markers, targets, phase bands, dish scope | Existing `CookChartScreenTest`, `test03` |
| Timeline | Detail Timeline tab | Filtered activity history, notes, photos, actions | Existing `CookChartScreenTest`, `test03` |
| Results | Record-results dialog, dish verdicts | Record/edit ratings, cook-again, notes | Existing `test03`, PR 02 suites |
| Show guide | Live/Timeline/Charts guide control | Reference milestones/curves/reminders per meal | Existing `GuidanceScreenTest`, `GuidanceIntegrationTest` |

### iOS (Insights tab + detail sections)

The task brief expected no iOS Insights screen; PR 01 has since added one, so
this inventory records the actual current state.

| Free tool | Surface | Free task | Proven by |
|---|---|---|---|
| Insights totals | Insights tab metric cards | Finished/active counts, reading/note totals, average session time | New `InsightsFreeToolsUITests` (standard + large text) |
| Cook history | Insights "Recently finished" + Meals list + cook detail | Browse and open past cooks | New UITest; existing `MealRecipeFlowsTests` |
| Chart inspection | `CookTemperatureChart` | Clock/elapsed axes, zoom, pan, follow, scrub, channel show/only, trend, markers, targets, phase bands | New UITest asserts the plot; existing guide UITest scrubs recorded data |
| Prior-cook comparison | "Compare a prior cook" controls | Single-reference food/pit channel comparison aligned at Food on/Wrap, with reference save | New UITest asserts the entry; new `InsightsFreeToolsTests.testChartComparisonNeedsMatchingAnchoredChannels` asserts anchored channel inputs |
| Results/verdicts | Verdict sheets + result storage | Record/edit ratings, cook-again, notes | New `InsightsFreeToolsTests.testSignedOutHistoryPhasesVerdictsAndRawChartInputsStayLocal` |
| Show guide | Guide control/milestones/scope | Reference milestones/curves/reminders per meal | Existing `testOneGuideToggleControlsAllSectionsAndPersistsAtStandardAndLargeText`; new unit test for default-off + per-meal persistence; new UITest asserts the toggle on a guided meal |

### Ported gap and documented presentation differences

PR 00's parity row for this slice ("Existing free Insights math → port common
metrics") is resolved by the shared v1 engine below, including the
equipment-hours union, which existed only on Android (`CookPhases.heatingMillis`)
and is now ported to iOS (`InsightsCalculations.unionHeatingMillis`) under the
same golden fixtures.

Remaining presentation differences are documented, not paywalled: Android
compares 2–4 cooks in a selection card while iOS compares one reference curve
in the chart; Android has search chips/smoker filter/statistics cards while
iOS shows totals plus recent history. Both sides keep working free tools; any
convergence belongs to PR 18's personal analysis, which builds on v1 rather
than rebuilding around the gaps.

## 2. Calculation version design

Contract: `contracts/insights-calculations-v1.json`, `calculationVersion` 1,
with byte-identical copies in `app/src/main/assets/`,
`app/src/test/resources/`, and `ios/Contracts/` (enforced by the new
`api-service/test/insights-contract.test.ts`, which runs in the existing
backend gate). The contract pins metric meanings, the phase map, the pit/food
type sets, the temperature unit set, and twelve normative rules.

Engines (same archive in → identical metric values out):

- Android `domain/InsightsCalculations.kt` reuses the existing phase rules
  (`CookPhases.all`, `CookPhases.heatingMillis`, `CookPlanEngine.fahrenheit`)
  and adds the v1 layer: explicit-mode gating, null-when-missing durations,
  anchors, channel stats, results, methods, and equipment union. A unit test
  pins the engine role mapping equal to `RecipeGuideProjection.role`.
- iOS `InsightsCalculations.swift` reuses `CookPhaseEngine`,
  `RecipeGuideProjection.role`, and the same conversion formula, and ports the
  equipment union. Adapters (`data/InsightsArchiveAdapter.kt`,
  `CookStore.insightsArchive()`) map the native stores onto the versioned
  input; adapter tests prove entities/libraries reach the same report as
  fixture JSON.

Key semantics: session creation time is never cooking time; durations require
`explicit_phases` plus a food_on anchor; missing weight/cut/anchors/results
stay missing; events order by (at, id) so edited anchors recompute
deterministically; repeated return-to-heat intervals accumulate; equipment
hours union simultaneous (and touching) intervals; only valid finite °F/°C
readings become measurements; manual points are counted, never interpolated;
channels split on the full six-part identity so a changed probe assignment
never merges dishes; Celsius/Fahrenheit normalize within epsilon 1e-9; every
output records `calculationVersion`.

Comparison honesty (§4): both comparison surfaces now show methods, weights,
units, telemetry quality, and channel coverage next to the numbers
(`comparisonDifferenceLines` on Android, `comparisonContext` on iOS). The
helpers emit factual side-by-side lines only; unit tests assert the exact
lines and the absence of causal/doneness language. A source audit found no
causal ("this wrap made it cook faster") or doneness-prediction copy in the
Insights/chart surfaces.

## 3. Fixture set

`contracts/fixtures/insights-calculations-cases-v1.json`: 14 shared cases,
each an input archive plus expected metric values, consumed by both native
suites and compared by result value (millis/counts exact, doubles within
1e-9):

1. single dish explicit phases fahrenheit — full phases, wrap, rest, hold,
   pit/food/manual channels, rating/cook-again/notes/method, scope-vs-recency
   rating rule
2. celsius twin matches fahrenheit — identical durations and normalized
   extrema across units
3. simultaneous dishes union equipment hours — overlap counts once (9M, not 12M)
4. missing anchors stay missing — null durations despite a finished session
5. legacy session wall clock is not cooking time — anchors reported, durations
   null under `legacy_preserved`
6. edited anchor recomputes deterministically — equal timestamps break ties by id
7. source corrections exclude invalid readings — unavailable/missing-value/
   unknown-unit rows counted, never measured
8. sparse manual data preserved — counts and extrema only, plus `overall`
   rating fallback
9. changed probe assignment splits channels — same probe, two dishes, two
   channels; touching intervals merge
10. repeated return to heat accumulates — two heating spans, counted once each
11. wrap anchors recorded — wrapped time heats; missing wrap stays missing
12. multiple cooks stay independent — per-cook outputs, legacy + explicit mix,
    cook-scoped rating fallback
13. method weight unit differences visible — distinct methods/weights/units
    echoed; °C and °F agree at 212
14. active cook uses evaluation time — `asOfUtcMillis` ends open intervals
    deterministically

## 4. What remains (NOT this slice)

Store entitlement and billing (slice 16), the community aggregate pipeline
and benchmark API (slice 17), personal Pro analysis/customization/recap
(slice 18), and final release verification (slice 19). This slice adds no
backend engine; the backend only enforces contract-copy identity and case
shape. Fresh Actions evidence is required for the Android unit suites, the
API 36 font 1.15/1.5 emulator lanes (companion count now 26 with the new
free-tools class), the backend gate, and the complete iOS run (unit + UI).
