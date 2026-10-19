# PR 17 — Community aggregate pipeline, backend completion (done); native cards (implemented, Actions pending)

The backend-prep half built the aggregation job scaffolding, deduplication,
forbidden-input guards, removal propagation, and the n=9/n=10
suppression-boundary mechanics on isolated backend files. This completion lane
fills the plug-in points with the reviewed versioned taxonomy, the four real
metric summarizers, production scheduling, and the Pro-authorized read API. The
stubs remain only for the framework's own unit tests, still labeled
non-authoritative; production wiring uses the real taxonomy and metrics (pinned
by a wiring test).

Hard constraints hold for everything in this lane: aggregates read ONLY
deliberately active public measured attempts; the private sync database is
never an input and editable recipe instructions are never an input; metrics
below 10 distinct eligible attempts are suppressed; there is no author ranking
from inferred quality and no claim that community norms establish safe doneness.

## Job design

`api-service/src/community-aggregation.ts` holds the job. `build()` is a pure
function of (input snapshot, cutoff, taxonomy, metric specs, clock day):
dedupe groups are processed in sorted key order, figures are sorted by
(bucket, metricId), and output bytes are canonical `stable()` JSON, so repeated
runs over the same snapshot are byte-identical. Figures carry a UTC compute
DATE (not a timestamp), so repeats within a day agree exactly; the run record
keeps the exact compute time.

Every run records its input cutoff (`cutoff_millis`: contributions with
`collected_at` past the cutoff wait for a later run), its algorithm version
(`community-aggregation-framework-v0`), taxonomy/band versions, and the
metric-set hash. Recording is keyed by
(cutoff, algorithm, taxonomy, band, metric-set, input hash): re-running an
identical job replays the stored bytes instead of minting a duplicate run.
`MemoryJobRunStore` and `PostgresCommunityStore` implement the same record with
`replayed` semantics; runs and per-cell figures persist in the isolated
`community_aggregation` schema (migration 007).

Scheduling and runtime wiring live in `community-scheduler.ts` plus the
`COMMUNITY_AGGREGATION_ENABLED` flag in `config.ts` and the timer in `main.ts`
(see Scheduling below). The constructor shape (store + clock, the
Relay/Publications idiom) kept that wiring thin.

## Dedupe identity scheme

Dedupe key: original measured-attempt ID + dish identity
(`attemptId + '\0' + dishId`, lower-cased). Each physical cook keeps its
original attempt identity for life; derived artifacts resolve back to it before
bucketing:

- public fork: retains the source story's attempt ID (a fork is not a second
  measured cook) and the same dish identities;
- saved copy republished unchanged: pins the source revision, retains the
  attempt ID;
- recipe revision: owner edits mint a new revision but keep the attempt ID
  (recorded facts stay frozen); only the current revision is projected;
- repeated collection membership: the same dish pinned in several collections
  carries the same pair; membership is not a sample;
- genuine subsequent cook: "share my actual cook" mints a NEW attempt ID, so a
  second cook of the same dish is a second attempt.

Within a group the representative is the lexicographically smallest
(itemId, revisionId) and measured fields must agree; disagreement throws
`community_identity_conflict` instead of merging, because frozen facts cannot
diverge. Fixtures prove fork/copy/revision/re-collection collapse to n=1 and
genuine repeats count as n=2.

## Forbidden-input guarantees

Three independent layers, strongest available at each:

1. SQL: `community_aggregation.eligible_attempts()` is a SECURITY DEFINER
   function returning only allowlisted columns (identities, status-derived
   eligibility, bucketing descriptors, measured story). It selects NO
   instruction fields and references NO private store, and its WHERE clause
   mirrors public-read visibility (active status, author deletion/suspension,
   blocked assets, uncleared source-review flags).
2. Roles: `pittech_community_aggregation_runtime` can EXECUTE that function and
   read/write only the aggregation tables. Direct reads of publications tables,
   auth, relay, private sync, or moderation fail with 42501 (tested, mirroring
   the PR 14 private-sync boundary).
3. Code: the TypeScript projection validates exact input shapes; smuggled
   instruction or private-sync keys throw `forbidden_community_input`. The
   community modules import only `core.ts`, `pack.ts`, each other, `node:crypto`,
   and a type-only `pg` import (asserted by test). Story verdicts/ratings are
   tolerated for shape compatibility but never extracted, so no quality signal
   exists to rank by.

## Removal propagation

