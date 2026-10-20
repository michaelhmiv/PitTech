import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { makeServer } from '../src/http.ts';
import { ApiError } from '../src/core.ts';
import { Relay } from '../src/relay.ts';
import type { Authentication } from '../src/auth.ts';
import { Subscriptions } from '../src/subscriptions.ts';
import { CommunityAggregates, MemoryJobRunStore } from '../src/community-aggregation.ts';
import { COMMUNITY_TAXONOMY_V1 } from '../src/community-taxonomy.ts';
import { COMMUNITY_METRICS_V1 } from '../src/community-aggregation.ts';
import { MemoryRepository, MemoryBillingStore, budgets } from './helpers.ts';
import {
  NOW, appleConfig, googleConfig, appleTrust, oidcKeySet, oidcAudience, oidcEmail,
  signApple, appleTransaction, stubAppleApi, stubPlayApi,
} from './billing-fixtures.ts';

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const contribution = (index: number, cut: string, weightGrams: number): unknown => ({
  itemId: id(1000 + index), revisionId: id(2000 + index), status: 'active',
  collectedAtMillis: NOW - 1000, attemptId: id(3000 + index), dishId: id(4000 + index),
  foodType: 'Pork', cut, weightGrams, method: 'Smoke',
  heatingMillis: 27600000, finalTemp: { value: 100, unit: '°C' },
});

async function setup(t: { after: (fn: () => void) => void }) {
  const billing = new MemoryBillingStore();
  const apple = stubAppleApi(), play = stubPlayApi();
  const access = randomBytes(32).toString('base64url'), userId = randomUUID();
  const otherAccess = randomBytes(32).toString('base64url'), otherId = randomUUID();
  const auth = {
    user: async (token: unknown) => {
      if (token === access) return { id: userId, email: 'synthetic-pro@example.com' };
      if (token === otherAccess) return { id: otherId, email: 'synthetic-free@example.com' };
      throw new ApiError(401, 'authentication_required');
    },
  } as unknown as Authentication;
  const service = new Subscriptions(billing, {
    apple: { config: appleConfig, trust: appleTrust, api: apple.api },
    google: { config: googleConfig, api: play.api, oidcKeys: oidcKeySet, oidcAudience, oidcEmail },
  }, () => NOW);
  const store = new MemoryJobRunStore();
  const aggregates = new CommunityAggregates(store, () => NOW);
  // Ten in the butt bucket (visible), nine in the loin bucket (suppressed).
  const raw = [
    ...Array.from({ length: 10 }, (_, i) => contribution(i, 'Shoulder', 3855)),
    ...Array.from({ length: 9 }, (_, i) => contribution(100 + i, 'Loin', 2000)),
  ];
  const built = aggregates.build(raw, { cutoffMillis: NOW, taxonomy: COMMUNITY_TAXONOMY_V1, metrics: COMMUNITY_METRICS_V1 });
  const { run } = await aggregates.record(built);
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const server = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW),
    { ipKey: 'x'.repeat(32), uploadsEnabled: false, auth, subscriptions: service, communityAggregates: aggregates });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  return { service, aggregates, store, run, base, access, userId, otherAccess };
}
type Setup = Awaited<ReturnType<typeof setup>>;

