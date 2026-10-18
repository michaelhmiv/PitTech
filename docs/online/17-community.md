# PR 17 — Community aggregate pipeline, backend-prep lane (in progress)

This is the backend-prep half of slice 17, parallel-safe with slice 15. It
builds the aggregation job scaffolding, deduplication, forbidden-input guards,
removal propagation, and the n=9/n=10 suppression-boundary mechanics on
isolated backend files. It pins no metric meanings: the food/cut taxonomy,
weight bands, metric definitions, curve percentiles, and stall logic wait for
slice 15's versioned calculation contract, which plugs into the interfaces and
version hooks defined here.

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

Scheduling and runtime wiring (env flag, pool, periodic trigger) are
deliberately absent: community entry stays disabled until the whole
accounts/social/sync/deletion phase passes, and `main.ts`/`sweep.ts`/`config.ts`
are untouched so this lane stays parallel-safe with slice 15. The constructor
shape (store + clock, the Relay/Publications idiom) keeps that wiring thin.

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

## Explicitly deferred to slice 15 (and later)

- Food/cut taxonomy groupings and weight-band boundaries (interfaces +
  `taxonomyVersion`/`bandVersion` hooks ready; tests use
  `STUB_TAXONOMY_NON_AUTHORITATIVE`, which groups by raw food-type text only).
- Metric definitions and summarizers: median heating time/range, pit/food curve
  percentiles, stall summaries, wrap/spritz prevalence (the `MetricSpec`
  hook is ready; stubs return `{ pending: 'slice-15-calculation-contract' }`
  and count presence only).
- Stall detection/resampling thresholds and curve/coverage rules.
- Native benchmark cards and their empty states (later slice).
- Job scheduling/runtime wiring and any Pro-gated aggregate API.
- Cutoff sequences (cutoffs are timestamps; no sequence exists yet).

No Actions evidence yet: the modules and all ten tests below were written by
construction against the inspected backend patterns; `online-services.yml`
(typecheck, unit + real-Postgres gates, container checks) is the proof gate.