Full recompute, by design: unpublish, deletion, purge, or takedown excludes the
contribution from the eligible set (status/author/visibility predicates), and
the next job run over a new cutoff recomputes every cell. Per-cell incremental
refresh is a deferred optimization that must preserve byte-identical outputs.
Tests prove contribute → aggregate → remove → re-aggregate: a 10→9 removal
flips the cell to suppressed, the removed attempt ID is absent, and the refresh
is recorded as a second run with an advanced cutoff.

## Suppression and empty states

Cells with fewer than 10 distinct eligible attempts are suppressed: the figure
carries `status: 'insufficient_data'`, its eligible n, and the compute date,
with a null summary — never interpolated, never a partial figure. Boundary
fixtures prove n=9 hidden / n=10 visible for the same metric shape. Eligibility
is per metric: an attempt missing one metric's field still counts toward other
metrics. Temperatures normalize at the projection boundary to Celsius (Cook Pack
`°C`/`°F` units in, `valueCelsius` convention out), so °F- and °C-recorded
attempts bucket together; fixtures prove identical physical cooks in different
units yield byte-identical outputs.

## Taxonomy v1 (`community-taxonomy-v1` / `community-bands-v1`)

`api-service/src/community-taxonomy.ts` pins `COMMUNITY_TAXONOMY_V1`.
Groupings key on (food type, cut) pairs from the echoed vocabulary the codebase
actually records: foodType is the native dropdown (Beef, Pork, Poultry, plus
free-text beef/chicken/turkey variants); cut is free text echoed exactly (Whole
packer, Baby back, Boston butt, Brisket point, Shoulder, ...). Matching is
case-insensitive with punctuation normalized to spaces. Every named group
requires a non-blank matching cut; null or unrecognized cuts go to `other`.

| Group | (food, cut) rule | Why |
|---|---|---|
| `beef-brisket` | beef + brisket/packer/point/flat | Whole packer, point, and flat share the long low-and-slow cook; other beef cuts (steak, ribs) stay in `other`. |
| `pork-butt-shoulder` | pork + butt/shoulder/boston/picnic | Boston butt and shoulder are the same picnic/shoulder cook; the plan's hard rule keeps this separate from loin. |
| `pork-spare-ribs` | pork + spare/st louis | Spare/St. Louis racks share timing; baby backs are leaner and separate. |
| `pork-baby-back-ribs` | pork + baby back | Checked before loin because the alias "loin back ribs" contains "loin"; never falls through to the roast group. |
| `pork-loin` | pork + loin, excluding tenderloin/back-rib/baby | Lean roast with a different cook from butt; tenderloin and rib aliases go to `other`, never silently merged. |
| `chicken-whole` | poultry/chicken + whole (no turkey) | Whole birds share cavity/time behavior; pieces are separate. |
| `chicken-pieces` | poultry/chicken + thigh/breast/wing/drum/leg/quarter/pieces/parts | Parts cook faster and unevenly versus whole birds. |
| `turkey-whole` | turkey food, or poultry + turkey cut, without pieces hints | Whole turkeys group together; turkey breast/parts go to `other`. |
| `other` | everything else with non-blank food | Explicit bucket for unknown cuts, vegetables, seafood, generic meats, and null cuts. |

Blank food is unbucketable (`null`, counted in the run record, never dropped).
Bucket key: `group|band|method:<label>` with an optional fourth
`|equipment:<label>` segment when the projection carries equipment (absent in
v1, so production keys have three segments). Method is the normalized raw
recipe label (v1 `methods` meaning: no causation inferred); missing method
narrows to `method:unknown`, never widening to a shared bucket.

Weight bands are per cut family, defined in grams (1 lb = 453.59237 g):

| Family | small | medium | large |
|---|---|---|---|
| brisket | <4536 g (<10 lb) | 4536–7257 g (10–16 lb) | >7257 g (>16 lb) |
| pork butt | <2722 g (<6 lb) | 2722–4536 g (6–10 lb) | >4536 g (>10 lb) |
| ribs (both) | <1134 g (<2.5 lb) | 1134–1814 g (2.5–4 lb) | >1814 g (>4 lb) |
| pork loin | <1361 g (<3 lb) | 1361–2268 g (3–5 lb) | >2268 g (>5 lb) |
| chicken whole | <1361 g (<3 lb) | 1361–2268 g (3–5 lb) | >2268 g (>5 lb) |
| chicken pieces | <907 g (<2 lb) | 907–1814 g (2–4 lb) | >1814 g (>4 lb) |
| turkey | <5443 g (<12 lb) | 5443–8165 g (12–18 lb) | >8165 g (>18 lb) |
| other | <2000 g (<4.41 lb) | 2000–6000 g (4.41–13.23 lb) | >6000 g (>13.23 lb) |

