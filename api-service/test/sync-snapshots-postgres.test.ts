import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash as createHashForPhoto } from 'node:crypto';
import { readFile, writeFile, mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Pool } from 'pg';
import sharp from 'sharp';
import { PrivatePhotos, PRIVATE_PHOTO_CHUNK_BYTES } from '../src/sync-photos.ts';
import { migrate } from '../src/migrate.ts';
import { FileStorage, type Storage } from '../src/storage.ts';
import { SyncEncryption } from '../src/sync-encryption.ts';
import { PrivateSnapshots, type SyncStorageLimits } from '../src/sync-snapshots.ts';
import { syncEnvelope, type SyncEnvelope } from '../src/sync-envelope.ts';
import { stable } from '../src/pack.ts';
import { AccountDeletion } from '../src/account-deletion.ts';
import { PrivateSyncPurger } from '../src/sync-purge.ts';
import { AuthSecrets, credential, proofHash } from '../src/auth-credentials.ts';
const gate = { skip: !process.env.TEST_DATABASE_URL && process.env.REQUIRE_DB_TESTS !== '1' };
const device = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
function snapshot(text: string, baseVersion = 0, counter = 1): SyncEnvelope {
  return { contractVersion: 1, baseVersion, mutationId: randomUUID(), clientId: device, conflicts: [], records: [{ recordType: 'events', recordId: 'synthetic-note', version: { counter, clientId: device }, deleted: false, payload: { title: text }, assetIds: [] }] };
}
function latch() { let release!: () => void; const promise = new Promise<void>((resolve, reject) => { const timer = setTimeout(() => reject(Error('Synthetic race barrier timed out')), 15000); release = () => { clearTimeout(timer); resolve(); }; }); return { promise, release }; }

