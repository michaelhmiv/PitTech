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

// A metric is an eligibility rule plus a summarizer hook. Slice 15's
// calculation contract supplies reviewed MetricSpecs (median heating time,
// curve percentiles, stall summaries, wrap prevalence); this lane ships only
// presence-based stubs whose summaries are explicit pending markers, so no
// metric meaning is pinned here.
export type CommunityMetricField = 'heatingMillis' | 'finalTempCelsius';
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
  private runs: { key: string; run: JobRunRecord }[] = [];
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
    this.runs.push({ key, run });
    return { run, replayed: false };
  }
  async list(): Promise<JobRunRecord[]> {
    return [...this.runs]
      .sort((a, b) => a.run.computedAtMillis - b.run.computedAtMillis || (a.run.runId < b.run.runId ? -1 : 1))
      .map((row) => row.run);
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
        || (metric.requires !== 'heatingMillis' && metric.requires !== 'finalTempCelsius')
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