Null weight maps to `unknown`. Golden fixtures prove brisket 12 lb vs 14 lb
share `medium`, pork loin vs pork butt stay separate, and °F/°C attempts bucket
together.

## Metric summarizers (`community-metrics-v1`)

`COMMUNITY_METRICS_V1` in `community-aggregation.ts` aggregates the v1
calculation contract's meanings (`calculationVersion` 1): `heatingMillis` per
the v1 heating-interval rules, temperatures as `valueCelsius`, manual gaps
never interpolated, excluded readings counted at publication. Community figures
are NEW aggregate summaries, not redefinitions of per-cook charts. All
summarizers are pure and deterministic (sorted inputs, linear-interpolation
R-7 quantiles), so repeat jobs stay byte-identical. Per-metric eligibility
excludes missing fields from that metric only.

- `median-heating-time-v1` (`requires: heatingMillis`, eligible when
  non-null): median of v1 `heatingMillis` plus IQR (p25/p75), min, and max.
- `pit-food-curve-percentiles-v1` (`requires: measurements`): per-15-minute
  elapsed-since-foodOn percentiles (p10/p50/p90) for pit and food channels.
  Resampling: `CURVE_INTERVAL_MILLIS` 900000, `CURVE_MIN_READINGS_PER_INTERVAL`
  2 per channel, `CURVE_MAX_GAP_MILLIS` 1800000 within the interval,
  `CURVE_MAX_ELAPSED_MILLIS` 86400000 (96 intervals max), prep (negative
  elapsed) excluded, any recorded gap overlapping the interval drops it for
  that attempt, per-attempt interval value is the median of its readings.
  Each interval carries its own pit/food n and coverage (`n / figureN`);
  bands with n < 10 carry null percentiles (suppressed per interval) while
  still reporting n. Summaries are labeled `percentile-bands`, never presented
  as recorded individual measurements.
- `stall-summary-v1` (`requires: measurements`): measured stall windows on the
  pooled food channel. Thresholds: `STALL_MIN_DURATION_MILLIS` 7200000 (2 h),
  `STALL_MAX_DRIFT_CELSIUS` 3, `STALL_MIN_READINGS_PER_HOUR` 4 (minimum 8 per
  2 h window), `STALL_MAX_GAP_MILLIS` 1800000, `STALL_WINDOW_STEP_MILLIS`
  900000, `STALL_MAX_ELAPSED_MILLIS` 86400000. Windows with overlapping gaps
  are skipped; the first qualifying 2 h window extends in 15-minute steps while
  density/continuity/drift hold. Attempts with no dense continuous 2 h window
  are not assessable and excluded from n — sparse manual spot checks cannot
  establish a continuous stall distribution. The summary reports n, nStalled,
  prevalence, and median/p25/p75 over the stalled subset for start, duration,
  and temperature, with the honest note that sparse attempts are excluded,
  never interpolated.
- `wrap-spritz-prevalence-v1` (`requires: spritzed`, always eligible):
  prevalence of recorded wrap (first `wrap` milestone per the v1 `wrapAt`
  meaning), spritz/mop/spray milestones (exact normalized-action match against
  `COMMUNITY_SPRITZ_ACTIONS_V1`), and normalized recipe method labels.
  Prevalence only — no causation is inferred.

The projection (`community-projection.ts`) carries the v1 inputs
(`foodOnOffsetMillis`, `wrapAtOffsetMillis`, `spritzed`, sorted measurements
and deduped gaps) as optional boundary fields so pre-v1 fixtures still
validate; production rows from `extractRawContributions` always carry them via
`storyV1Fields`. Dedupe conflict checks extend to the new frozen measured
inputs. Verdicts/ratings are still never extracted.

## Scheduling

