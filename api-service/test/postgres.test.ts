import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { PostgresRepository, type Pack } from '../src/repository.ts';
import { LIMITS, ownerHash, newCode } from '../src/core.ts';
import { budgets } from './helpers.ts';

test('real Postgres: repeatable migrations, concurrent budgets/rates, independent pools, owner operations and cleanup leases', { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' }, async () => {
  const url = process.env.TEST_DATABASE_URL;
  assert.ok(url, 'TEST_DATABASE_URL is required in the backend Actions gate');
  assert.match(new URL(url).pathname, /^\/pittech_test[a-z_\d]*$/i, 'Use a dedicated disposable test database');
  const a = new Pool({ connectionString: url, max: 15 }), b = new Pool({ connectionString: url, max: 5 });
  try {
    await migrate(a); await migrate(a); await a.query('DELETE FROM relay.packs'); await a.query('DELETE FROM relay.daily_budgets'); await a.query('DELETE FROM relay.rate_buckets');
    const repo = new PostgresRepository(a), other = new PostgresRepository(b), owner = randomBytes(32).toString('base64url'), now = Date.now();
    const candidate = (): Pack => ({ code: newCode(), objectKey: `relay/${randomUUID()}.pittech`, ownerHash: ownerHash(owner), idempotencyKey: randomUUID(),
      sha256: 'a'.repeat(64), bytes: 100, kind: 'recipe', dishes: 1, createdAt: now, expiresAt: now + LIMITS.life, leaseUntil: 0, state: 'uploading' });
    const outcomes = await Promise.allSettled(Array.from({ length: 12 }, () => repo.reserve(candidate(), { ...budgets, storageBytes: 100 })));
    assert.equal(outcomes.filter(r => r.status === 'fulfilled').length, 1);
    const winner = outcomes.find(r => r.status === 'fulfilled') as PromiseFulfilledResult<Awaited<ReturnType<typeof repo.reserve>>>;
    const pack = winner.value!.pack; await repo.activate(pack.code);
    assert.equal((await other.get(pack.code))!.state, 'active');
    assert.equal((await other.reserve(pack, budgets))!.existing, true);
    assert.equal(await other.renew(pack.code, ownerHash(randomBytes(32).toString('base64url')), now), undefined);
    assert.equal(await other.revoke(pack.code, ownerHash(randomBytes(32).toString('base64url')), now), false);
    assert.equal((await other.renew(pack.code, ownerHash(owner), now + 1000))!.expiresAt, now + 1000 + LIMITS.life);
    const hits = await Promise.all(Array.from({ length: 130 }, () => repo.rate('synthetic-key', 120, now + 3600000))); assert.equal(hits.filter(Boolean).length, 120);
    assert.equal(await repo.revoke(pack.code, ownerHash(owner), now), true);
    const claim = await repo.claim(now, 1); assert.equal(claim.length, 1); assert.equal((await other.claim(now, 1)).length, 0);
    // A process that died before deleting its object leaves the metadata and reserved bytes until lease expiry.
    assert.equal((await other.get(pack.code))!.state, 'deleting');
    const reclaimed = await other.claim(now + 300001, 1); assert.equal(reclaimed.length, 1); await other.deleted(reclaimed[0]);
    assert.equal(await repo.get(pack.code), undefined);
    await assert.rejects(repo.reserve(candidate(), { ...budgets, dailyUploads: 1 }), { code: 'relay_budget_exhausted' });
  } finally { await Promise.all([a.end(), b.end()]); }
});
