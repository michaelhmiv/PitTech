import { test, after, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRuntime } from '../src/config.ts';
import { CommunityAggregates, MemoryJobRunStore } from '../src/community-aggregation.ts';
import { runCommunityAggregationOnce, startCommunityScheduler } from '../src/community-scheduler.ts';

const directory = await mkdtemp(join(tmpdir(), 'pittech-community-config-'));
after(() => rm(directory, { recursive: true, force: true }));

const base: Record<string, string> = {
  RATE_HMAC_KEY: 'x'.repeat(32),
  DATABASE_URL: 'postgres://localhost:5432/pittech',
  STORAGE_MAX_BYTES: String(1024 ** 3),
  DAILY_UPLOAD_MAX_BYTES: String(1024 ** 3),
  DAILY_UPLOAD_MAX_COUNT: '1000',
  OBJECT_STORAGE: 'filesystem',
  OBJECT_DIRECTORY: directory,
  NODE_ENV: 'test',
  PORT: '3000',
  APPLE_APP_IDS: '',
  ANDROID_APP_LINK_CERTIFICATES: '',
  TRUSTED_PROXY_CIDRS: '',
};
const quiet: Record<string, string | undefined> = {
  COMMUNITY_AGGREGATION_ENABLED: undefined,
  AUTH_ENABLED: undefined,
  SUBSCRIPTIONS_ENABLED: undefined,
  PUBLIC_API_ENABLED: undefined,
  ACCOUNT_LIFECYCLE_ENABLED: undefined,
  PRIVATE_SYNC_ENABLED: undefined,
};
function withEnv(t: TestContext, vars: Record<string, string | undefined>) {
  const saved = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(vars)) {
    if (!saved.has(key)) saved.set(key, process.env[key]);
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  t.after(() => {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  });
}

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const NOW = Date.UTC(2026, 9, 18, 12);
const contribution = (index: number): unknown => ({
  itemId: id(1000 + index), revisionId: id(2000 + index), status: 'active',
  collectedAtMillis: NOW - 1000, attemptId: id(3000 + index), dishId: id(4000 + index),
  foodType: 'Pork', cut: 'Shoulder', weightGrams: 3855, method: 'Smoke',
  heatingMillis: 27600000, finalTemp: { value: 100, unit: '°C' },
});

test('community aggregation stays disabled by default with no services or routes', async t => {
  withEnv(t, { ...quiet, ...base });
  const runtime = createRuntime();
  try {
    assert.equal(runtime.communityAggregates, undefined);
    assert.equal(runtime.options.communityAggregates, undefined);
  } finally { await runtime.close(); }
});

test('community aggregation ignores the flag unless explicitly true', async t => {
  withEnv(t, { ...quiet, ...base, COMMUNITY_AGGREGATION_ENABLED: 'false' });
  const runtime = createRuntime();
  try {
    assert.equal(runtime.communityAggregates, undefined);
  } finally { await runtime.close(); }
});

test('community aggregation constructs production services when explicitly enabled', async t => {
  withEnv(t, { ...quiet, ...base, COMMUNITY_AGGREGATION_ENABLED: 'true' });
  const runtime = createRuntime();
  try {
    assert.ok(runtime.communityAggregates instanceof CommunityAggregates);
    assert.equal(runtime.options.communityAggregates, runtime.communityAggregates);
  } finally { await runtime.close(); }
});

test('community production job uses the real taxonomy and metric set, never stubs', async t => {
  void t;
  const store = new MemoryJobRunStore();
  const raw = Array.from({ length: 10 }, (_, i) => contribution(i));
  const { run, replayed } = await runCommunityAggregationOnce({ store, load: async () => raw, clock: () => NOW });
  assert.equal(replayed, false);
  assert.equal(run.taxonomyVersion, 'community-taxonomy-v1');
  assert.equal(run.bandVersion, 'community-bands-v1');
  assert.ok(!run.taxonomyVersion.includes('non-authoritative'));
  assert.deepEqual(run.metricVersions, [
    { metricId: 'median-heating-time-v1', metricVersion: 'community-metrics-v1' },
    { metricId: 'pit-food-curve-percentiles-v1', metricVersion: 'community-metrics-v1' },
    { metricId: 'stall-summary-v1', metricVersion: 'community-metrics-v1' },
    { metricId: 'wrap-spritz-prevalence-v1', metricVersion: 'community-metrics-v1' },
  ]);
  const replay = await runCommunityAggregationOnce({ store, load: async () => structuredClone(raw), clock: () => NOW });
  assert.equal(replay.replayed, true);
  assert.equal(replay.run.runId, run.runId);
});

test('community scheduler runs the job, records a run, and guards overlaps', async () => {
  const store = new MemoryJobRunStore();
  let concurrent = 0;
  let maxConcurrent = 0;
  const load = async (): Promise<readonly unknown[]> => {
    concurrent++;
    maxConcurrent = Math.max(maxConcurrent, concurrent);
    await new Promise((resolve) => setTimeout(resolve, 30));
    concurrent--;
    return Array.from({ length: 10 }, (_, i) => contribution(i));
  };
  const scheduler = startCommunityScheduler({ store, load, clock: () => NOW, intervalMillis: 10 });
  try {
    const deadline = Date.now() + 2000;
    while ((await store.list()).length === 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    const runs = await store.list();
    assert.ok(runs.length >= 1);
    assert.equal(runs[0].taxonomyVersion, 'community-taxonomy-v1');
    // Overlapping ticks collapse: the slow loader never runs concurrently.
    assert.equal(maxConcurrent, 1);
  } finally {
    scheduler.stop();
  }
});
