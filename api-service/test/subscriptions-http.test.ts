import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { makeServer } from '../src/http.ts';
import { ApiError } from '../src/core.ts';
import { Relay } from '../src/relay.ts';
import type { Authentication } from '../src/auth.ts';
import { Subscriptions } from '../src/subscriptions.ts';
import { MemoryRepository, MemoryBillingStore, budgets } from './helpers.ts';
import {
  NOW, MONTH, appleConfig, googleConfig, appleTrust, oidcKeySet, oidcAudience, oidcEmail,
  signApple, appleTransaction, appleRenewal, appleStatusBody, appleNotificationBody,
  stubAppleApi, stubPlayApi, signOidc, googleSubscription, rtdnBody, rtdnSubscription,
} from './billing-fixtures.ts';

async function setup(t: { after: (fn: () => void) => void }) {
  const store = new MemoryBillingStore(), apple = stubAppleApi(), play = stubPlayApi();
  const access = randomBytes(32).toString('base64url'), userId = randomUUID();
  const auth = { user: async (token: unknown) => { if (token !== access) throw new ApiError(401, 'authentication_required'); return { id: userId, email: 'synthetic-billing@example.com' }; } } as unknown as Authentication;
  const service = new Subscriptions(store, {
    apple: { config: appleConfig, trust: appleTrust, api: apple.api },
    google: { config: googleConfig, api: play.api, oidcKeys: oidcKeySet, oidcAudience, oidcEmail },
  }, () => NOW);
  const logs: unknown[] = [];
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const server = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW), { ipKey: 'x'.repeat(32), uploadsEnabled: false, auth, subscriptions: service, log: row => logs.push(row) });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  return { store, apple, play, service, logs, base, access, userId, json: { 'Content-Type': 'application/json' as const } };
}
type Setup = Awaited<ReturnType<typeof setup>>;

