import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { parseBetaAllAccess } from '../src/beta-entitlement.ts';
import { Subscriptions, betaEntitlementRecord, ENTITLEMENT_CACHE_VERSION, OFFLINE_WINDOW_MS } from '../src/subscriptions.ts';
import { SubscriptionsEntitlementLookup, mcpProAccess } from '../src/mcp-grants.ts';
import { MemoryBillingStore } from './helpers.ts';
import { NOW, appleConfig, googleConfig, appleTrust, oidcKeySet, oidcAudience, oidcEmail, stubAppleApi, stubPlayApi } from './billing-fixtures.ts';

test('parse: unset, empty and false are off in either environment', () => {
  assert.equal(parseBetaAllAccess(undefined, 'production'), false);
  assert.equal(parseBetaAllAccess(undefined, 'staging'), false);
  assert.equal(parseBetaAllAccess('', 'staging'), false);
  assert.equal(parseBetaAllAccess('false', 'production'), false);
  assert.equal(parseBetaAllAccess('false', 'staging'), false);
});

test('parse: true is accepted on staging only, case and space tolerant', () => {
  assert.equal(parseBetaAllAccess('true', 'staging'), true);
  assert.equal(parseBetaAllAccess(' TRUE ', 'staging'), true);
});

test('parse: true outside staging refuses the boot', () => {
  assert.throws(() => parseBetaAllAccess('true', 'production'), /staging-only/);
});

test('parse: malformed values are rejected', () => {
  assert.throws(() => parseBetaAllAccess('yes', 'staging'), /Invalid BETA_ALL_ACCESS/);
  assert.throws(() => parseBetaAllAccess('1', 'staging'), /Invalid BETA_ALL_ACCESS/);
});

test('record: the beta grant carries the verified entitled shape', () => {
  const record = betaEntitlementRecord('sandbox', NOW);
  assert.equal(record.version, ENTITLEMENT_CACHE_VERSION);
  assert.equal(record.contractVersion, 'store-verification-v1');
  assert.equal(record.environment, 'sandbox');
  assert.equal(record.verified, true);
  assert.equal(record.state, 'active');
  assert.equal(record.verifiedUntil, null);
  assert.equal(record.lastVerifiedAt, new Date(NOW).toISOString());
  assert.equal(record.offlineValidUntil, new Date(NOW + OFFLINE_WINDOW_MS).toISOString());
});

const clock = () => NOW;
function setup(betaAllAccess: boolean) {
  const store = new MemoryBillingStore(), apple = stubAppleApi(), play = stubPlayApi();
  const service = new Subscriptions(store, {
    apple: { config: appleConfig, trust: appleTrust, api: apple.api },
    google: { config: googleConfig, api: play.api, oidcKeys: oidcKeySet, oidcAudience, oidcEmail },
  }, clock, { betaAllAccess });
  return { store, service };
}

test('cache: with beta all-access a purchaseless account reads active in both store environments', async () => {
  const { store, service } = setup(true), account = randomUUID();
  for (const environment of ['production', 'sandbox'] as const) {
    const cache = await service.cache(account, environment);
    assert.equal(cache.state, 'active');
    assert.equal(cache.verified, true);
    assert.equal(cache.environment, environment);
  }
  // The grant is a read projection only: nothing was written to the store.
  assert.equal(store.entitlements.size, 0);
  assert.equal(store.reconciliations.length, 0);
});

test('cache: without the flag the same account stays unverified', async () => {
  const { service } = setup(false), account = randomUUID();
  const cache = await service.cache(account, 'sandbox');
  assert.equal(cache.state, 'unknown');
  assert.equal(cache.verified, false);
});

test('reconcile: with beta all-access the launch reconcile answers active and writes nothing', async () => {
  const { store, service } = setup(true), account = randomUUID();
  const cache = await service.reconcileAccount(account, 'sandbox');
  assert.equal(cache.state, 'active');
  assert.equal(cache.verified, true);
  assert.equal(store.entitlements.size, 0);
});

test('mcp lookup: the beta grant satisfies the MCP Pro gate for any account', async () => {
  const { service } = setup(true), account = randomUUID();
  const lookup = new SubscriptionsEntitlementLookup(service, 'sandbox');
  const state = await lookup.state(account);
  assert.equal(state, 'active');
  assert.equal(mcpProAccess(state), true);
});
