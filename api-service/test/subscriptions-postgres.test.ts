import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { Subscriptions, PostgresBillingStore } from '../src/subscriptions.ts';
import {
  NOW, MONTH, appleConfig, googleConfig, appleTrust, oidcKeySet, oidcAudience,
  signApple, appleTransaction, appleRenewal, appleStatusBody, appleNotificationBody,
  stubAppleApi, stubPlayApi, signOidc, googleSubscription, rtdnBody, rtdnSubscription,
} from './billing-fixtures.ts';

const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
test('real Postgres billing: verified bindings dedupe, conflicts persist with recovery, notifications reconcile, environments isolate, runtime role stays narrow', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source); const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const control = new Pool({ connectionString: source }), database = 'pittech_test_billing_' + randomUUID().replaceAll('-', '');
  await control.query(`CREATE DATABASE ${database}`); base.pathname = '/' + database;
  const connectionString = base.toString(), admin = new Pool({ connectionString, max: 5 });
  const billingPool = new Pool({ connectionString, max: 5, options: '-c role=pittech_billing_runtime' });
  let now = NOW;
  try {
    await migrate(admin); await migrate(admin);
    const grant = await readFile(new URL('../operations/billing-runtime-role.sql', import.meta.url), 'utf8');
    await admin.query(grant); await admin.query(grant);
    for (const sql of ['CREATE TABLE billing.forbidden(id integer)', 'TRUNCATE billing.entitlements', 'SELECT 1 FROM relay.schema_migrations', 'SELECT 1 FROM auth.users', 'SELECT 1 FROM publications.items']) await assert.rejects(billingPool.query(sql), { code: '42501' });
    const apple = stubAppleApi(), play = stubPlayApi();
    const service = new Subscriptions(new PostgresBillingStore(billingPool), {
      apple: { config: appleConfig, trust: appleTrust, api: apple.api },
      google: { config: googleConfig, api: play.api, oidcKeys: oidcKeySet, oidcAudience },
    }, () => now);
    const alice = randomUUID(), bob = randomUUID(), original = '2000001000000000';
    const proof = signApple(appleTransaction({ originalTransactionId: original, transactionId: '2000001000000001' }));
    const bound = await service.verifyPurchase(alice, { store: 'apple', signedTransaction: proof });
    assert.equal(bound.cache.state, 'active');
    const again = await service.verifyPurchase(alice, { store: 'apple', signedTransaction: proof });
    assert.equal(again.note, 'duplicate-transaction');
    assert.equal((await admin.query('SELECT * FROM billing.transactions')).rowCount, 1);
    const conflicted = await service.verifyPurchase(bob, { store: 'apple', signedTransaction: proof });
    assert.equal(conflicted.note, 'binding-conflict'); assert.ok(conflicted.conflict?.id);
    assert.equal((await admin.query("SELECT * FROM billing.binding_conflicts WHERE state='open'")).rowCount, 1);
    assert.equal((await service.cache(bob, 'production')).state, 'unknown');
    await service.releaseBinding(alice, { store: 'apple', environment: 'production', transactionKey: original });
    now += 1000;
    const reclaimed = await service.verifyPurchase(bob, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: original, transactionId: '2000001000000002', purchaseDate: now, expiresDate: now + MONTH, signedDate: now })) });
    assert.equal(reclaimed.cache.state, 'active');
    assert.equal((await admin.query("SELECT state FROM billing.binding_conflicts WHERE id=$1", [conflicted.conflict!.id])).rows[0].state, 'released');
    now += 1000;
    const graceTx = appleTransaction({ originalTransactionId: original, transactionId: '2000001000000003', purchaseDate: now, expiresDate: now + MONTH, signedDate: now });
    apple.status.body = appleStatusBody([{ status: 4, transaction: signApple(graceTx), renewal: signApple(appleRenewal(original, true, now)) }]);
    const id = randomUUID();
    const outer = signApple(appleNotificationBody('DID_FAIL_TO_RENEW', signApple(graceTx), id, now, 'Production', signApple(appleRenewal(original, true, now)), 'GRACE_PERIOD'));
    assert.equal((await service.appleNotification(outer)).outcome, 'applied');
    assert.equal((await service.cache(bob, 'production')).state, 'grace');
    assert.deepEqual(await service.appleNotification(outer), { outcome: 'deduped', note: 'replay' });
    assert.equal((await admin.query("SELECT * FROM billing.store_events WHERE applied")).rowCount, 1);
    await service.verifyPurchase(alice, { store: 'apple', signedTransaction: signApple(appleTransaction({ originalTransactionId: '2000001000000009', transactionId: '2000001000000009', environment: 'Sandbox' })) });
    assert.equal((await service.cache(alice, 'sandbox')).state, 'active');
    assert.equal((await service.cache(alice, 'production')).state, 'expired');
    assert.equal((await admin.query("SELECT * FROM billing.entitlements WHERE environment='sandbox'")).rowCount, 1);
    play.subscription.body = googleSubscription();
    const google = await service.verifyPurchase(alice, { store: 'google', purchaseToken: 'synthetic-pg-token' });
    assert.equal(google.cache.state, 'active'); assert.equal(play.acknowledged.length, 0);
    now += 1000;
    play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' }, NOW);
    const pushed = await service.rtdnPush(rtdnBody('synthetic-pg-message', rtdnSubscription('synthetic-pg-token', 6, now)), `Bearer ${signOidc({}, now)}`);
    assert.equal(pushed.outcome, 'applied');
    assert.equal((await service.cache(alice, 'production')).state, 'grace');
    assert.deepEqual(await service.rtdnPush(rtdnBody('synthetic-pg-message', rtdnSubscription('synthetic-pg-token', 6, now)), `Bearer ${signOidc({}, now)}`), { outcome: 'deduped', note: 'replay' });
    assert.ok((await admin.query('SELECT * FROM billing.reconciliation_events')).rowCount! >= 6);
    now += 25 * 3600000;
    play.subscription.body = googleSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }, NOW);
    apple.status.body = appleStatusBody([{ status: 2, transaction: signApple(appleTransaction({ originalTransactionId: original, transactionId: '2000001000000003', purchaseDate: NOW, expiresDate: NOW + MONTH, signedDate: now })), renewal: signApple(appleRenewal(original, true, now)) }]);
    // The sandbox binding skips this sweep: the stub serves production status
    // only, and the sandbox record keeps its verified state for the next run.
    assert.equal(await service.reconcileStale(24 * 3600000, 10), 2);
    assert.equal((await service.cache(alice, 'production')).state, 'expired');
    assert.equal((await service.cache(bob, 'production')).state, 'expired');
  } finally {
    await Promise.all([admin.end(), billingPool.end()]);
    await control.query(`DROP DATABASE ${database}`); await control.end();
  }
});
