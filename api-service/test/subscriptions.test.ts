import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { verifyAppleTransaction } from '../src/subscriptions-apple.ts';
import { PlayDeveloperApi } from '../src/subscriptions-google.ts';
import { Subscriptions, deriveAppleState, deriveGoogleState, projectCache, transitionEntitlement, ENTITLEMENT_CACHE_VERSION, OFFLINE_WINDOW_MS } from '../src/subscriptions.ts';
import { MemoryBillingStore } from './helpers.ts';
import {
  NOW, MONTH, appleConfig, googleConfig, appleTrust, applePublicPem, oidcKeySet, oidcAudience, oidcEmail,
  signApple, appleTransaction, appleRenewal, appleStatusBody, appleNotificationBody,
  stubAppleApi, stubPlayApi, signOidc, googleSubscription, rtdnBody, rtdnSubscription, type StubApple,
} from './billing-fixtures.ts';

let now = NOW;
const clock = () => now;
function setup() {
  now = NOW;
  const store = new MemoryBillingStore(), apple = stubAppleApi(), play = stubPlayApi();
  const service = new Subscriptions(store, {
    apple: { config: appleConfig, trust: appleTrust, api: apple.api },
    google: { config: googleConfig, api: play.api, oidcKeys: oidcKeySet, oidcAudience, oidcEmail },
  }, clock);
  return { store, apple, play, service };
}
function notify(input: { type: string; tx: Record<string, unknown>; renewal?: Record<string, unknown>; id?: string; at?: number; environment?: string; subtype?: string }): string {
  const at = input.at ?? now, environment = input.environment ?? 'Production';
  return signApple(appleNotificationBody(input.type, signApple(input.tx), input.id ?? randomUUID(), at, environment, input.renewal ? signApple(input.renewal) : undefined, input.subtype));
}
function setStatus(apple: StubApple, rows: { status: number; tx: Record<string, unknown>; renewal?: Record<string, unknown> }[], environment = 'Production') {
  apple.status.body = appleStatusBody(rows.map(r => ({ status: r.status, transaction: signApple(r.tx), renewal: r.renewal ? signApple(r.renewal) : undefined })), environment);
}

test('Apple verification binds a purchase to the verified account and resolves the environment from the signed payload', async () => {
  const { service } = setup(), account = randomUUID();
  await assert.rejects(service.verifyPurchase('not-a-uuid', { store: 'apple', signedTransaction: 'x' }), { code: 'authentication_required' });
  await assert.rejects(service.cache('not-a-uuid', 'production'), { code: 'authentication_required' });
  const first = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  assert.equal(first.applied, true); assert.equal(first.cache.verified, true); assert.equal(first.cache.state, 'active');
  assert.equal(first.cache.environment, 'production'); assert.equal(first.transaction.transactionKey, '2000000111111111');
  assert.equal(first.cache.verifiedUntil, new Date(NOW + MONTH).toISOString());
  const sandbox = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000222222222', transactionId: '2000000233333333', environment: 'Sandbox' })) });
  assert.equal(sandbox.cache.environment, 'sandbox'); assert.equal(sandbox.cache.state, 'active');
  assert.equal((await service.cache(account, 'production')).state, 'active');
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()), claimedEnvironment: 'sandbox' }), { code: 'wrong_store_environment' });
});

test('the same store transaction applied twice never double-extends entitlement', async () => {
  const { store, service } = setup(), account = randomUUID();
  const proof = signApple(appleTransaction());
  const first = await service.verifyPurchase(account, { store: 'apple', signedTransaction: proof });
  const again = await service.verifyPurchase(account, { store: 'apple', signedTransaction: proof });
  assert.equal(again.applied, false); assert.equal(again.note, 'duplicate-transaction');
  assert.equal(again.cache.verifiedUntil, first.cache.verifiedUntil); assert.equal(store.transactions.size, 1);
  assert.equal(store.reconciliations.length, 1);
  now += MONTH;
  const renewed = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ transactionId: '2000000199999999', purchaseDate: now, expiresDate: now + MONTH, signedDate: now })) });
  assert.equal(renewed.applied, true); assert.equal(renewed.cache.verifiedUntil, new Date(now + MONTH).toISOString());
  assert.equal(store.transactions.size, 1);
});

