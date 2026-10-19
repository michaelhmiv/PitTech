// Slice 17 completion: production community aggregation scheduling.
//
// The job aggregates ONLY deliberately active public measured attempts via the
// allowlisted eligible_attempts() projection (least-privilege by code path;
// production must grant the pittech_community_aggregation_runtime role per
// operations/community-aggregation-runtime-role.sql). It runs with the
// production taxonomy (community-taxonomy-v1 / community-bands-v1) and the
// production metric set (community-metrics-v1); stubs never run here.
import type { Pool } from 'pg';
import {
  CommunityAggregates, COMMUNITY_METRICS_V1, loadRawContributions, type JobRunStore,
} from './community-aggregation.ts';
import { COMMUNITY_TAXONOMY_V1 } from './community-taxonomy.ts';

// Daily. Figures carry a UTC compute DATE so repeats within a day are
// byte-identical (same input replays, minting no duplicate run); community
// data changes slowly and Pro launches on personal value even when every cell
// is below threshold, so a daily recompute over a fresh cutoff is sufficient
// and avoids same-date churn. Removals propagate on the next run.
export const COMMUNITY_AGGREGATION_INTERVAL_MILLIS = 86400000;

export type CommunityJobDeps = {
  readonly store: JobRunStore;
  readonly load: () => Promise<readonly unknown[]>;
  readonly clock?: () => number;
};

/** One production aggregation run: fresh cutoff, production taxonomy and
 * metrics, persisted via the provided store. Pure wiring around build/record. */
export async function runCommunityAggregationOnce(deps: CommunityJobDeps, cutoffMillis?: number) {
  const clock = deps.clock ?? Date.now;
  const cutoff = cutoffMillis ?? clock();
  const aggregates = new CommunityAggregates(deps.store, clock);
  const raw = await deps.load();
  const built = aggregates.build(raw, {
    cutoffMillis: cutoff, taxonomy: COMMUNITY_TAXONOMY_V1, metrics: COMMUNITY_METRICS_V1,
  });
  return aggregates.record(built);
}

export function productionCommunityLoad(pool: Pool): () => Promise<readonly unknown[]> {
  return () => loadRawContributions(pool);
}

/** Periodic scheduler with the sweep overlap guard. Runs once immediately on
 * start so a deploy recomputes without waiting a full day, then on the
 * interval. Failures defer to the next tick, never crash the process. */
export function startCommunityScheduler(deps: CommunityJobDeps & { readonly intervalMillis?: number }) {
  const interval = deps.intervalMillis ?? COMMUNITY_AGGREGATION_INTERVAL_MILLIS;
  let running = false;
  let stopped = false;
  const tick = async (): Promise<void> => {
    if (running || stopped) return;
    running = true;
    try {
      await runCommunityAggregationOnce(deps);
    } catch {
      console.error('community_aggregation_deferred');
    } finally {
      running = false;
    }
  };
  void tick();
  const timer = setInterval(() => { void tick(); }, interval);
  timer.unref();
  return {
    stop: (): void => { stopped = true; clearInterval(timer); },
    tick,
  };
}
