import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { migrate } from '../src/migrate.ts';
import { PostgresMcpDeviceStore } from '../src/mcp-device.ts';

// Real-Postgres round trip for the device command store (issue #114,
// §7), gated exactly like the other *-postgres suites: skipped without
// TEST_DATABASE_URL, required in CI via REQUIRE_DB_TESTS.
const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };

test('mcp device postgres: registration, claim, receipt and unregister semantics', gate, async () => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const base = new URL(source); assert.match(base.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const pool = new Pool({ connectionString: source, max: 2 });
  try {
    await migrate(pool);
    const userId = (await pool.query(
      'INSERT INTO auth.users(id,email,created_at) VALUES($1,$2,$3) RETURNING id',
      [randomUUID(), `mcp-dev-${randomUUID()}@example.invalid`, Date.now()])).rows[0].id as string;
    const store = new PostgresMcpDeviceStore(pool);
    const now = Date.now();
    const deviceId = randomUUID();
    const device = await store.upsertDevice(userId, {
      deviceId, name: 'Pixel', platform: 'android', actionClasses: ['timer', 'reminder'],
      lastSeenAt: now, createdAt: now, updatedAt: now,
    });
    assert.equal(device.name, 'Pixel');
    assert.deepEqual(device.actionClasses, ['timer', 'reminder']);
    assert.equal((await store.listDevices(userId)).length, 1);
    assert.equal((await store.listDevices(randomUUID())).length, 0, 'another account sees no devices');

    const updated = await store.upsertDevice(userId, {
      deviceId, name: 'Pixel 9', platform: 'android', actionClasses: ['timer'],
      lastSeenAt: now + 1, createdAt: now + 1, updatedAt: now + 1,
    });
    assert.equal(updated.createdAt, now, 're-registration keeps the original registration time');
    assert.deepEqual(updated.actionClasses, ['timer'], 'opt-in classes are replaced wholesale');

    const commandId = randomUUID();
    await store.createCommand({
      id: commandId, userId, deviceId, grantId: 'g1', action: 'timer.start',
      params: { label: 'Rest', durationMinutes: 30 }, state: 'queued_for_device',
      createdAt: now, expiresAt: now + 900_000, claimedAt: null, claimedBy: null, resolvedAt: null, receipt: null,
    });
    assert.equal((await store.claim(userId, deviceId, ['reminder'], now + 2, 10)).length, 0, 'class filter applies in SQL too');
    const claimed = await store.claim(userId, deviceId, ['timer'], now + 2, 10);
    assert.equal(claimed.length, 1);
    assert.equal(claimed[0].claimedBy, deviceId);
    assert.equal((await store.claim(userId, deviceId, ['timer'], now + 3, 10)).length, 0, 'a fresh claim is not re-issued');

    assert.equal(await store.resolve(userId, randomUUID(), commandId, { outcome: 'applied' }, now + 4), false, 'only the claiming device resolves');
    assert.equal(await store.resolve(userId, deviceId, commandId, { outcome: 'bogus' }, now + 4), false, 'outcomes are a closed set');
    assert.equal(await store.resolve(userId, deviceId, commandId, { outcome: 'applied', at: now + 4 }, now + 4), true);
    const resolved = await store.commandFor(userId, commandId);
    assert.equal(resolved?.state, 'applied');
    assert.equal(await store.commandFor(randomUUID(), commandId), undefined, 'commands are owner-isolated');

    const doomedId = randomUUID();
    await store.createCommand({
      id: doomedId, userId, deviceId, grantId: 'g1', action: 'timer.start',
      params: { label: 'Nap', durationMinutes: 10 }, state: 'queued_for_device',
      createdAt: now, expiresAt: now + 900_000, claimedAt: null, claimedBy: null, resolvedAt: null, receipt: null,
    });
    assert.equal(await store.removeDevice(userId, deviceId, now + 5), true);
    assert.equal((await store.commandFor(userId, doomedId))?.state, 'canceled', 'unregister cancels queued work');
    assert.equal((await store.listDevices(userId)).length, 0);
  } finally { await pool.end(); }
});