`COMMUNITY_AGGREGATION_ENABLED` (default off) gates the job. When off, no
service or timer exists and the job never runs (fail closed). When on,
`config.ts` builds `CommunityAggregates` over `PostgresCommunityStore` (main
pool, allowlisted projection only; production grants the
`pittech_community_aggregation_runtime` role) and `main.ts` starts
`startCommunityScheduler`, which runs once immediately and then every
`COMMUNITY_AGGREGATION_INTERVAL_MILLIS` (86400000, daily). Daily fits the
UTC-date figures (same-day repeats replay instead of minting runs), community
data changes slowly, and Pro launches on personal value even when every cell is
below threshold. The scheduler reuses the sweep overlap guard; failures log
`community_aggregation_deferred` and retry on the next tick.

## Aggregate read API (Pro-authorized)

`GET /v1/community/aggregates?bucket=&metric=&runId=` returns the recorded run
plus its figures filtered by bucket/metric (latest run when `runId` is absent;
`{ run: null, figures: [] }` when no runs exist). `GET
/v1/community/job-runs?limit=&cursor=` paginates run records (limit 1–100,
default 20; cursor is a run ID). Figures are served exactly as recorded,
including `insufficient_data` cells with n and compute date; the API never
invents a figure. Bucket allowlist enforces `group|band|method:*` shape with
known groups/bands; metric allowlist is the four v1 IDs (stubs never served).

Authorization is verified Pro only: a Bearer account token resolves via the
auth layer, then the slice-16 subscriptions cache is checked in production and
sandbox for a verified `entitlement-cache-v1` record in active/grace/retry/
paused (or cancelled-but-paid-through). Signed-out callers get 401
`authentication_required`; authenticated non-Pro/unknown callers get 403
`pro_required` with no figures. Routes are absent (404) unless both
subscriptions and community aggregation are enabled.

## Native benchmark cards (both clients)

The native lane consumes the read API above with the same meanings, counts,
and fixtures on both clients. Cards live behind the existing slice-16
entitlement/paywall machinery — no second gate, no second purchase offer.

### Placement

One Pro entry, "Community benchmarks", inside advanced Insights next to the
slice-16 entries:

| Surface | iOS | Android |
|---|---|---|
| Insights entry row | `ContentView.swift` DEEPER ANALYSIS + `ProEntryRow(entry: .benchmarks)` | `ProPaywall.kt` `ProInsightsEntries` + `ProPlacement.BENCHMARKS` row |
| Gate host | `ProPaywallHost`: `.pro` + `.benchmarks` renders `CommunityBenchmarksView`; every other entry keeps its slice-18 placeholder | `ProGateDialog`: `PRO` + `BENCHMARKS` renders `CommunityBenchmarksDialog`; every other entry keeps its placeholder |
| Loader lifetime | `CommunityBenchmarksLoader` in the app environment, reset on view disappear | `CommunityBenchmarksLoader` on `CooksViewModel`, reset on dispose |

Entry preview: "Typical heating times and temperature bands from eligible
published cooks." Paywall bullets: typical heating times with sample counts,
temperature percentile bands from many cooks, wrap and spritz shares
(prevalence only).

### Per-metric rendering

One card per (bucket, metric) figure, in recorded order. Titles: "Typical
heating time", "Temperature percentile bands", "Stall summary", "Wrap and
spritz prevalence". Every card shows its bucket line ("Pork butt / shoulder ·
Medium · Method: smoke"), the figure exactly as recorded, and the coverage
line ("10 eligible cooks · Computed 2026-10-18") with the UTC DATE displayed
verbatim (never parsed into a timezone-shifted date).