test('pending purchases activate only on verified renewal evidence', async () => {
  const { service } = setup(), account = randomUUID();
  const tx = appleTransaction({ transactionId: '2000000300000001' }); delete tx.expiresDate;
  const pending = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(tx) });
  assert.equal(pending.cache.state, 'pending'); assert.equal(pending.cache.verifiedUntil, null); assert.equal(pending.cache.offlineValidUntil, null);
  now += 1000;
  const active = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ transactionId: '2000000300000002', purchaseDate: now, expiresDate: now + MONTH, signedDate: now })) });
  assert.equal(active.applied, true); assert.equal(active.note, 'transition'); assert.equal(active.cache.state, 'active');
});

test('verified notifications walk grace, retry, pause, cancellation, expiry, refund and revocation', async () => {
  const { store, apple, play, service } = setup(), account = randomUUID(), original = '2000000400000000';
  const tx = (id: string, extra: Record<string, unknown> = {}) => appleTransaction({ originalTransactionId: original, transactionId: id, purchaseDate: now, expiresDate: now + MONTH, signedDate: now, ...extra });
  const renewal = (auto: boolean) => appleRenewal(original, auto, now);
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(tx('2000000400000001')), signedRenewalInfo: signApple(renewal(true)) });
  assert.equal((await service.cache(account, 'production')).state, 'active');
  const step = async (type: string, status: number, auto: boolean, id: string, extra: Record<string, unknown> = {}, subtype?: string) => {
    now += 1000;
    const current = tx(id, extra);
    setStatus(apple, [{ status, tx: current, renewal: renewal(auto) }]);
    const result = await service.appleNotification(notify({ type, tx: current, renewal: renewal(auto), at: now, subtype }));
    assert.equal(result.outcome, 'applied');
    return (await service.cache(account, 'production')).state;
  };
  assert.equal(await step('DID_FAIL_TO_RENEW', 4, true, '2000000400000002', {}, 'GRACE_PERIOD'), 'grace');
  assert.equal(await step('DID_FAIL_TO_RENEW', 3, true, '2000000400000003', {}, 'RETRY_PERIOD'), 'retry');
  assert.equal(await step('DID_RENEW', 1, true, '2000000400000004'), 'active');
  assert.equal(await step('DID_CHANGE_RENEWAL_STATUS', 1, false, '2000000400000005', {}, 'AUTO_RENEW_DISABLED'), 'cancelled');
  now += MONTH + 1000;
  setStatus(apple, [{ status: 2, tx: tx('2000000400000006', { expiresDate: now - 1000 }), renewal: renewal(false) }]);
  assert.equal((await service.reconcileAccount(account, 'production')).state, 'expired');
  now += 1000;
  const resubscribe = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(tx('2000000400000007')), signedRenewalInfo: signApple(renewal(true)) });
  assert.equal(resubscribe.cache.state, 'active');
  now += 1000;
  const refundedTx = tx('2000000400000007');
  setStatus(apple, [{ status: 2, tx: refundedTx, renewal: renewal(true) }]);
  assert.equal(await step('REFUND', 2, true, '2000000400000007'), 'refunded');
  now += 1000;
  const fresh = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000499999999', transactionId: '2000000499999999', purchaseDate: now, expiresDate: now + MONTH, signedDate: now })), signedRenewalInfo: signApple(appleRenewal('2000000499999999', true, now)) });
  assert.equal(fresh.cache.state, 'active');
  now += 1000;
  const revokeTx = appleTransaction({ originalTransactionId: '2000000499999999', transactionId: '2000000499999999', purchaseDate: now, expiresDate: now + MONTH, signedDate: now });
  setStatus(apple, [{ status: 5, tx: revokeTx, renewal: appleRenewal('2000000499999999', true, now) }]);
  const revoked = await service.appleNotification(notify({ type: 'REVOKE', tx: revokeTx, renewal: appleRenewal('2000000499999999', true, now), at: now }));
  assert.equal(revoked.outcome, 'applied'); assert.equal((await service.cache(account, 'production')).state, 'revoked');
  play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_PAUSED' });
  const paused = await service.verifyPurchase(randomUUID(), { store: 'google', purchaseToken: 'synthetic-pause-token' });
  assert.equal(paused.cache.state, 'paused');
  assert.ok(store.reconciliations.length >= 10);
});

