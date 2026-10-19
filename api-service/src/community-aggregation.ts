import { randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import { ApiError, digest } from './core.ts';
import { stable } from './pack.ts';
import type { CommunityTaxonomy } from './community-taxonomy.ts';
import {
  extractRawContributions, projectEligibleAttempts,
  type EligibleAttempt, type RawContribution,
} from './community-projection.ts';

// Slice 17 backend-prep lane: idempotent community aggregation job scaffolding.
//
// The job aggregates ONLY deliberately active public measured attempts through
// the eligible-attempt projection (community-projection.ts). It never reads raw
// tables: the single SQL path is ELIGIBLE_ATTEMPTS_SQL below, which selects
// explicit allowlisted columns from community_aggregation.eligible_attempts().
// That projection selects no instruction fields and references no private
// store; the dedicated runtime role cannot read those sources at all (see the
// operations grant file and the Postgres isolation test).
//
// Idempotency and recomputability: build() is a pure function of (input
// snapshot, cutoff, taxonomy, metric specs, clock day). Recording is keyed by
// (cutoff, algorithm/taxonomy/band versions, metric-set hash, input hash), so
// re-running an identical job replays the stored bytes instead of minting a
// duplicate run. Figures carry a UTC compute DATE (not a timestamp) so repeats
// within a day are byte-identical; the run record keeps the exact compute time.
//
// Refresh granularity is FULL recompute: removal/takedown/unpublish of a
// contribution is honored by re-running the job over the current eligible set
// with a new cutoff. Per-cell incremental refresh is a future optimization that
// must preserve byte-identical outputs; it is not implemented here.
//
// Scheduling/runtime wiring (env flag, pool, periodic trigger) intentionally
// stays out of this lane: community entry remains disabled until the whole
// accounts/social/sync/deletion phase passes, and main.ts/sweep.ts/config.ts
// are untouched so this work stays parallel-safe with slice 15. The
// constructor shape (store + clock, Relay/Publications idiom) keeps that
// wiring a thin addition.

export const COMMUNITY_ALGORITHM_VERSION = 'community-aggregation-framework-v0';
export const COMMUNITY_MIN_N = 10;
export const COMMUNITY_METRICS_VERSION = 'community-metrics-v1';

// Curve resampling thresholds (v1). Intervals align to foodOn; elapsed times
// before foodOn (prep) are excluded. Manual gaps are never interpolated: an
// interval needs its own readings, and any recorded gap overlapping the
// interval drops it for that attempt.
export const CURVE_INTERVAL_MILLIS = 900000;
export const CURVE_MIN_READINGS_PER_INTERVAL = 2;
export const CURVE_MAX_ELAPSED_MILLIS = 86400000;
export const CURVE_MIN_N_PER_INTERVAL = 10;
export const CURVE_MAX_GAP_MILLIS = 1800000;

// Stall-detection thresholds (v1). Food-channel only, pooled across probes.
// Sparse manual spot checks cannot meet the density/continuity gates, so those
// attempts are not assessable and never enter the stall n.
export const STALL_MIN_DURATION_MILLIS = 7200000;
export const STALL_MAX_DRIFT_CELSIUS = 3;
export const STALL_MIN_READINGS_PER_HOUR = 4;
export const STALL_MAX_GAP_MILLIS = 1800000;
export const STALL_WINDOW_STEP_MILLIS = 900000;
export const STALL_MAX_ELAPSED_MILLIS = 86400000;

// A metric is an eligibility rule plus a summarizer hook. The stubs below pin
// no meanings; COMMUNITY_METRICS_V1 aggregates the v1 calculation contract's
// meanings (heatingMillis per the v1 heating-interval rules, valueCelsius,
// manual gaps never interpolated). Community figures are NEW aggregate
// summaries, never redefinitions of per-cook charts.
export type CommunityMetricField = 'heatingMillis' | 'finalTempCelsius' | 'measurements' | 'spritzed';
export type MetricSpec = {
  readonly metricId: string;
  readonly metricVersion: string;
  readonly requires: CommunityMetricField;
  eligible(attempt: EligibleAttempt): boolean;
  summarize(eligible: readonly EligibleAttempt[]): unknown;
};

/** Non-authoritative test stubs. Eligibility counts are real (they exercise
 * suppression and per-metric exclusion); the summaries pin no statistics and
 * must never be presented as community analysis. */
export const STUB_METRICS_NON_AUTHORITATIVE: readonly MetricSpec[] = [
  {
    metricId: 'stub-heating-presence', metricVersion: 'stub-non-authoritative-v0', requires: 'heatingMillis',
    eligible: (attempt) => attempt.heatingMillis !== null,
    summarize: () => ({ pending: 'slice-15-calculation-contract' }),
  },
  {
    metricId: 'stub-temp-presence', metricVersion: 'stub-non-authoritative-v0', requires: 'finalTempCelsius',
    eligible: (attempt) => attempt.finalTempCelsius !== null,
    summarize: () => ({ pending: 'slice-15-calculation-contract' }),
  },
];

// Linear-interpolation quantiles over ascending values (R-7). Deterministic
// and pure; the same inputs always yield the same bytes.
function quantile(sorted: readonly number[], p: number): number {
  if (sorted.length === 1) return sorted[0];
  const rank = p * (sorted.length - 1);
  const lo = Math.floor(rank), hi = Math.ceil(rank);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
}

type CurvePoint = { readonly elapsed: number; readonly value: number };
function median(values: readonly number[]): number {
  return quantile([...values].sort((a, b) => a - b), 0.5);
}

// Per-attempt resampled curve: one median value per 15-minute elapsed interval
// per channel, or null when the interval lacks continuity. Gaps overlapping the
// interval drop it; prep (negative elapsed) is excluded.
function resampleAttempt(attempt: EligibleAttempt): Map<number, { pit: number | null; food: number | null }> {
  const out = new Map<number, { pit: number | null; food: number | null }>();
  const foodOn = attempt.foodOnOffsetMillis;
  if (foodOn === null) return out;
  const byInterval = new Map<number, { pit: CurvePoint[]; food: CurvePoint[] }>();
  for (const m of attempt.measurements) {
    const elapsed = m.offsetMillis - foodOn;
    if (elapsed < 0 || elapsed > CURVE_MAX_ELAPSED_MILLIS) continue;
    const index = Math.floor(elapsed / CURVE_INTERVAL_MILLIS);
    const cell = byInterval.get(index) ?? { pit: [], food: [] };
    (m.role === 'pit' ? cell.pit : cell.food).push({ elapsed, value: m.valueCelsius });
    byInterval.set(index, cell);
  }
  const gapOverlaps = (index: number): boolean => {
    const start = foodOn + index * CURVE_INTERVAL_MILLIS;
    const end = start + CURVE_INTERVAL_MILLIS;
    return attempt.gaps.some((g) => g.fromMillis < end && g.toMillis >= start);
  };
  for (const [index, cell] of [...byInterval.entries()].sort(([a], [b]) => a - b)) {
    if (gapOverlaps(index)) continue;
    const summarizeChannel = (points: CurvePoint[]): number | null => {
      if (points.length < CURVE_MIN_READINGS_PER_INTERVAL) return null;
      const ordered = [...points].sort((a, b) => a.elapsed - b.elapsed);
      for (let i = 1; i < ordered.length; i++) {
        if (ordered[i].elapsed - ordered[i - 1].elapsed > CURVE_MAX_GAP_MILLIS) return null;
      }
      return median(ordered.map((p) => p.value));
    };
    const pit = summarizeChannel(cell.pit);
    const food = summarizeChannel(cell.food);
    if (pit !== null || food !== null) out.set(index, { pit, food });
  }
  return out;
}

function curveEligible(attempt: EligibleAttempt): boolean {
  return resampleAttempt(attempt).size > 0;
}

type StallAssessment =
  | { readonly assessable: false }
  | { readonly assessable: true; readonly stalled: false }
  | {
    readonly assessable: true; readonly stalled: true;
    readonly startElapsedMillis: number; readonly durationMillis: number; readonly tempCelsius: number;
  };

function assessStall(attempt: EligibleAttempt): StallAssessment {
  const foodOn = attempt.foodOnOffsetMillis;
  if (foodOn === null) return { assessable: false };
  const food = attempt.measurements
    .filter((m) => m.role === 'food')
    .map((m) => ({ elapsed: m.offsetMillis - foodOn, value: m.valueCelsius }))
    .filter((p) => p.elapsed >= 0 && p.elapsed <= STALL_MAX_ELAPSED_MILLIS)
    .sort((a, b) => a.elapsed - b.elapsed);
  if (food.length < 8) return { assessable: false };
  const gapHits = (start: number, end: number): boolean =>
    attempt.gaps.some((g) => g.fromMillis - foodOn < end && g.toMillis - foodOn >= start);
  const windowStats = (start: number, end: number): { count: number; maxGap: number; drift: number } | null => {
    const points = food.filter((p) => p.elapsed >= start && p.elapsed < end);
    if (!points.length) return null;
    let maxGap = 0;
    let min = points[0].value, max = points[0].value;
    for (let i = 1; i < points.length; i++) {
      maxGap = Math.max(maxGap, points[i].elapsed - points[i - 1].elapsed);
      if (points[i].value < min) min = points[i].value;
      if (points[i].value > max) max = points[i].value;
    }
    return { count: points.length, maxGap, drift: max - min };
  };
  const denseEnough = (count: number, duration: number): boolean =>
    count >= Math.max(8, Math.ceil((duration / 3600000) * STALL_MIN_READINGS_PER_HOUR));
  let assessable = false;
  let stall: StallAssessment | null = null;
  for (let start = 0; start + STALL_MIN_DURATION_MILLIS <= STALL_MAX_ELAPSED_MILLIS; start += STALL_WINDOW_STEP_MILLIS) {
    const end = start + STALL_MIN_DURATION_MILLIS;
    if (gapHits(start, end)) continue;
    const stats = windowStats(start, end);
    if (!stats || !denseEnough(stats.count, STALL_MIN_DURATION_MILLIS) || stats.maxGap > STALL_MAX_GAP_MILLIS) continue;
    assessable = true;
    if (stats.drift <= STALL_MAX_DRIFT_CELSIUS && stall === null) {
      let extended = end;
      while (extended + STALL_WINDOW_STEP_MILLIS <= STALL_MAX_ELAPSED_MILLIS) {
        const next = extended + STALL_WINDOW_STEP_MILLIS;
        if (gapHits(start, next)) break;
        const grown = windowStats(start, next);
        if (!grown || !denseEnough(grown.count, next - start) || grown.maxGap > STALL_MAX_GAP_MILLIS) break;
        if (grown.drift > STALL_MAX_DRIFT_CELSIUS) break;
        extended = next;
      }
      const points = food.filter((p) => p.elapsed >= start && p.elapsed < extended).map((p) => p.value);
      stall = {
        assessable: true, stalled: true,
        startElapsedMillis: start, durationMillis: extended - start, tempCelsius: median(points),
      };
    }
  }
  if (stall !== null) return stall;
  return assessable ? { assessable: true, stalled: false } : { assessable: false };
}

/** Production metric set v1. Every summarizer is pure and deterministic over
 * its eligible attempts so repeat-job byte-equality holds. */
export const COMMUNITY_METRICS_V1: readonly MetricSpec[] = [
  {
    metricId: 'median-heating-time-v1', metricVersion: COMMUNITY_METRICS_VERSION, requires: 'heatingMillis',
    eligible: (attempt) => attempt.heatingMillis !== null,
    summarize: (eligible) => {
      const values = eligible.map((a) => a.heatingMillis as number).sort((a, b) => a - b);
      const p25 = quantile(values, 0.25), p50 = quantile(values, 0.5), p75 = quantile(values, 0.75);
      return {
        n: values.length, medianHeatingMillis: p50, p25HeatingMillis: p25, p75HeatingMillis: p75,
        iqrMillis: p75 - p25, minHeatingMillis: values[0], maxHeatingMillis: values[values.length - 1],
      };
    },
  },
  {
    metricId: 'pit-food-curve-percentiles-v1', metricVersion: COMMUNITY_METRICS_VERSION, requires: 'measurements',
    eligible: curveEligible,
    summarize: (eligible) => {
      const n = eligible.length;
      const perInterval = new Map<number, { pit: number[]; food: number[] }>();
      for (const attempt of eligible) {
        for (const [index, values] of resampleAttempt(attempt)) {
          const cell = perInterval.get(index) ?? { pit: [], food: [] };
          if (values.pit !== null) cell.pit.push(values.pit);
          if (values.food !== null) cell.food.push(values.food);
          perInterval.set(index, cell);
        }
      }
      const intervals = [...perInterval.entries()]
        .sort(([a], [b]) => a - b)
        .map(([index, cell]) => {
          const pitSorted = cell.pit.sort((a, b) => a - b);
          const foodSorted = cell.food.sort((a, b) => a - b);
          const band = (sorted: number[]): { n: number; coverage: number; p10: number | null; p50: number | null; p90: number | null } =>
            sorted.length >= CURVE_MIN_N_PER_INTERVAL
              ? { n: sorted.length, coverage: sorted.length / n, p10: quantile(sorted, 0.1), p50: quantile(sorted, 0.5), p90: quantile(sorted, 0.9) }
              : { n: sorted.length, coverage: sorted.length / n, p10: null, p50: null, p90: null };
          return {
            startElapsedMillis: index * CURVE_INTERVAL_MILLIS,
            endElapsedMillis: index * CURVE_INTERVAL_MILLIS + CURVE_INTERVAL_MILLIS,
            pit: band(pitSorted),
            food: band(foodSorted),
          };
        });
      return { n, intervalMillis: CURVE_INTERVAL_MILLIS, kind: 'percentile-bands', intervals };
    },
  },
  {
    metricId: 'stall-summary-v1', metricVersion: COMMUNITY_METRICS_VERSION, requires: 'measurements',
    eligible: (attempt) => assessStall(attempt).assessable,
    summarize: (eligible) => {
      const stalled = eligible
        .map((attempt) => assessStall(attempt))
        .filter((r): r is Extract<StallAssessment, { stalled: true }> => r.assessable && r.stalled);
      const starts = stalled.map((s) => s.startElapsedMillis).sort((a, b) => a - b);
      const durations = stalled.map((s) => s.durationMillis).sort((a, b) => a - b);
      const temps = stalled.map((s) => s.tempCelsius).sort((a, b) => a - b);
      const band = (sorted: number[]): { p25: number | null; median: number | null; p75: number | null } =>
        sorted.length ? { p25: quantile(sorted, 0.25), median: quantile(sorted, 0.5), p75: quantile(sorted, 0.75) } : { p25: null, median: null, p75: null };
      return {
        n: eligible.length, nStalled: stalled.length, prevalence: eligible.length ? stalled.length / eligible.length : 0,
        startElapsedMillis: band(starts), durationMillis: band(durations), tempCelsius: band(temps),
        note: 'Measured stall windows only; sparse attempts are excluded from n, never interpolated.',
      };
    },
  },
  {
    // Prevalence only: no causation is inferred (v1 methods meaning).
    metricId: 'wrap-spritz-prevalence-v1', metricVersion: COMMUNITY_METRICS_VERSION, requires: 'spritzed',
    eligible: () => true,
    summarize: (eligible) => {
      const n = eligible.length;
      const nWrapped = eligible.filter((a) => a.wrapAtOffsetMillis !== null).length;
      const nSpritzed = eligible.filter((a) => a.spritzed).length;
      const counts = new Map<string, number>();
      for (const attempt of eligible) {
        if (attempt.method === null) continue;
        const label = attempt.method.trim().toLowerCase().replace(/\s+/g, ' ');
        if (!label) continue;
        counts.set(label, (counts.get(label) ?? 0) + 1);
      }
      const methods = [...counts.entries()]
        .sort(([a], [b]) => (a < b ? -1 : 1))
        .map(([label, count]) => ({ label, n: count, prevalence: count / n }));
      const nMethodRecorded = [...counts.values()].reduce((a, b) => a + b, 0);
      return {
        n, nWrapped, wrapPrevalence: n ? nWrapped / n : 0, nSpritzed, spritzPrevalence: n ? nSpritzed / n : 0,
        methods, nMethodRecorded, nMethodUnrecorded: n - nMethodRecorded,
      };
    },
  },
];

export type CommunityFigure = {
  readonly bucket: string;
  readonly metricId: string;
  readonly metricVersion: string;
  readonly n: number;
  readonly status: 'visible' | 'insufficient_data';
  readonly summary: unknown;
  readonly computedDate: string;
  readonly cutoffMillis: number;
  readonly algorithmVersion: string;
  readonly taxonomyVersion: string;
  readonly bandVersion: string;
};

export type JobRunRecord = {
  readonly runId: string;
  readonly cutoffMillis: number;
  readonly computedAtMillis: number;
  readonly algorithmVersion: string;
  readonly taxonomyVersion: string;
  readonly bandVersion: string;
  readonly metricVersions: readonly { readonly metricId: string; readonly metricVersion: string }[];
  readonly inputCount: number;
  readonly eligibleCount: number;
  readonly unbucketedCount: number;
  readonly cellCount: number;
  readonly visibleCount: number;
  readonly outputSha256: string;
};

export type AggregationOptions = {
  readonly cutoffMillis: number;
  readonly taxonomy: CommunityTaxonomy;
  readonly metrics: readonly MetricSpec[];
};

export type BuiltAggregation = {
  readonly attempts: readonly EligibleAttempt[];
  readonly figures: readonly CommunityFigure[];
  readonly bytes: string;
  readonly outputSha256: string;
  readonly inputSha256: string;
  readonly metricSetHash: string;
  readonly entry: JobRunEntry;
};

export type JobRunEntry = {
  readonly cutoffMillis: number;
  readonly computedAtMillis: number;
  readonly algorithmVersion: string;
  readonly taxonomyVersion: string;
  readonly bandVersion: string;
  readonly metricVersions: readonly { readonly metricId: string; readonly metricVersion: string }[];
  readonly metricSetHash: string;
  readonly inputSha256: string;
  readonly inputCount: number;
  readonly eligibleCount: number;
  readonly unbucketedCount: number;
  readonly cellCount: number;
  readonly visibleCount: number;
  readonly outputSha256: string;
  readonly figures: readonly CommunityFigure[];
  readonly bytes: string;
};

export type JobRunStore = {
  record(entry: JobRunEntry): Promise<{ run: JobRunRecord; replayed: boolean }>;
  list(): Promise<JobRunRecord[]>;
  // Read API: figures exactly as the job recorded them, never fabricated. The
  // memory store keeps figures alongside runs for tests; Postgres reads the
  // figures table. Unknown run IDs return undefined.
  figures(runId: string): Promise<readonly CommunityFigure[] | undefined>;
};

const invalid = () => new ApiError(422, 'invalid_community_job');

// Explicit allowlisted columns from the projection function. Never SELECT *:
// new columns must be consciously adopted into the extractor, never silently
// ingested. The function itself is the choke point; this string must reference
// nothing else.
export const ELIGIBLE_ATTEMPTS_SQL =
  'SELECT item_id, revision_id, collected_at, dish_id, attempt_id, food_type, cut, weight_grams, method, story ' +
  'FROM community_aggregation.eligible_attempts()';

export async function loadRawContributions(pool: Pool): Promise<RawContribution[]> {
  return extractRawContributions((await pool.query(ELIGIBLE_ATTEMPTS_SQL)).rows);
}

export class MemoryJobRunStore implements JobRunStore {
  private runs: { key: string; run: JobRunRecord; figures: readonly CommunityFigure[] }[] = [];
  async record(entry: JobRunEntry): Promise<{ run: JobRunRecord; replayed: boolean }> {
    const key = stable([entry.cutoffMillis, entry.algorithmVersion, entry.taxonomyVersion,
      entry.bandVersion, entry.metricSetHash, entry.inputSha256]);
    const prior = this.runs.find((row) => row.key === key);
    if (prior) return { run: prior.run, replayed: true };
    const run: JobRunRecord = {
      runId: randomUUID(), cutoffMillis: entry.cutoffMillis, computedAtMillis: entry.computedAtMillis,
      algorithmVersion: entry.algorithmVersion, taxonomyVersion: entry.taxonomyVersion, bandVersion: entry.bandVersion,
      metricVersions: entry.metricVersions, inputCount: entry.inputCount, eligibleCount: entry.eligibleCount,
      unbucketedCount: entry.unbucketedCount, cellCount: entry.cellCount, visibleCount: entry.visibleCount,
      outputSha256: entry.outputSha256,
    };
    this.runs.push({ key, run, figures: structuredClone(entry.figures) });
    return { run, replayed: false };
  }
  async list(): Promise<JobRunRecord[]> {
    return [...this.runs]
      .sort((a, b) => a.run.computedAtMillis - b.run.computedAtMillis || (a.run.runId < b.run.runId ? -1 : 1))
      .map((row) => row.run);
  }
  async figures(runId: string): Promise<readonly CommunityFigure[] | undefined> {
    const found = this.runs.find((row) => row.run.runId === runId);
    return found ? structuredClone(found.figures) : undefined;
  }
}

export class PostgresCommunityStore implements JobRunStore {
  private pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async record(entry: JobRunEntry): Promise<{ run: JobRunRecord; replayed: boolean }> {
    const runId = randomUUID();
    const inserted = await this.pool.query(
      `INSERT INTO community_aggregation.job_runs(run_id, cutoff_millis, computed_at, algorithm_version, taxonomy_version, band_version,
        metric_set_hash, input_sha256, input_count, eligible_count, unbucketed_count, cell_count, visible_count, output_sha256, figures)
       VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15::jsonb)
       ON CONFLICT(cutoff_millis, algorithm_version, taxonomy_version, band_version, metric_set_hash, input_sha256) DO NOTHING
       RETURNING run_id`,
      [runId, entry.cutoffMillis, entry.computedAtMillis, entry.algorithmVersion, entry.taxonomyVersion, entry.bandVersion,
        entry.metricSetHash, entry.inputSha256, entry.inputCount, entry.eligibleCount, entry.unbucketedCount,
        entry.cellCount, entry.visibleCount, entry.outputSha256, entry.bytes]);
    if (!inserted.rowCount) {
      const prior = await this.pool.query(
        `SELECT run_id, cutoff_millis, computed_at, algorithm_version, taxonomy_version, band_version,
          metric_set_hash, input_sha256, input_count, eligible_count, unbucketed_count, cell_count, visible_count, output_sha256
         FROM community_aggregation.job_runs
         WHERE cutoff_millis=$1 AND algorithm_version=$2 AND taxonomy_version=$3 AND band_version=$4 AND metric_set_hash=$5 AND input_sha256=$6`,
        [entry.cutoffMillis, entry.algorithmVersion, entry.taxonomyVersion, entry.bandVersion, entry.metricSetHash, entry.inputSha256]);
      return { run: this.row(prior.rows[0], entry.metricVersions), replayed: true };
    }
    for (const figure of entry.figures) {
      await this.pool.query(
        'INSERT INTO community_aggregation.figures(run_id, bucket, metric_id, n, status, figure) VALUES($1,$2,$3,$4,$5,$6::jsonb)',
        [runId, figure.bucket, figure.metricId, figure.n, figure.status, stable(figure)]);
    }
    return {
      run: {
        runId, cutoffMillis: entry.cutoffMillis, computedAtMillis: entry.computedAtMillis,
        algorithmVersion: entry.algorithmVersion, taxonomyVersion: entry.taxonomyVersion, bandVersion: entry.bandVersion,
        metricVersions: entry.metricVersions, inputCount: entry.inputCount, eligibleCount: entry.eligibleCount,
        unbucketedCount: entry.unbucketedCount, cellCount: entry.cellCount, visibleCount: entry.visibleCount,
        outputSha256: entry.outputSha256,
      },
      replayed: false,
    };
  }
  async list(): Promise<JobRunRecord[]> {
    // Metric versions are not stored per run row; callers that need them read
    // the figure rows. Run listing carries the hashes that identify the run.
    const rows = (await this.pool.query(
      `SELECT run_id, cutoff_millis, computed_at, algorithm_version, taxonomy_version, band_version,
        metric_set_hash, input_sha256, input_count, eligible_count, unbucketed_count, cell_count, visible_count, output_sha256
       FROM community_aggregation.job_runs ORDER BY computed_at ASC, run_id ASC`)).rows;
    return Promise.all(rows.map(async (row) => {
      const versions = (await this.pool.query(
        'SELECT DISTINCT ON (figure->>\'metricId\') figure->>\'metricId\' AS id, figure->>\'metricVersion\' AS version FROM community_aggregation.figures WHERE run_id=$1 ORDER BY figure->>\'metricId\'',
        [row.run_id])).rows;
      return this.row(row, versions.map((v) => ({ metricId: v.id, metricVersion: v.version })));
    }));
  }
  async figures(runId: string): Promise<readonly CommunityFigure[] | undefined> {
    const exists = await this.pool.query('SELECT 1 FROM community_aggregation.job_runs WHERE run_id=$1', [runId]);
    if (!exists.rowCount) return undefined;
    const rows = (await this.pool.query(
      'SELECT figure FROM community_aggregation.figures WHERE run_id=$1 ORDER BY bucket ASC, metric_id ASC',
      [runId])).rows;
    return rows.map((r) => (typeof r.figure === 'string' ? JSON.parse(r.figure) : r.figure) as CommunityFigure);
  }
  private row(row: Record<string, string | number>, metricVersions: JobRunRecord['metricVersions']): JobRunRecord {
    return {
      runId: String(row.run_id), cutoffMillis: Number(row.cutoff_millis), computedAtMillis: Number(row.computed_at),
      algorithmVersion: String(row.algorithm_version), taxonomyVersion: String(row.taxonomy_version), bandVersion: String(row.band_version),
      metricVersions, inputCount: Number(row.input_count), eligibleCount: Number(row.eligible_count),
      unbucketedCount: Number(row.unbucketed_count), cellCount: Number(row.cell_count), visibleCount: Number(row.visible_count),
      outputSha256: String(row.output_sha256),
    };
  }
}

export class CommunityAggregates {
  readonly store: JobRunStore;
  readonly clock: () => number;
  constructor(store: JobRunStore = new MemoryJobRunStore(), clock: () => number = Date.now) {
    this.store = store;
    this.clock = clock;
  }
  build(raw: readonly unknown[], options: AggregationOptions): BuiltAggregation {
    if (!options || typeof options !== 'object') throw invalid();
    const { cutoffMillis, taxonomy, metrics } = options;
    if (!taxonomy || typeof taxonomy.bucket !== 'function'
      || typeof taxonomy.taxonomyVersion !== 'string' || !taxonomy.taxonomyVersion
      || typeof taxonomy.bandVersion !== 'string' || !taxonomy.bandVersion) throw invalid();
    if (!Array.isArray(metrics) || !metrics.length) throw invalid();
    for (const metric of metrics) {
      if (!metric || typeof metric.metricId !== 'string' || !metric.metricId
        || typeof metric.metricVersion !== 'string' || !metric.metricVersion
        || (metric.requires !== 'heatingMillis' && metric.requires !== 'finalTempCelsius'
          && metric.requires !== 'measurements' && metric.requires !== 'spritzed')
        || typeof metric.eligible !== 'function' || typeof metric.summarize !== 'function') throw invalid();
    }
    if (new Set(metrics.map((m) => m.metricId)).size !== metrics.length) throw invalid();
    const attempts = projectEligibleAttempts(raw, cutoffMillis);
    const metricVersions = [...metrics]
      .sort((a, b) => (a.metricId < b.metricId ? -1 : 1))
      .map((metric) => ({ metricId: metric.metricId, metricVersion: metric.metricVersion }));
    const metricSetHash = digest(stable(metricVersions));
    const inputSha256 = digest(stable(attempts));
    const computedAtMillis = this.clock();
    const computedDate = new Date(computedAtMillis).toISOString().slice(0, 10);
    let unbucketedCount = 0;
    const cells = new Map<string, { bucket: string; metric: MetricSpec; list: EligibleAttempt[] }>();
    for (const attempt of attempts) {
      const bucket = taxonomy.bucket(attempt);
      if (typeof bucket !== 'string' || !bucket) {
        if (bucket !== null) throw invalid();
        unbucketedCount++;
        continue;
      }
      for (const metric of metrics) {
        if (!metric.eligible(attempt)) continue;
        const key = `${bucket}\0${metric.metricId}`;
        const cell: { bucket: string; metric: MetricSpec; list: EligibleAttempt[] } = cells.get(key) ?? { bucket, metric, list: [] };
        cell.list.push(attempt);
        cells.set(key, cell);
      }
    }
    const figures: CommunityFigure[] = [...cells.values()]
      .sort((a, b) => (a.bucket < b.bucket ? -1 : a.bucket > b.bucket ? 1 : (a.metric.metricId < b.metric.metricId ? -1 : 1)))
      .map(({ bucket, metric, list }) => {
        const n = list.length;
        const status = n >= COMMUNITY_MIN_N ? 'visible' : 'insufficient_data';
        return {
          bucket, metricId: metric.metricId, metricVersion: metric.metricVersion, n, status,
          summary: status === 'visible' ? metric.summarize(list) : null,
          computedDate, cutoffMillis,
          algorithmVersion: COMMUNITY_ALGORITHM_VERSION,
          taxonomyVersion: taxonomy.taxonomyVersion, bandVersion: taxonomy.bandVersion,
        };
      });
    const bytes = stable(figures);
    const outputSha256 = digest(bytes);
    const entry: JobRunEntry = {
      cutoffMillis, computedAtMillis, algorithmVersion: COMMUNITY_ALGORITHM_VERSION,
      taxonomyVersion: taxonomy.taxonomyVersion, bandVersion: taxonomy.bandVersion,
      metricVersions, metricSetHash, inputSha256,
      inputCount: raw.length, eligibleCount: attempts.length, unbucketedCount,
      cellCount: figures.length, visibleCount: figures.filter((f) => f.status === 'visible').length,
      outputSha256, figures, bytes,
    };
    return { attempts, figures, bytes, outputSha256, inputSha256, metricSetHash, entry };
  }
  record(built: BuiltAggregation): Promise<{ run: JobRunRecord; replayed: boolean }> {
    return this.store.record(built.entry);
  }
}