- Heating: median, middle half (p25–p75), recorded range (min–max).
- Curves: one block per recorded interval ("+0:00–0:15") with per-band
  p10/p50/p90 plus that band's own n and coverage ("n=10, 100% of cooks").
  Suppressed bands (n < 10, null percentiles) render their n and coverage
  with the 10-cook threshold ("Food band hidden: 3 of 10 cooks in this
  interval (30%). Bands need 10 cooks."). Every curve card carries "Percentile
  bands summarize many cooks. They are not one cook's readings." — bands are
  never presented as recorded individual measurements.
- Stall: n, nStalled, prevalence, median start/length/temperature, plus the
  recorded note that sparse attempts are excluded from n, never interpolated.
  An empty stalled subset shows "No stalled cooks in this group.", never
  zeroed medians.
- Wrap/spritz: wrap share, spritz-or-mop share, per-method shares, plus the
  "Prevalence only" note. Prevalence only — no causation language anywhere.

Group labels keep pork loin ("Pork loin") and pork butt ("Pork butt /
shoulder") separate; all nine taxonomy groups have distinct labels (pinned by
test). Shared formatting rules: millis to "5h 30m"/"1h"/"45m", elapsed to
"+0:15", shares to whole percent, temperatures as recorded °C.

### State matrix

| State | UI (both clients) |
|---|---|
| Entitled + visible figure | Card with figure, n, compute date |
| Entitled + `insufficient_data` | Honest card: "Not enough cooks yet", eligible n ("9 of 10 eligible cooks recorded"), the 10-distinct-cook threshold, compute date; never a partial figure. A normal supported state — it never blocks the personal (slice 18) entry points |
| Entitled + `run: null` | Honest empty state, no invented figures |
| Signed-out (401) | Contextual sign-in + paywall preview at the entry point (slice-16 pattern); the loader performs no network call |
| Authenticated non-Pro (403 `pro_required`) | Paywall with preview (slice-16 pattern), no figures, no second-subscription offer when covered |
| Unknown/loading entitlement | Neutral loading state; never a paywall flash and never a content flash (the slice-16 matrix, unchanged) |
| Offline / network error / 404 (feature disabled) | Graceful unavailable state ("Community benchmarks are unavailable right now. Your cooks, exports, and free tools are unchanged.") with Try again; an active cook is never interrupted |
| Stale entitled cache rejected by the server (401/403) | One silent entitlement refresh, then the same contextual paywall — with no figures decoded or rendered |

### Plumbing and strictness

- Typed clients (`CommunityBenchmarksClient` on both platforms) follow the
  subscription/account client idioms: Bearer [REDACTED] session from the existing
  vault/coordinator, no-store, bounded responses (256 KiB for full latest runs),
  no cookies or redirects. Bucket/metric/runId and job-runs limit/cursor
  validate client-side before any request; only the two merged routes are
  called (cards read the latest run; job-runs pagination is client-supported
  for later run selection).
- Strict decoding fails closed: unknown metric IDs, misshapen figures, bad
  dates, and status/summary mismatches map the whole response to the
  unavailable state — never a crash, never a render.
- Summaries the client cannot represent (for example a curve whose `kind` is
  not `percentile-bands`, or an undecodable interval/band) are omitted with
  their coverage note (n + compute date) intact — never rendered as zero, and
  a figure is never partially rendered.
- Figures live only while the benchmarks view is shown (reset on
  disappear/dispose). No persisted figure cache exists, so a removed or
  expired entitlement cannot keep serving figures; the gate owns access.
- Hard prohibitions, asserted in tests: no author ranking, no claim that
  community norms establish safe doneness, no causation language for
  wrap/spritz/method prevalence.

### Tests

Fixture-driven suites on both clients consume the same
`contracts/fixtures/community-benchmarks-v1.json` payloads (byte-identical
copies in `app/src/main/assets/`, `app/src/test/resources/`, and the iOS test
bundle): n=10 visible figures, the n=9 `insufficient_data` card, the
`run: null` empty state, curve per-interval suppression, the stall
sparse-exclusion note, and wrap/spritz prevalence. State tests cover
signed-out, non-Pro, entitled, unknown/loading, offline/error, and the stale
403 → paywall convergence. Wording-guard tests forbid ranking, safe-doneness,
and causation language and require the percentile-band label.

- iOS: `CommunityBenchmarksTests` (21 unit tests) +
  `CommunityBenchmarksUITests` (9 UI tests at both text sizes).
- Android: JVM `CommunityBenchmarksTest` (20) + `CommunityBenchmarksClientTest`
  (2), plus `CommunityBenchmarksTest` (9 stub-driven UI tests) and
  `CommunityBenchmarksFlowTest` (3 real-navigation tests); the companion
  selector in `scripts/run-emulator-ui-tests.sh` moves from `OK (45 tests)`
  to `OK (57 tests)`.

## Honest remains

- Store/sandbox purchase evidence and installed-link checks (owner-gated).
- Cutoff sequences (cutoffs remain timestamps).
- Per-cell incremental refresh remains a deferred optimization that must
  preserve byte-identical outputs.
- No Actions evidence yet for the native lane: the clients and their tests
  were written by construction against the inspected native patterns (no SDK
  or Xcode in this environment); `ios-build.yml` (unit + UI at both text
  sizes) and `android-build.yml` (JVM tests plus the API 36 font 1.15/1.5
  emulator lanes with the updated companion count) are the proof gates.

The backend lane's proof gate remains `online-services.yml` (typecheck, unit +
real-Postgres gates, container checks).