test('Google verification enforces app association and acknowledges pending purchases during verification', async () => {
  const { play, service } = setup(), account = randomUUID();
  play.subscription.body = googleSubscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' });
  const result = await service.verifyPurchase(account, { store: 'google', purchaseToken: 'synthetic-token-ack' });
  assert.equal(result.cache.state, 'active'); assert.equal(play.acknowledged.length, 1);
  assert.ok(play.calls[0].url === 'https://oauth2.googleapis.com/token');
  assert.equal(play.calls[1].url, `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${googleConfig.packageName}/purchases/subscriptionsv2/tokens/synthetic-token-ack`);
  assert.equal(play.acknowledged[0], `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${googleConfig.packageName}/purchases/subscriptions/${googleConfig.productId}/tokens/synthetic-token-ack:acknowledge`);
  play.subscription.body = googleSubscription({ lineItems: [{ productId: 'someone-elses-product', expiryTime: new Date(NOW + MONTH).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }] });
  await assert.rejects(service.verifyPurchase(randomUUID(), { store: 'google', purchaseToken: 'synthetic-token-product' }), { code: 'unknown_store_product' });
  play.subscription.status = 404;
  await assert.rejects(service.verifyPurchase(randomUUID(), { store: 'google', purchaseToken: 'synthetic-token-missing' }), { code: 'unknown_store_purchase' });
  play.subscription.status = 200;
  play.subscription.body = googleSubscription({ testPurchase: {} });
  const sandbox = await service.verifyPurchase(randomUUID(), { store: 'google', purchaseToken: 'synthetic-token-test' });
  assert.equal(sandbox.cache.environment, 'sandbox'); assert.equal(sandbox.transaction.environment, 'sandbox');
});

test('a stronger live binding shadows a weaker second purchase instead of merging or doubling', async () => {
  const { play, service } = setup(), account = randomUUID();
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_PAUSED' });
  const shadowed = await service.verifyPurchase(account, { store: 'google', purchaseToken: 'synthetic-shadow-token' });
  assert.equal(shadowed.applied, false); assert.equal(shadowed.note, 'shadowed-weaker-binding');
  assert.equal(shadowed.cache.state, 'active');
  const record = await service.cache(account, 'production');
  assert.equal(record.verifiedUntil, new Date(NOW + MONTH).toISOString());
});

test('failed Google acknowledgement never grants Pro and the same verified purchase can retry', async () => {
  for (const status of [404, 403, 500]) {
    const { store, play, service } = setup(), account = randomUUID();
    play.subscription.body = googleSubscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' });
    play.acknowledgement.status = status;
    await assert.rejects(service.verifyPurchase(account, { store: 'google', purchaseToken: 'synthetic-retry-token' }), { code: 'store_unavailable' });
    assert.equal(store.entitlements.size, 0);
    play.acknowledgement.status = 200;
    const result = await service.verifyPurchase(account, { store: 'google', purchaseToken: 'synthetic-retry-token' });
    assert.equal(result.cache.state, 'active');
    assert.equal(play.acknowledged.length, 2);
  }
});

test('tampered, future-dated, wrong-environment and wrong-product proofs are rejected; expired transactions never activate', async () => {
  const { store, service } = setup(), account = randomUUID();
  const valid = signApple(appleTransaction());
  // Flip a data-carrying character of the signature segment: the final base64url
  // character of a 64-byte signature encodes only padding bits, so changing it
  // is a no-op decode and would not test tampering at all.
  const validParts = valid.split('.');
  const tamperedSig = (validParts[2][0] === 'A' ? 'B' : 'A') + validParts[2].slice(1);
  const tampered = validParts[0] + '.' + validParts[1] + '.' + tamperedSig;
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: tampered }), { code: 'invalid_store_proof' });
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction(), { alg: 'none' }) }), { code: 'invalid_store_proof' });
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction(), { kid: 'unknown-key' }) }), { code: 'invalid_store_proof' });
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ bundleId: 'evil.app' })) }), { code: 'unknown_store_product' });
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ productId: 'evil.product' })) }), { code: 'unknown_store_product' });
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: valid, claimedEnvironment: 'sandbox' }), { code: 'wrong_store_environment' });
  await assert.rejects(service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ signedDate: NOW + 600000 })) }), { code: 'invalid_store_proof' });
  const expired = await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000555555555', transactionId: '2000000555555555', purchaseDate: NOW - MONTH, expiresDate: NOW - 1000, signedDate: NOW })) });
  assert.equal(expired.applied, false); assert.equal(expired.note, 'transition-rejected'); assert.equal(expired.cache.state, 'unknown');
  assert.equal(store.transactions.size, 1);
});

