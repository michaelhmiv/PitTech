import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { FileStorage } from '../src/storage.ts';
import { SyncEncryption } from '../src/sync-encryption.ts';
import { PrivateSnapshots, type SyncStorageLimits } from '../src/sync-snapshots.ts';
import { privateLibraryEnvelope } from '../src/sync-projection.ts';
import { AuthSecrets, credential } from '../src/auth-credentials.ts';
import { McpGrants, PostgresMcpGrantStore, type McpPrincipal } from '../src/mcp-grants.ts';
import { PrivateLibraryService } from '../src/mcp-library.ts';

// Real-Postgres proof for the MCP command spine (issue #114, M3): the
// command layer writes through the actual PrivateSnapshots CAS engine
// and the actual production projection validator — a constructed
// envelope that violates the contract fails here, not in production.
// Gated like the other *-postgres suites.
const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
const KEY = '123e4567-e89b-42d3-a456-426614174001';

test('mcp writes against real Postgres snapshots + production validator', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const url = new URL(source); assert.match(url.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const control = new Pool({ connectionString: source });
  const database = 'pittech_test_mcpwrites_' + randomUUID().replaceAll('-', '');
  await control.query(`CREATE DATABASE ${database}`); url.pathname = '/' + database;
  const connectionString = url.toString();
  const admin = new Pool({ connectionString, max: 4 });
  const syncPool = new Pool({ connectionString, max: 4, options: '-c role=pittech_private_sync_runtime' });
  const authPool = new Pool({ connectionString, max: 4, options: '-c role=pittech_auth_runtime' });
  const root = await mkdtemp(join(tmpdir(), 'pittech-mcp-writes-'));
  const now = Date.UTC(2026, 9, 23, 12);
  try {
    await migrate(admin);
    for (const role of ['auth', 'lifecycle', 'private-sync']) await admin.query(await readFile(new URL(`../operations/${role}-runtime-role.sql`, import.meta.url), 'utf8'));
    const userId = randomUUID();
    await admin.query('INSERT INTO auth.users VALUES($1,$2,$3)', [userId, userId + '@example.invalid', now]);
    const storage = new FileStorage(root);
    const encryption = new SyncEncryption('synthetic', { synthetic: randomBytes(32).toString('base64url') });
    const limits: SyncStorageLimits = { ownerBytes: 1024 ** 2, globalBytes: 8 * 1024 ** 2, dailyBytes: 16 * 1024 ** 2, dailyUploads: 100, ownerOperations: 100 };
    const snapshots = new PrivateSnapshots(syncPool, storage, encryption, privateLibraryEnvelope, limits, () => now);
    const grants = new McpGrants(new PostgresMcpGrantStore(authPool), new AuthSecrets(credential(), credential()), { state: async () => 'active' }, () => now);
    const library = new PrivateLibraryService(grants, snapshots, { writer: snapshots, writesEnabled: true, clock: () => now });
    const principal: McpPrincipal = { grantId: 'g', userId, scopes: ['library:read', 'library:write', 'library:delete'] };

    const created = await library.createMeal(principal, { title: 'CI brisket', smokerName: 'Offset', dishes: [{ name: 'Flat', foodType: 'beef', weightValue: 5, weightUnit: 'lb' }], idempotencyKey: KEY });
    assert.equal((created.receipt as { snapshotVersion: number }).snapshotVersion, 1);
    const mealId = created.mealId as string;

    // The stored snapshot decrypts to exactly one cook + dish + lifecycle,
    // stamped by the MCP writer — and it passed the production validator.
    const read = await snapshots.read(userId);
    assert.equal(read.version, 1);
    assert.deepEqual(read.envelope?.records.map(r => r.recordType).sort(), ['cooks', 'dishes', 'lifecycles']);
    assert.ok(read.envelope?.records.every(r => r.version.clientId === '6d435057-0000-4000-8000-000000000001'));

    // Receipt-first replay against the real operations table.
    const replay = await library.createMeal(principal, { title: 'CI brisket', smokerName: 'Offset', dishes: [{ name: 'Flat', foodType: 'beef', weightValue: 5, weightUnit: 'lb' }], idempotencyKey: KEY });
    assert.equal(replay.replayed, true);
    assert.equal((await snapshots.read(userId)).version, 1);

    const transitioned = await library.transitionMeal(principal, { mealId, toStatus: 'completed' });
    assert.equal((transitioned.receipt as { snapshotVersion: number }).snapshotVersion, 2);
    const after = await library.getMeal(principal, { mealId });
    assert.equal((after.meal as { status: string }).status, 'completed');

    const deleted = await library.deleteMeal(principal, { mealId });
    assert.ok((deleted.tombstonedRecords as number) >= 3);
    assert.equal((await snapshots.read(userId)).version, 3);
  } finally {
    await Promise.all([admin.end(), syncPool.end(), authPool.end(), control.end()]);
    await rm(root, { recursive: true, force: true });
    const cleanup = new Pool({ connectionString: source });
    await cleanup.query(`DROP DATABASE ${database}`); await cleanup.end();
  }
});