test('billing routes require verified accounts and expose the unknown cache before any purchase', async t => {
  const s: Setup = await setup(t);
  assert.equal((await fetch(s.base + '/v1/me/entitlement')).status, 401);
  assert.equal((await fetch(s.base + '/v1/me/entitlement', { headers: { Authorization: 'Bearer ' + randomBytes(32).toString('base64url') } })).status, 401);
  const unknown = await fetch(s.base + '/v1/me/entitlement', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(unknown.status, 200); assert.match(unknown.headers.get('cache-control')!, /no-store/);
  assert.deepEqual(await unknown.json(), { version: 'entitlement-cache-v1', environment: 'production', verified: false, state: 'unknown', verifiedUntil: null, lastVerifiedAt: null, offlineValidUntil: null, contractVersion: 'store-verification-v1' });
  const sandbox = await fetch(s.base + '/v1/me/entitlement?environment=sandbox', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(sandbox.status, 200); assert.equal(((await sandbox.json()) as { environment: string }).environment, 'sandbox');
  assert.equal((await fetch(s.base + '/v1/me/entitlement?environment=evil', { headers: { Authorization: 'Bearer ' + s.access } })).status, 400);
  assert.equal((await fetch(s.base + '/v1/me/purchases/verify', { method: 'POST', headers: s.json, body: '{}' })).status, 401);
  const bearer = { ...s.json, Authorization: 'Bearer ' + s.access };
  assert.equal((await fetch(s.base + '/v1/me/purchases/verify', { method: 'POST', headers: bearer, body: JSON.stringify({ store: 'apple', signedTransaction: 'garbage' }) })).status, 422);
  assert.equal((await fetch(s.base + '/v1/me/purchases/verify', { method: 'POST', headers: bearer, body: JSON.stringify({ store: 'apple', signedTransaction: 'garbage', entitled: true }) })).status, 422);
  assert.equal((await fetch(s.base + '/v1/me/purchases/verify', { method: 'POST', headers: bearer, body: JSON.stringify({ store: 'apple' }) })).status, 422);
});

test('billing HTTP verifies purchases, serves the cache, reconciles and releases bindings', async t => {
  const s: Setup = await setup(t);
  const bearer = { ...s.json, Authorization: 'Bearer ' + s.access };
  const proof = signApple(appleTransaction());
  const verified = await fetch(s.base + '/v1/me/purchases/verify', { method: 'POST', headers: bearer, body: JSON.stringify({ store: 'apple', signedTransaction: proof }) });
  assert.equal(verified.status, 200);
  const receipt = await verified.json() as { cache: { state: string }; transaction: { transactionKey: string } };
  assert.equal(receipt.cache.state, 'active'); assert.equal(receipt.transaction.transactionKey, '2000000111111111');
  const cached = await fetch(s.base + '/v1/me/entitlement', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(((await cached.json()) as { state: string }).state, 'active');
  s.apple.status.body = appleStatusBody([{ status: 1, transaction: proof, renewal: signApple(appleRenewal('2000000111111111', true, NOW)) }]);
  const reconciled = await fetch(s.base + '/v1/me/entitlement/reconcile', { method: 'POST', headers: bearer, body: JSON.stringify({}) });
  assert.equal(reconciled.status, 200); assert.equal(((await reconciled.json()) as { state: string }).state, 'active');
  const restored = await fetch(s.base + '/v1/me/purchases/restore', { method: 'POST', headers: bearer, body: JSON.stringify({ proofs: [{ store: 'apple', signedTransaction: proof }] }) });
  assert.equal(restored.status, 200); assert.equal((((await restored.json()) as { outcomes: { note: string }[] }).outcomes[0].note), 'duplicate-transaction');
  const released = await fetch(s.base + '/v1/me/purchases/release', { method: 'POST', headers: bearer, body: JSON.stringify({ store: 'apple', environment: 'production', transactionKey: '2000000111111111' }) });
  assert.equal(released.status, 200); assert.deepEqual(await released.json(), { released: true });
  const after = await fetch(s.base + '/v1/me/entitlement', { headers: { Authorization: 'Bearer ' + s.access } });
  assert.equal(((await after.json()) as { state: string }).state, 'expired');
  assert.equal((await fetch(s.base + '/v1/me/purchases/release', { method: 'POST', headers: bearer, body: JSON.stringify({ store: 'apple', environment: 'production', transactionKey: '2000000111111111' }) })).status, 404);
});

test('billing notification endpoints verify signatures, dedupe replays and acknowledge RTDN', async t => {
  const s: Setup = await setup(t);
  const proof = signApple(appleTransaction());
  await s.service.verifyPurchase(s.userId, { store: 'apple', signedTransaction: proof });
  s.play.subscription.body = googleSubscription();
  await s.service.verifyPurchase(s.userId, { store: 'google', purchaseToken: 'synthetic-http-token' });
  const graceTx = appleTransaction({ transactionId: '2000001100000002', purchaseDate: NOW, expiresDate: NOW + MONTH, signedDate: NOW });
  s.apple.status.body = appleStatusBody([{ status: 4, transaction: signApple(graceTx), renewal: signApple(appleRenewal('2000000111111111', true, NOW)) }]);
  const id = randomUUID();
  const outer = signApple(appleNotificationBody('DID_FAIL_TO_RENEW', signApple(graceTx), id, NOW, 'Production', signApple(appleRenewal('2000000111111111', true, NOW)), 'GRACE_PERIOD'));
  assert.equal((await fetch(s.base + '/v1/billing/apple/notifications', { method: 'POST', headers: s.json, body: JSON.stringify({ signedPayload: 'forged' }) })).status, 401);
  const applied = await fetch(s.base + '/v1/billing/apple/notifications', { method: 'POST', headers: s.json, body: JSON.stringify({ signedPayload: outer }) });
  assert.equal(applied.status, 200); assert.deepEqual(await applied.json(), { outcome: 'applied', note: 'transition' });
  const replay = await fetch(s.base + '/v1/billing/apple/notifications', { method: 'POST', headers: s.json, body: JSON.stringify({ signedPayload: outer }) });
  assert.equal(replay.status, 200); assert.deepEqual(await replay.json(), { outcome: 'deduped', note: 'replay' });
  assert.equal((await s.service.cache(s.userId, 'production')).state, 'grace');
  const push = (body: unknown, authorization?: string) => fetch(s.base + '/v1/billing/google/notifications', { method: 'POST', headers: authorization ? { ...s.json, Authorization: authorization } : s.json, body: JSON.stringify(body) });
  const message = rtdnBody('synthetic-http-message', rtdnSubscription('synthetic-http-token', 2));
  assert.equal((await push(message)).status, 401);
  assert.equal((await push(message, 'Bearer forged')).status, 401);
  const acknowledged = await push(message, `Bearer ${signOidc()}`);
  assert.equal(acknowledged.status, 200); assert.deepEqual(await acknowledged.json(), { outcome: 'applied', note: 'transition' });
  const replayed = await push(message, `Bearer ${signOidc()}`);
  assert.equal(replayed.status, 200); assert.deepEqual(await replayed.json(), { outcome: 'deduped', note: 'replay' });
  const testPush = rtdnBody('synthetic-http-test', { version: '1.0', packageName: googleConfig.packageName, eventTimeMillis: String(NOW), testNotification: { version: '1.0' } });
  assert.deepEqual(await (await push(testPush, `Bearer ${signOidc()}`)).json(), { outcome: 'recorded', note: 'test-delivery' });
  const log = JSON.stringify(s.logs);
  for (const secret of [proof.slice(0, 40), outer.slice(0, 40), 'synthetic-http-token', s.access]) assert.ok(!log.includes(secret));
  assert.ok(log.includes('/v1/billing/google/notifications'));
});

test('billing routes stay absent while subscriptions are disabled', async t => {
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0), delete: async () => {} };
  const server = makeServer(new Relay(new MemoryRepository(), storage, budgets, () => NOW), { ipKey: 'x'.repeat(32), uploadsEnabled: false });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
    assert.equal((await fetch(base + '/v1/me/entitlement')).status, 404);
    assert.equal((await fetch(base + '/v1/billing/apple/notifications', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 404);
  } finally { server.closeAllConnections(); server.close(); }
});