test('chain trust rejects missing anchors and malformed certificate chains', () => {
  const pinned = signApple(appleTransaction());
  assert.throws(() => verifyAppleTransaction({ signedTransaction: pinned }, appleConfig, { mode: 'chain', roots: [] }, NOW), { code: 'apple_chain_required' } as unknown as Error);
  assert.throws(() => verifyAppleTransaction({ signedTransaction: signApple(appleTransaction(), { x5c: [] }) }, appleConfig, { mode: 'chain', roots: [] }, NOW), { code: 'invalid_store_proof' } as unknown as Error);
  assert.throws(() => verifyAppleTransaction({ signedTransaction: signApple(appleTransaction(), { x5c: ['oops'] }) }, appleConfig, { mode: 'chain', roots: ['not-a-certificate'] }, NOW), { code: 'invalid_store_proof' } as unknown as Error);
});

test('replayed notifications deduplicate and out-of-order notifications converge to the store state', async () => {
  const { store, apple, service } = setup(), account = randomUUID(), original = '2000000600000000';
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: original, transactionId: '2000000600000001' })) });
  now += 1000;
  const graceTx = appleTransaction({ originalTransactionId: original, transactionId: '2000000600000002', purchaseDate: now, expiresDate: now + MONTH, signedDate: now });
  setStatus(apple, [{ status: 4, tx: graceTx, renewal: appleRenewal(original, true, now) }]);
  const id = randomUUID();
  const first = await service.appleNotification(notify({ type: 'DID_FAIL_TO_RENEW', tx: graceTx, renewal: appleRenewal(original, true, now), id, at: now, subtype: 'GRACE_PERIOD' }));
  assert.equal(first.outcome, 'applied'); assert.equal((await service.cache(account, 'production')).state, 'grace');
  assert.deepEqual(await service.appleNotification(notify({ type: 'DID_FAIL_TO_RENEW', tx: graceTx, renewal: appleRenewal(original, true, now), id, at: now, subtype: 'GRACE_PERIOD' })), { outcome: 'deduped', note: 'replay' });
  now += 1000;
  const renewedTx = appleTransaction({ originalTransactionId: original, transactionId: '2000000600000003', purchaseDate: now, expiresDate: now + MONTH, signedDate: now });
  setStatus(apple, [{ status: 1, tx: renewedTx, renewal: appleRenewal(original, true, now) }]);
  await service.appleNotification(notify({ type: 'DID_RENEW', tx: renewedTx, renewal: appleRenewal(original, true, now), at: now }));
  assert.equal((await service.cache(account, 'production')).state, 'active');
  const staleRefund = await service.appleNotification(notify({ type: 'REFUND', tx: graceTx, at: NOW + 1000 }));
  assert.equal(staleRefund.outcome, 'applied'); assert.equal((await service.cache(account, 'production')).state, 'active');
  setStatus(apple, [{ status: 4, tx: graceTx, renewal: appleRenewal(original, true, NOW + 1000) }]);
  const stale = await service.appleNotification(notify({ type: 'DID_FAIL_TO_RENEW', tx: graceTx, at: NOW + 1000, subtype: 'GRACE_PERIOD' }));
  assert.equal(stale.note, 'superseded'); assert.equal((await service.cache(account, 'production')).state, 'active');
  assert.equal([...store.events.values()].filter(e => e.applied).length, 2);
});

test('sandbox and production entitlement records never mix', async () => {
  const { apple, service } = setup(), account = randomUUID();
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000700000001', transactionId: '2000000700000001', environment: 'Sandbox' })) });
  assert.equal((await service.cache(account, 'sandbox')).state, 'active');
  assert.deepEqual(await service.cache(account, 'production'), { version: ENTITLEMENT_CACHE_VERSION, environment: 'production', verified: false, state: 'unknown', verifiedUntil: null, lastVerifiedAt: null, offlineValidUntil: null, contractVersion: 'store-verification-v1' });
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000700000002', transactionId: '2000000700000002' })) });
  const before = await service.cache(account, 'production');
  now += 1000;
  const sandboxTx = appleTransaction({ originalTransactionId: '2000000700000001', transactionId: '2000000700000003', purchaseDate: now, expiresDate: now + MONTH, signedDate: now, environment: 'Sandbox' });
  setStatus(apple, [{ status: 4, tx: sandboxTx, renewal: appleRenewal('2000000700000001', true, now, 'Sandbox') }], 'Sandbox');
  await service.appleNotification(notify({ type: 'DID_FAIL_TO_RENEW', tx: sandboxTx, renewal: appleRenewal('2000000700000001', true, now, 'Sandbox'), at: now, environment: 'Sandbox', subtype: 'GRACE_PERIOD' }));
  assert.equal((await service.cache(account, 'sandbox')).state, 'grace');
  assert.deepEqual(await service.cache(account, 'production'), before);
});