test('community aggregates require signed-in Pro and serve recorded figures with n and date', async t => {
  const s: Setup = await setup(t);
  assert.equal((await fetch(s.base + '/v1/community/aggregates')).status, 401);
  assert.equal((await fetch(s.base + '/v1/community/job-runs')).status, 401);
  const unknown = await fetch(s.base + '/v1/community/aggregates', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(unknown.status, 403);
  const denied = await unknown.json() as { error: { code: string } };
  assert.equal(denied.error.code, 'pro_required');
  assert.ok(!JSON.stringify(denied).includes('medianHeatingMillis'));

  await s.service.verifyPurchase(s.userId, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  const response = await fetch(s.base + '/v1/community/aggregates', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('cache-control')!, /no-store/);
  const body = await response.json() as { run: { runId: string }; figures: { bucket: string; metricId: string; n: number; status: string; summary: unknown; computedDate: string }[] };
  assert.equal(body.run.runId, s.run.runId);
  const heating = body.figures.filter((f) => f.metricId === 'median-heating-time-v1');
  assert.equal(heating.length, 2);
  const visible = heating.find((f) => f.bucket.startsWith('pork-butt-shoulder|'))!;
  const suppressed = heating.find((f) => f.bucket.startsWith('pork-loin|'))!;
  assert.equal(visible.n, 10);
  assert.equal(visible.status, 'visible');
  assert.notEqual(visible.summary, null);
  assert.equal(suppressed.n, 9);
  assert.equal(suppressed.status, 'insufficient_data');
  assert.equal(suppressed.summary, null);
  assert.equal(suppressed.computedDate, '2026-10-18');
  // Stored bytes round-trip exactly: the API never fabricates figures.
  assert.deepEqual(body.figures, await s.store.figures(s.run.runId));

  const other = await fetch(s.base + '/v1/community/aggregates', { headers: { Authorization: 'Bearer ' + s.otherAccess } });
  assert.equal(other.status, 403);
  assert.ok(!JSON.stringify(await other.json()).includes('medianHeatingMillis'));
});

test('community aggregates validate bucket, metric, run, and pagination bounds', async t => {
  const s: Setup = await setup(t);
  await s.service.verifyPurchase(s.userId, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  const auth = { Authorization: 'Bearer ' + s.access };
  const butt = encodeURIComponent('pork-butt-shoulder|medium|method:smoke');
  const filtered = await fetch(`${s.base}/v1/community/aggregates?bucket=${butt}&metric=median-heating-time-v1`, { headers: auth });
  assert.equal(filtered.status, 200);
  const body = await filtered.json() as { figures: { bucket: string; metricId: string }[] };
  assert.ok(body.figures.length >= 1);
  assert.ok(body.figures.every((f) => f.bucket === 'pork-butt-shoulder|medium|method:smoke' && f.metricId === 'median-heating-time-v1'));
  assert.equal((await fetch(`${s.base}/v1/community/aggregates?bucket=evil`, { headers: auth })).status, 400);
  assert.equal((await fetch(`${s.base}/v1/community/aggregates?metric=stub-heating-presence`, { headers: auth })).status, 400);
  assert.equal((await fetch(`${s.base}/v1/community/aggregates?runId=${randomUUID()}`, { headers: auth })).status, 404);
  assert.equal((await fetch(`${s.base}/v1/community/aggregates?runId=not-a-uuid`, { headers: auth })).status, 400);
  assert.equal((await fetch(`${s.base}/v1/community/aggregates?bucket=${butt}&bucket=${butt}`, { headers: auth })).status, 400);

  const runs = await fetch(s.base + '/v1/community/job-runs?limit=1', { headers: auth });
  assert.equal(runs.status, 200);
  const page = await runs.json() as { runs: { runId: string }[]; nextCursor: string | null };
  assert.equal(page.runs.length, 1);
  assert.equal((await fetch(s.base + '/v1/community/job-runs?limit=0', { headers: auth })).status, 400);
  assert.equal((await fetch(s.base + '/v1/community/job-runs?limit=101', { headers: auth })).status, 400);
  assert.equal((await fetch(s.base + '/v1/community/job-runs?cursor=bad', { headers: auth })).status, 400);
});

test('community routes stay absent while subscriptions or aggregation are disabled', async t => {
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const bodies = { ipKey: 'x'.repeat(32), uploadsEnabled: false };
  const lonely = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW),
    { ...bodies, communityAggregates: new CommunityAggregates(new MemoryJobRunStore(), () => NOW) });
  lonely.listen(0, '127.0.0.1');
  await once(lonely, 'listening');
  try {
    const base = `http://127.0.0.1:${(lonely.address() as { port: number }).port}`;
    assert.equal((await fetch(base + '/v1/community/aggregates')).status, 404);
    assert.equal((await fetch(base + '/v1/community/job-runs')).status, 404);
  } finally {
    lonely.closeAllConnections();
    lonely.close();
  }
  void t;
});