test('real Postgres encrypted snapshots: exact retries, compare-and-swap, quotas, reads and deletion races', gate, async t => {
  const source = process.env.TEST_DATABASE_URL; assert.ok(source);
  const url = new URL(source); assert.match(url.pathname, /^\/pittech_test[a-z_\d]*$/i);
  const control = new Pool({ connectionString: source }), database = 'pittech_test_snapshots_' + randomUUID().replaceAll('-', '');
  await control.query(`CREATE DATABASE ${database}`); url.pathname = '/' + database;
  const connectionString = url.toString(), admin = new Pool({ connectionString, max: 5 });
  const pool = new Pool({ connectionString, max: 8, options: '-c role=pittech_private_sync_runtime' });
  const life = new Pool({ connectionString, max: 5, options: '-c role=pittech_lifecycle_runtime' });
  const root = await mkdtemp(join(tmpdir(), 'pittech-snapshot-test-')), storage = new FileStorage(root);
  const encryption = new SyncEncryption('synthetic', { synthetic: randomBytes(32).toString('base64url') });
  const limits: SyncStorageLimits = { ownerBytes: 1024 ** 2, globalBytes: 8 * 1024 ** 2, dailyBytes: 16 * 1024 ** 2, dailyUploads: 100, ownerOperations: 100 };
  let now = Date.UTC(2026, 9, 16);
  // This synthetic metadata projector is deliberately not a production library validator.
  const create = (adapter: Storage = storage, caps = limits) => new PrivateSnapshots(pool, adapter, encryption, syncEnvelope, caps, () => now);
  const freshOwner = async () => { const id = randomUUID(); await admin.query('INSERT INTO auth.users VALUES($1,$2,$3)', [id, id + '@example.com', now]); return id; };
  const deletion = (adapter: Storage = storage) => { const secrets = new AuthSecrets(credential(), credential()); return new AccountDeletion(life, secrets, adapter, { send: async () => {} }, email => secrets.hash('email', email), () => now, new PrivateSyncPurger(life, adapter, () => now)); };
  const requestDelete = (service: AccountDeletion, id: string) => service.request({ id, email: 'ignored@example.com' }, { operationId: randomUUID(), proofChallenge: proofHash(credential()), confirm: 'delete-account', policyVersion: 'account-deletion-v1' });
  try {
    await migrate(admin); await migrate(admin);
    for (const role of ['lifecycle', 'private-sync']) await admin.query(await readFile(new URL(`../operations/${role}-runtime-role.sql`, import.meta.url), 'utf8'));
    await t.test('empty reads are owner scoped; canonical snapshots persist encrypted and exact replay does not restore an old head', async () => {
      const owner = await freshOwner(), other = await freshOwner(), api = create(), first = snapshot('Synthetic private journal alpha');
      assert.deepEqual(await api.read(owner), { version: 0, envelope: null });
      const receipt = await api.put(owner, first); assert.equal(receipt.version, 1);
      assert.deepEqual(await api.read(owner), { version: 1, envelope: first });
      assert.deepEqual(await api.read(other), { version: 0, envelope: null });
      await admin.query('INSERT INTO auth.account_lifecycle(user_id,suspended,updated_at) VALUES($1,true,$2)', [owner, now]);
      assert.equal((await api.read(owner)).version, 1, 'A publishing restriction does not remove access to a private journal');
      const row = (await admin.query('SELECT * FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0];
      const cipher = await storage.get(row.key); assert.ok(!cipher.includes(Buffer.from('Synthetic private journal alpha')));
      const second = snapshot('Synthetic private journal beta', 1, 2); await api.put(owner, second);
      assert.deepEqual(await api.put(owner, first), receipt); assert.deepEqual(await api.read(owner), { version: 2, envelope: second });
      await assert.rejects(api.put(owner, { ...first, records: [] }), { code: 'retry_content_changed' });
      await assert.rejects(api.put(owner, snapshot('Stale')), { code: 'sync_version_changed' });
      now += 300001; assert.equal(await api.sweep(owner), 1); assert.equal((await stat(storage.path(row.key))).size, 0);
      assert.deepEqual(await api.put(owner, first), receipt); assert.equal((await api.read(owner)).version, 2);
    });
    await t.test('interrupted upload survives restart; a different encryption nonce adopts only the identical authenticated plaintext', async () => {
      const owner = await freshOwner(), value = snapshot('Synthetic retry'), keys: string[] = []; let fail = true;
      const adapter: Storage = {
        put: async (key, file) => { keys.push(key); await storage.put(key, file); },
        get: async key => { if (fail) { fail = false; throw Error('Synthetic read outage'); } return storage.get(key); },
        delete: storage.delete.bind(storage),
      };
      await assert.rejects(create(adapter).put(owner, value));
      assert.equal((await admin.query('SELECT version FROM private_sync.accounts WHERE user_id=$1', [owner])).rows[0].version, '0');
      assert.equal((await admin.query('SELECT state FROM private_sync.operations WHERE user_id=$1', [owner])).rows[0].state, 'pending');
      const receipt = await create(adapter).put(owner, value); assert.equal(receipt.version, 1);
      assert.equal(keys.length, 2); assert.equal(keys[0], keys[1]);
      assert.equal((await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].n, '1');
    });
    await t.test('two uploads based on one version commit only one winner and retain the loser until fenced cleanup', async () => {
      const owner = await freshOwner(), ready = latch(), go = latch(); let arrivals = 0;
      const adapter: Storage = { ...storage, get: storage.get.bind(storage), delete: storage.delete.bind(storage), put: async (key, file) => { if (++arrivals === 2) ready.release(); await go.promise; await storage.put(key, file); } };
      const a = snapshot('Concurrent A'), b = snapshot('Concurrent B'), api = create(adapter);
      const running = Promise.allSettled([api.put(owner, a), api.put(owner, b)]);
      await ready.promise; go.release(); const results = await running;
      assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
      const loser = results.find(r => r.status === 'rejected') as PromiseRejectedResult; assert.equal(loser.reason.code, 'sync_version_changed');
      const winner = results[0].status === 'fulfilled' ? a : b, lost = winner === a ? b : a;
      assert.deepEqual(await api.read(owner), { version: 1, envelope: winner }); await assert.rejects(api.put(owner, lost), { code: 'sync_version_changed' });
      now += 300001; assert.equal(await api.sweep(owner), 1); assert.equal((await api.read(owner)).version, 1);
    });
    await t.test('reserved objects count against concurrent quotas; failed deletion does not release their storage charge', async () => {
      const owners = await Promise.all([freshOwner(), freshOwner(), freshOwner()]), values = owners.map(() => snapshot('Equal sized payload'));
      const used = Number((await admin.query('SELECT COALESCE(sum(reserved_bytes),0) AS n FROM private_sync.objects')).rows[0].n);
      const bytes = Buffer.byteLength(stable(values[0])) + 77, caps = { ...limits, globalBytes: used + 2 * bytes };
      const outcomes = await Promise.allSettled(owners.map((owner, i) => create(storage, caps).put(owner, values[i])));
      assert.equal(outcomes.filter(r => r.status === 'fulfilled').length, 2);
      assert.equal((outcomes.find(r => r.status === 'rejected') as PromiseRejectedResult).reason.code, 'private_sync_capacity');
      const owner = await freshOwner(); let broken = true;
      const adapter: Storage = { put: async () => { throw Error('Synthetic upload outage'); }, get: storage.get.bind(storage), delete: async key => { if (broken) throw Error('Synthetic deletion outage'); await storage.delete(key); } };
      await assert.rejects(create(adapter).put(owner, snapshot('Reserved charge')));
      now += 300001; assert.equal(await create(adapter).sweep(owner), 0);
      assert.equal((await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].n, '1');
      broken = false; now += 300001; assert.equal(await create(adapter).sweep(owner), 1);
    });
    await t.test('tampered or substituted encrypted bytes never become a successful read', async () => {
      const owner = await freshOwner(), other = await freshOwner(), api = create();
      await api.put(owner, snapshot('Owner A')); await api.put(other, snapshot('Owner B'));
      const row = (await admin.query('SELECT key FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0];
      const original = await storage.get(row.key), changed = Buffer.from(original); changed[changed.length - 1] ^= 1;
      await writeFile(storage.path(row.key), changed); await assert.rejects(api.read(owner), { code: 'private_snapshot_unavailable' }); await writeFile(storage.path(row.key), original);
      const foreign = (await admin.query('SELECT key FROM private_sync.objects WHERE user_id=$1', [other])).rows[0];
      const adapter: Storage = { put: storage.put.bind(storage), delete: storage.delete.bind(storage), get: async () => storage.get(foreign.key) };
      await assert.rejects(create(adapter).read(owner), { code: 'private_snapshot_unavailable' });
    });
    await t.test('account deletion during a read prevents returning the already downloaded private snapshot', async () => {
      const owner = await freshOwner(); await create().put(owner, snapshot('Read racing deletion'));
      const ready = latch(), go = latch();
      const adapter: Storage = { put: storage.put.bind(storage), delete: storage.delete.bind(storage), get: async key => { ready.release(); await go.promise; return storage.get(key); } };
      const reading = create(adapter).read(owner); const rejected = assert.rejects(reading, { code: 'account_unavailable' });
      await ready.promise; const service = deletion(); await requestDelete(service, owner); go.release(); await rejected;
      now += 300001; await service.sweepDeletion(); await service.deliverNotices(); now += 60001; await service.sweepDeletion();
    });
    await t.test('account deletion fences an upload paused after reservation and prevents commit or resurrection', async () => {
      const owner = await freshOwner(), ready = latch(), go = latch(); let key = '';
      const adapter: Storage = { get: storage.get.bind(storage), delete: storage.delete.bind(storage), put: async (target, file) => { key = target; ready.release(); await go.promise; await storage.put(target, file); } };
      const pending = create(adapter).put(owner, snapshot('Upload racing deletion')); const rejected = assert.rejects(pending);
      await ready.promise; const service = deletion(); await requestDelete(service, owner);
      now += 300001; await service.sweepDeletion(); assert.equal((await stat(storage.path(key))).size, 0);
      go.release(); await rejected; await assert.rejects(storage.get(key));
      assert.equal((await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].n, '0');
      await service.deliverNotices(); now += 60001; await service.sweepDeletion();
    });
    const photoCaps = { ownerBytes: 64 * 1024 ** 2, globalBytes: 128 * 1024 ** 2, dailyBytes: 256 * 1024 ** 2, dailyUploads: 200, ownerOperations: 100 };
    const photos = (adapter: Storage = storage) => new PrivatePhotos(pool, adapter, encryption, photoCaps, () => now);
    const picture = await sharp(randomBytes(1600 * 1000 * 3), { raw: { width: 1600, height: 1000, channels: 3 } }).jpeg({ quality: 95 }).toBuffer();
    const photoInput = () => ({ assetId: randomUUID(), bytes: picture.length, mimeType: 'image/jpeg', sha256: digestForPhoto(picture) });
    const digestForPhoto = (bytes: Buffer) => createHashForPhoto('sha256').update(bytes).digest('hex');
    const uploadPhoto = async (owner: string, input: ReturnType<typeof photoInput>) => {
      const api = photos(); await api.begin(owner, input);
      for (let i = 0; i * PRIVATE_PHOTO_CHUNK_BYTES < picture.length; i++) await api.putPart(owner, input.assetId, i, picture.subarray(i * PRIVATE_PHOTO_CHUNK_BYTES, (i + 1) * PRIVATE_PHOTO_CHUNK_BYTES));
      return api.finish(owner, input.assetId);
    };
    await t.test('resumable encrypted photos pin owned completed assets, preserve good chunks and reject dangling/deleted references', async () => {
      const owner = await freshOwner(), other = await freshOwner(), input = photoInput(), api = photos();
      assert.ok(picture.length > PRIVATE_PHOTO_CHUNK_BYTES);
      await api.begin(owner, input); await assert.rejects(api.statusOf(other, input.assetId), { code: 'private_photo_unavailable' });
      const withPhoto = snapshot('Private photo relationship'); withPhoto.records[0].assetIds = [input.assetId];
      const snapshots = create(storage, photoCaps); await assert.rejects(snapshots.put(owner, withPhoto), { code: 'private_assets_unavailable' });
      let fail = true; const adapter: Storage = { put: storage.put.bind(storage), delete: storage.delete.bind(storage), get: async key => { if (fail) { fail = false; throw Error('Synthetic part response loss'); } return storage.get(key); } };
      const first = picture.subarray(0, PRIVATE_PHOTO_CHUNK_BYTES);
      await assert.rejects(photos(adapter).putPart(owner, input.assetId, 0, first));
      await photos(adapter).putPart(owner, input.assetId, 0, first);
      assert.deepEqual((await photos().statusOf(owner, input.assetId)).receivedParts, [0]);
      const changed = Buffer.from(first); changed[0] ^= 1; await assert.rejects(api.putPart(owner, input.assetId, 0, changed), { code: 'retry_content_changed' });
      await assert.rejects(api.finish(owner, input.assetId), { code: 'private_photo_incomplete' });
      for (let i = 1; i * PRIVATE_PHOTO_CHUNK_BYTES < picture.length; i++) await api.putPart(owner, input.assetId, i, picture.subarray(i * PRIVATE_PHOTO_CHUNK_BYTES, (i + 1) * PRIVATE_PHOTO_CHUNK_BYTES));
      assert.equal((await api.finish(owner, input.assetId)).complete, true);
      assert.deepEqual((await api.read(owner, input.assetId)).bytes, picture);
      assert.equal((await api.begin(owner, input)).complete, true);
      await assert.rejects(api.begin(owner, { ...input, sha256: 'f'.repeat(64) }), { code: 'retry_content_changed' });
      await assert.rejects(api.read(other, input.assetId), { code: 'private_photo_unavailable' });
      await assert.rejects(create(storage, photoCaps).put(other, withPhoto), { code: 'private_assets_unavailable' });
      await snapshots.put(owner, withPhoto); await assert.rejects(api.abandon(owner, input.assetId, snapshots), { code: 'private_asset_in_use' });
      const after = snapshot('Photo deliberately detached', 1, 2); await snapshots.put(owner, after);
      assert.equal((await api.abandon(owner, input.assetId, snapshots)).abandoned, true);
      assert.equal((await api.abandon(owner, input.assetId, snapshots)).abandoned, true);
      now += 300001; assert.ok(await snapshots.sweep(owner) >= 2);
      await assert.rejects(api.read(owner, input.assetId), { code: 'private_photo_unavailable' });
      const dangling = snapshot('Cannot revive abandoned photo', 2, 3); dangling.records[0].assetIds = [input.assetId];
      await assert.rejects(snapshots.put(owner, dangling), { code: 'private_assets_unavailable' });
      assert.equal((await snapshots.read(owner)).version, 2);
    });
    await t.test('expired incomplete chunks resume under fresh object identities after cleanup fences the old keys', async () => {
      const owner = await freshOwner(), bytes = await sharp({ create: { width: 4, height: 4, channels: 3, background: '#abcdef' } }).png().toBuffer();
      const input = { assetId: randomUUID(), bytes: bytes.length, mimeType: 'image/png', sha256: digestForPhoto(bytes) }, api = photos();
      await api.begin(owner, input);
      const broken: Storage = { put: async () => { throw Error('Synthetic interrupted chunk'); }, get: storage.get.bind(storage), delete: storage.delete.bind(storage) };
      await assert.rejects(photos(broken).putPart(owner, input.assetId, 0, bytes));
      const old = (await admin.query('SELECT key FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].key;
      now += 300001; assert.equal(await create(storage, photoCaps).sweep(owner), 1); assert.equal((await stat(storage.path(old))).size, 0);
      assert.deepEqual((await api.statusOf(owner, input.assetId)).receivedParts, []);
      await api.putPart(owner, input.assetId, 0, bytes); assert.equal((await api.finish(owner, input.assetId)).complete, true);
      assert.deepEqual((await api.read(owner, input.assetId)).bytes, bytes);
      const current = (await admin.query('SELECT key FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].key; assert.notEqual(current, old);
    });
    await t.test('invalid images and wrong whole-photo checksums cannot become complete assets', async () => {
      const owner = await freshOwner(), bytes = Buffer.from('Synthetic non-image'), api = photos();
      const input = { assetId: randomUUID(), bytes: bytes.length, mimeType: 'image/jpeg', sha256: digestForPhoto(bytes) };
      await api.begin(owner, input); await api.putPart(owner, input.assetId, 0, bytes);
      await assert.rejects(api.finish(owner, input.assetId), { code: 'invalid_private_photo' });
      await assert.rejects(api.read(owner, input.assetId), { code: 'private_photo_unavailable' });
      const wrong = { ...input, assetId: randomUUID(), sha256: 'f'.repeat(64) }; await api.begin(owner, wrong); await api.putPart(owner, wrong.assetId, 0, bytes);
      await assert.rejects(api.finish(owner, wrong.assetId), { code: 'invalid_private_photo' });
      await assert.rejects(api.putPart(owner, input.assetId, 16, bytes), { code: 'invalid_private_photo' });
    });
    await t.test('deletion during a photo download rejects even fully decrypted bytes and purges owned chunk metadata', async () => {
      const owner = await freshOwner(), input = photoInput(); await uploadPhoto(owner, input);
      const ready = latch(), go = latch(); let seen = 0;
      const adapter: Storage = { put: storage.put.bind(storage), delete: storage.delete.bind(storage), get: async key => { const bytes = await storage.get(key); if (++seen === Math.ceil(picture.length / PRIVATE_PHOTO_CHUNK_BYTES)) { ready.release(); await go.promise; } return bytes; } };
      const reading = photos(adapter).read(owner, input.assetId), rejected = assert.rejects(reading, { code: 'account_unavailable' });
      await ready.promise; const service = deletion(); await requestDelete(service, owner); go.release(); await rejected;
      now += 300001; await service.sweepDeletion();
      assert.equal((await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].n, '0');
      assert.equal((await admin.query('SELECT count(*) AS n FROM private_sync.operations WHERE user_id=$1', [owner])).rows[0].n, '0');
    });
    await t.test('metadata-only paths reject photo references and never infer a library projection', async () => {
      const owner = await freshOwner(), value = snapshot('Unsupported attachment'); value.records[0].assetIds = [randomUUID()];
      await assert.rejects(create().put(owner, value), { code: 'private_assets_unavailable' });
      const strict = new PrivateSnapshots(pool, storage, encryption, () => { throw Error('Synthetic production projection rejection'); }, limits);
      await assert.rejects(strict.put(owner, snapshot('Unprojected input')), /projection rejection/);
      assert.equal((await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner])).rows[0].n, '0');
    });
  } finally {
    await Promise.all([admin.end(), pool.end(), life.end()]); await control.query(`DROP DATABASE ${database}`); await control.end(); await rm(root, { recursive: true, force: true });
  }
});