test('cross-account binding conflicts keep the first binding and recover through release and reclaim', async () => {
  const { store, apple, service } = setup(), alice = randomUUID(), bob = randomUUID();
  const proof = signApple(appleTransaction());
  const bound = await service.verifyPurchase(alice, { store: 'apple', signedTransaction: proof });
  assert.equal(bound.cache.state, 'active');
  const conflicted = await service.verifyPurchase(bob, { store: 'apple', signedTransaction: proof });
  assert.equal(conflicted.applied, false); assert.equal(conflicted.note, 'binding-conflict');
  assert.ok(conflicted.conflict?.id); assert.match(conflicted.conflict?.recovery ?? '', /already bound/);
  assert.equal(conflicted.cache.state, 'unknown');
  assert.equal((await service.cache(alice, 'production')).verifiedUntil, bound.cache.verifiedUntil);
  const retry = await service.verifyPurchase(bob, { store: 'apple', signedTransaction: proof });
  assert.equal(retry.conflict?.id, conflicted.conflict?.id); assert.equal(store.conflicts.size, 1);
  await assert.rejects(service.releaseBinding(bob, { store: 'apple', environment: 'production', transactionKey: '2000000111111111' }), { code: 'unknown_store_purchase' });
  assert.deepEqual(await service.releaseBinding(alice, { store: 'apple', environment: 'production', transactionKey: '2000000111111111' }), { released: true });
  assert.equal(store.conflicts.get(conflicted.conflict!.id)?.state, 'released');
  now += 1000;
  const reclaimed = await service.verifyPurchase(bob, { store: 'apple', signedTransaction: signApple(appleTransaction({ transactionId: '2000000123456799', purchaseDate: now, expiresDate: now + MONTH, signedDate: now })) });
  assert.equal(reclaimed.cache.state, 'active');
  assert.equal((await store.transaction('apple', 'production', '2000000111111111'))?.accountId, bob);
  const carol = randomUUID(), dave = randomUUID(), original = '2000000711111111';
  await service.verifyPurchase(carol, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: original, transactionId: '2000000711111112' })) });
  now += MONTH + 1000;
  setStatus(apple, [{ status: 2, tx: appleTransaction({ originalTransactionId: original, transactionId: '2000000711111112', purchaseDate: NOW, expiresDate: now - 1000, signedDate: now }), renewal: appleRenewal(original, true, now) }]);
  assert.equal((await service.reconcileAccount(carol, 'production')).state, 'expired');
  now += 1000;
  const seized = await service.verifyPurchase(dave, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: original, transactionId: '2000000711111113', purchaseDate: now, expiresDate: now + MONTH, signedDate: now })) });
  assert.equal(seized.cache.state, 'active');
  assert.equal((await service.cache(carol, 'production')).state, 'expired');
});

