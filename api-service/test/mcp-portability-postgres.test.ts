import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { PostgresMcpPortabilityStore } from '../src/mcp-portability.ts';

// Real-Postgres round trip for the portability store (issue #114, M5),
// gated exactly like the other *-postgres suites: skipped without
// TEST_DATABASE_URL, required in CI via REQUIRE_DB_TESTS.
const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };

test('mcp portability postgres: exports and previews round-trip with owner isolation', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const pool = new Pool({ connectionString: source, max: 2 });
  try {
    await migrate(pool);
    const userId = (await pool.query(
      'INSERT INTO auth.users(id,email,created_at) VALUES($1,$2,$3) RETURNING id',
      [randomUUID(), `mcp-port-${randomUUID()}@example.invalid`, Date.now()])).rows[0].id as string;
    const store = new PostgresMcpPortabilityStore(pool);
    const now = Date.now();
    const exportId = randomUUID();
    await store.createExport({ id: exportId, userId, kind: 'archive', scope: { type: 'library' }, payload: { archive: { schemaVersion: 10 } }, counts: { cooks: 2 }, createdAt: now, expiresAt: now + 3600_000 });
    const got = await store.exportFor(userId, exportId);
    assert.equal(got?.kind, 'archive');
    assert.deepEqual(got?.counts, { cooks: 2 });
    assert.equal(got?.createdAt, now);
    assert.equal(await store.exportFor(randomUUID(), exportId), undefined, 'another account sees nothing');

    const previewId = randomUUID();
    await store.createPreview({
      id: previewId, userId, grantId: 'g1', contentHash: 'hash-1', baseVersion: 7,
      rows: [{ recordType: 'cooks', recordId: 'c1', payload: { id: 'c1' }, assetIds: [] }],
      summary: { totalRows: 1 }, createdAt: now, expiresAt: now + 900_000, consumedAt: null,
    });
    const preview = await store.previewFor(userId, previewId);
    assert.equal(preview?.baseVersion, 7);
    assert.equal(preview?.rows[0].recordId, 'c1');
    assert.equal(await store.consumePreview(userId, previewId, now + 1), true);
    assert.equal(await store.consumePreview(userId, previewId, now + 2), false, 'a preview is single-use');
    assert.equal((await store.previewFor(userId, previewId))?.consumedAt, now + 1);
  } finally { await pool.end(); }
});