test('restore re-verifies proofs and re-establishes the same entitlement across platforms', async () => {
  const { play, service } = setup(), account = randomUUID(), other = randomUUID();
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  const same = await service.restore(account, [{ store: 'apple', signedTransaction: signApple(appleTransaction()) }]);
  assert.equal(same.length, 1); assert.equal(same[0].note, 'duplicate-transaction'); assert.equal(same[0].cache.state, 'active');
  play.subscription.body = googleSubscription();
  const cross = await service.restore(account, [{ store: 'google', purchaseToken: 'synthetic-restore-token' }]);
  assert.equal(cross[0].cache.state, 'active');
  await service.verifyPurchase(other, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000722222222', transactionId: '2000000722222222' })) });
  const foreign = await service.restore(account, [{ store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000000722222222', transactionId: '2000000722222222' })) }]);
  assert.equal(foreign[0].note, 'binding-conflict'); assert.ok(foreign[0].conflict?.id);
  assert.equal((await service.cache(account, 'production')).state, 'active');
  await assert.rejects(service.restore(account, []), { code: 'restore_invalid' });
});

test('the entitlement cache distinguishes unknown from verified-not-entitled and honors the offline window', async () => {
  const { store, apple, service } = setup(), account = randomUUID();
  assert.deepEqual(await service.cache(account, 'production'), { version: ENTITLEMENT_CACHE_VERSION, environment: 'production', verified: false, state: 'unknown', verifiedUntil: null, lastVerifiedAt: null, offlineValidUntil: null, contractVersion: 'store-verification-v1' });
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  const active = await service.cache(account, 'production');
  assert.equal(active.verified, true);
  assert.equal(active.offlineValidUntil, new Date(NOW + OFFLINE_WINDOW_MS).toISOString());
  now += MONTH + 1000;
  const projected = await service.cache(account, 'production');
  assert.equal(projected.state, 'expired');
  assert.equal((await store.entitlement(account, 'production'))?.state, 'active');
  setStatus(apple, [{ status: 2, tx: appleTransaction({ transactionId: '2000000123456789', purchaseDate: NOW, expiresDate: NOW + MONTH, signedDate: now }), renewal: appleRenewal('2000000111111111', true, now) }]);
  const expired = await service.reconcileAccount(account, 'production');
  assert.equal(expired.verified, true); assert.equal(expired.state, 'expired'); assert.equal(expired.offlineValidUntil, null);
  assert.equal(expired.verifiedUntil, new Date(NOW + MONTH).toISOString());
});

test('reconciliation converges the cache to the store and records every convergence', async () => {
  const { store, apple, play, service } = setup(), account = randomUUID(), stale = randomUUID(), fresh = randomUUID();
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  now += 1000;
  const graceTx = appleTransaction({ transactionId: '2000000800000002', purchaseDate: now, expiresDate: now + MONTH, signedDate: now });
  setStatus(apple, [{ status: 4, tx: graceTx, renewal: appleRenewal('2000000111111111', true, now) }]);
  assert.equal((await service.reconcileAccount(account, 'production')).state, 'grace');
  assert.ok(store.reconciliations.some(r => r.accountId === account && r.from === 'active' && r.to === 'grace' && r.reason.startsWith('notification:')));
  play.subscription.body = googleSubscription();
  await service.verifyPurchase(stale, { store: 'google', purchaseToken: 'synthetic-stale-token' });
  now += 25 * 3600000;
  await service.verifyPurchase(fresh, { store: 'google', purchaseToken: 'synthetic-fresh-token' });
  play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }, NOW);
  const callsBefore = play.calls.length;
  assert.equal(await service.reconcileStale(24 * 3600000, 10), 2);
  assert.ok(play.calls.length > callsBefore);
  assert.equal((await service.cache(stale, 'production')).state, 'expired');
  assert.equal((await service.cache(fresh, 'production')).state, 'active');
});

test('RTDN test deliveries and unknown purchases record without touching entitlements', async () => {
  const { store, service } = setup();
  const bearer = (token: string) => `Bearer ${token}`;
  const testPush = rtdnBody('synthetic-message-test', { version: '1.0', packageName: googleConfig.packageName, eventTimeMillis: String(NOW), testNotification: { version: '1.0' } });
  assert.deepEqual(await service.rtdnPush(testPush, bearer(signOidc())), { outcome: 'recorded', note: 'test-delivery' });
  assert.equal(store.entitlements.size, 0);
  const unknown = rtdnBody('synthetic-message-unknown', rtdnSubscription('synthetic-unknown-token', 2));
  assert.deepEqual(await service.rtdnPush(unknown, bearer(signOidc())), { outcome: 'recorded', note: 'unknown-transaction' });
  assert.equal(store.entitlements.size, 0);
  assert.deepEqual(await service.rtdnPush(unknown, bearer(signOidc())), { outcome: 'deduped', note: 'replay' });
  // Flip the FIRST signature character: the last base64url character of an
  // ES256 signature carries padding bits, so tampering there often leaves the
  // decoded signature unchanged (this assertion flaked ~40% of runs).
  const oidcParts = signOidc().split('.');
  oidcParts[2] = (oidcParts[2][0] === 'A' ? 'B' : 'A') + oidcParts[2].slice(1);
  const badOidc = oidcParts.join('.');
  await assert.rejects(service.rtdnPush(unknown, bearer(badOidc)), { code: 'notification_rejected' });
  await assert.rejects(service.rtdnPush(unknown, undefined), { code: 'notification_rejected' });
  for (const claims of [
    { email: 'other@synthetic.iam.gserviceaccount.com' }, { email: undefined },
    { email_verified: false }, { email_verified: 'true' }, { email_verified: undefined },
    { aud: 'https://wrong-audience.example' }, { exp: Math.floor(NOW / 1000) },
  ]) await assert.rejects(service.rtdnPush(unknown, bearer(signOidc(claims))), { code: 'notification_rejected' });
  const wrongTestPackage = rtdnBody('synthetic-wrong-test-package', { packageName: 'evil.app', testNotification: {} });
  await assert.rejects(service.rtdnPush(wrongTestPackage, bearer(signOidc())), { code: 'notification_rejected' });
  const wrongPackage = rtdnBody('synthetic-message-package', { ...rtdnSubscription('synthetic-unknown-token', 2), packageName: 'evil.app' });
  await assert.rejects(service.rtdnPush(wrongPackage, bearer(signOidc())), { code: 'notification_rejected' });
  const wrongProduct = rtdnBody('synthetic-message-product', { version: '1.0', packageName: googleConfig.packageName, eventTimeMillis: String(NOW), subscriptionNotification: { version: '1.0', notificationType: 2, purchaseToken: 'synthetic-unknown-token', subscriptionId: 'evil-product' } });
  await assert.rejects(service.rtdnPush(wrongProduct, bearer(signOidc())), { code: 'notification_rejected' });
});

test('RTDN revocations and renewals reconcile against the Play API, including sandbox bindings', async () => {
  const { play, service } = setup(), account = randomUUID(), sandboxAccount = randomUUID();
  play.subscription.body = googleSubscription();
  await service.verifyPurchase(account, { store: 'google', purchaseToken: 'synthetic-revoke-token' });
  now += 1000;
  play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }, NOW);
  const revoked = await service.rtdnPush(rtdnBody('synthetic-message-revoke', rtdnSubscription('synthetic-revoke-token', 12, now)), `Bearer ${signOidc({}, now)}`);
  assert.equal(revoked.outcome, 'applied');
  assert.equal((await service.cache(account, 'production')).state, 'revoked');
  play.subscription.body = googleSubscription({ testPurchase: {} });
  await service.verifyPurchase(sandboxAccount, { store: 'google', purchaseToken: 'synthetic-sandbox-token' });
  now += 1000;
  const renewed = await service.rtdnPush(rtdnBody('synthetic-message-sandbox', rtdnSubscription('synthetic-sandbox-token', 2, now)), `Bearer ${signOidc({}, now)}`);
  assert.equal(renewed.outcome, 'applied'); assert.equal(renewed.note, 'refreshed');
  assert.equal((await service.cache(sandboxAccount, 'sandbox')).state, 'active');
  assert.equal((await service.cache(sandboxAccount, 'production')).state, 'unknown');
});

test('unsupported store states fail closed without granting entitlement', async () => {
  const { store, apple, play, service } = setup(), appleAccount = randomUUID(), googleAccount = randomUUID();
  await service.verifyPurchase(appleAccount, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  now += 1000;
  const oddTx = appleTransaction({ transactionId: '2000000900000002', purchaseDate: now, expiresDate: now + MONTH, signedDate: now });
  setStatus(apple, [{ status: 99, tx: oddTx, renewal: appleRenewal('2000000111111111', true, now) }]);
  const oddId = randomUUID();
  await assert.rejects(service.appleNotification(notify({ type: 'DID_RENEW', tx: oddTx, renewal: appleRenewal('2000000111111111', true, now), id: oddId, at: now })), { code: 'unsupported_store_state' });
  assert.equal((await service.cache(appleAccount, 'production')).state, 'active');
  assert.equal(await store.eventSeen(`apple:${oddId}`), false);
  play.subscription.body = googleSubscription();
  await service.verifyPurchase(googleAccount, { store: 'google', purchaseToken: 'synthetic-odd-token' });
  now += 1000;
  play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_FUTURE' }, NOW);
  await assert.rejects(service.rtdnPush(rtdnBody('synthetic-message-odd', rtdnSubscription('synthetic-odd-token', 2, now)), `Bearer ${signOidc({}, now)}`), { code: 'unsupported_store_state' });
  assert.equal((await service.cache(googleAccount, 'production')).state, 'active');
});

test('store API clients sign requests and select the environment host explicitly', async () => {
  const { apple, play } = setup();
  apple.status.body = { environment: 'Sandbox', data: [] };
  await apple.api.subscriptionStatus('2000000111111111', 'sandbox');
  assert.match(apple.calls[0].url, /^https:\/\/api\.storekit-sandbox\.itunes\.apple\.com\/inApps\/v1\/subscriptions\/2000000111111111$/);
  await assert.rejects(apple.api.subscriptionStatus('../escape', 'production'), { code: 'invalid_store_proof' });
  play.subscription.body = googleSubscription();
  await play.api.subscription('synthetic-client-token', googleConfig.packageName);
  assert.equal(play.calls[0].url, 'https://oauth2.googleapis.com/token'); assert.equal(play.calls[0].method, 'POST');
  assert.ok(play.calls[1].url.includes(encodeURIComponent(googleConfig.packageName)));
  await play.api.acknowledge('synthetic-client-token', googleConfig.packageName, googleConfig.productId);
  assert.equal(play.acknowledged[0], `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${googleConfig.packageName}/purchases/subscriptions/${googleConfig.productId}/tokens/synthetic-client-token:acknowledge`);
  const failing = new PlayDeveloperApi({ clientEmail: 'synthetic@test.iam.gserviceaccount.com', privateKey: 'unused' }, async url => url === 'https://oauth2.googleapis.com/token' ? { status: 500, body: {} } : { status: 200, body: googleSubscription() });
  await assert.rejects(failing.subscription('synthetic-client-token', googleConfig.packageName), { code: 'store_unavailable' });
});

test('pure state derivations cover Apple statuses, Play states and illegal transitions', () => {
  assert.equal(deriveAppleState({ status: 1, revoked: false, upgraded: false, expiresAt: NOW + MONTH, autoRenew: true }, NOW), 'active');
  assert.equal(deriveAppleState({ status: 1, revoked: false, upgraded: false, expiresAt: NOW + MONTH, autoRenew: false }, NOW), 'cancelled');
  assert.equal(deriveAppleState({ status: 2, revoked: false, upgraded: false, expiresAt: NOW, autoRenew: true }, NOW), 'expired');
  assert.equal(deriveAppleState({ status: 3, revoked: false, upgraded: false, expiresAt: NOW + MONTH, autoRenew: true }, NOW), 'retry');
  assert.equal(deriveAppleState({ status: 4, revoked: false, upgraded: false, expiresAt: NOW + MONTH, autoRenew: true }, NOW), 'grace');
  assert.equal(deriveAppleState({ status: 5, revoked: false, upgraded: false, expiresAt: NOW + MONTH, autoRenew: true }, NOW), 'revoked');
  assert.equal(deriveAppleState({ status: 1, revoked: false, upgraded: true, expiresAt: NOW + MONTH, autoRenew: true }, NOW), 'expired');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_ACTIVE', true, NOW + MONTH, NOW), 'active');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_ACTIVE', false, NOW + MONTH, NOW), 'cancelled');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_CANCELED', false, NOW + MONTH, NOW), 'cancelled');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_CANCELED', false, NOW - 1, NOW), 'expired');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_IN_GRACE_PERIOD', true, NOW + MONTH, NOW), 'grace');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_ON_HOLD', true, NOW + MONTH, NOW), 'retry');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_PAUSED', true, NOW + MONTH, NOW), 'paused');
  assert.equal(deriveGoogleState('SUBSCRIPTION_STATE_EXPIRED', false, NOW - 1, NOW), 'expired');
  const context = { accountId: randomUUID(), environment: 'production' as const, store: 'apple' as const };
  const verdict = (state: 'grace', eventAt: number) => ({ state, expiresAt: NOW + MONTH, autoRenew: true as boolean | null, eventAt, evidenceRef: 'e', reason: 'test', transactionStore: 'apple' as const, transactionKey: 'k' });
  const illegal = transitionEntitlement(undefined, verdict('grace', NOW), context, NOW);
  assert.equal(illegal.applied, false); assert.equal(illegal.note, 'transition-rejected');
  const active = transitionEntitlement(undefined, { ...verdict('grace', NOW), state: 'active' }, context, NOW);
  assert.equal(active.applied, true);
  const stale = transitionEntitlement(active.record, verdict('grace', NOW - 1), context, NOW);
  assert.equal(stale.applied, false); assert.equal(stale.note, 'superseded');
  assert.equal(projectCache(undefined, 'sandbox', NOW).state, 'unknown');
  assert.equal(applePublicPem.includes('BEGIN PUBLIC KEY'), true);
});

test('live entitlement caches validate against the shared entitlement-cache contract', async () => {
  const { service } = setup(), account = randomUUID();
  const ajv = new Ajv2020({ strict: true, strictTypes: false }); addFormats.default(ajv);
  const root = new URL('../../contracts/', import.meta.url);
  const validate = ajv.compile(JSON.parse(await readFile(new URL('entitlement-cache.schema.json', root), 'utf8')));
  assert.ok(validate(JSON.parse(await readFile(new URL('fixtures/entitlement-cache-v1.json', root), 'utf8'))), JSON.stringify(validate.errors));
  assert.ok(validate(await service.cache(account, 'production')), JSON.stringify(validate.errors));
  await service.verifyPurchase(account, { store: 'apple', signedTransaction: signApple(appleTransaction()) });
  assert.ok(validate(await service.cache(account, 'production')), JSON.stringify(validate.errors));
  assert.equal(validate({ version: 'entitlement-cache-v1', environment: 'production', verified: false, state: 'active', verifiedUntil: null, lastVerifiedAt: null, offlineValidUntil: null, contractVersion: 'store-verification-v1' }), false);
});
