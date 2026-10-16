import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash as createHashForPhoto } from 'node:crypto';
import { readFile, writeFile, mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Pool } from 'pg';
import { once } from 'node:events';
import { Authentication } from '../src/auth.ts';
import { createRuntime } from '../src/config.ts';
import type { SignInMail } from '../src/auth-email.ts';
import { makeServer } from '../src/http.ts';
import { Relay } from '../src/relay.ts';
import { MemoryRepository, budgets } from './helpers.ts';
import sharp from 'sharp';
import { PrivatePhotos, PRIVATE_PHOTO_CHUNK_BYTES } from '../src/sync-photos.ts';
import { migrate } from '../src/migrate.ts';
import { FileStorage, type Storage } from '../src/storage.ts';
import { SyncEncryption } from '../src/sync-encryption.ts';
import { PrivateSnapshots, type SyncStorageLimits } from '../src/sync-snapshots.ts';
import { syncEnvelope, type SyncEnvelope } from '../src/sync-envelope.ts';
import { privateLibraryEnvelope, projectPrivateArchive } from '../src/sync-projection.ts';
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
  const authPool = new Pool({ connectionString, max: 5, options: '-c role=pittech_auth_runtime' });
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
    for (const role of ['auth', 'lifecycle', 'private-sync']) await admin.query(await readFile(new URL(`../operations/${role}-runtime-role.sql`, import.meta.url), 'utf8'));
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
    await t.test('strict portable library projection survives encrypted storage and rejects malformed ownership before reservation', async () => {
      const fixture = JSON.parse(await readFile(new URL('../../contracts/fixtures/private-sync-projection-cases-v1.json', import.meta.url), 'utf8'));
      const owner = await freshOwner(), api = new PrivateSnapshots(pool, storage, encryption, privateLibraryEnvelope, limits, () => now);
      const records = fixture.cases[0].rows.filter((r: { recordType: string }) => r.recordType !== 'photos').map((r: object) => ({ ...r, deleted: false, version: { counter: 1, clientId: device } }));
      const value = { contractVersion: 1, baseVersion: 0, mutationId: randomUUID(), clientId: device, records, conflicts: [] };
      await api.put(owner, value); assert.deepEqual((await api.read(owner)).envelope, value);
      const invalid = structuredClone(value); invalid.baseVersion = 1; invalid.mutationId = randomUUID(); invalid.records.find((r: { recordType: string }) => r.recordType === 'events').payload.noteMetadataJson = '{"version":1,"category":"general","token":"synthetic-placeholder"}';
      const before = await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner]);
      await assert.rejects(api.put(owner, invalid), { code: 'invalid_private_library' });
      assert.deepEqual((await admin.query('SELECT count(*) AS n FROM private_sync.objects WHERE user_id=$1', [owner])).rows, before.rows);
    });
    await t.test('authenticated sync HTTP binds ownership, resumes photos, redacts logs and stays absent without explicit services', async () => {
      const owner = await freshOwner(), other = await freshOwner(), sent: SignInMail[] = [];
      const auth = new Authentication(authPool, new AuthSecrets(credential(), credential()), { send: async mail => { sent.push(mail); } }, 100, () => now);
      const signIn = async (id: string) => {
        const verifier = credential(), challenge = await auth.start({ email: id + '@example.com', idempotencyId: randomUUID(), proofChallenge: proofHash(verifier) });
        await auth.deliverPending(); const mail = sent.find(v => v.challengeId === challenge.challengeId); assert.ok(mail);
        return auth.verify({ challengeId: challenge.challengeId, verifier, code: mail.code, idempotencyId: randomUUID() });
      };
      const a = await signIn(owner), b = await signIn(other), log: unknown[] = [];
      const relay = new Relay(new MemoryRepository(), storage, budgets, () => now);
      const server = makeServer(relay, { ipKey: credential(), auth, uploadsEnabled: false, sync: { snapshots: create(storage, photoCaps), photos: photos() }, log: value => log.push(value) });
      const absent = makeServer(relay, { ipKey: credential(), auth, uploadsEnabled: false });
      server.listen(0, '127.0.0.1'); absent.listen(0, '127.0.0.1'); await Promise.all([once(server, 'listening'), once(absent, 'listening')]);
      const origin = 'http://127.0.0.1:' + (server.address() as { port: number }).port, disabled = 'http://127.0.0.1:' + (absent.address() as { port: number }).port;
      const headers = (token = a.accessToken) => ({ Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' });
      const send = (path: string, body: unknown, method = 'POST', token = a.accessToken, key?: string) => fetch(origin + path, { method, headers: { ...headers(token), ...(key ? { 'Idempotency-Key': key } : {}) }, body: JSON.stringify(body) });
      try {
        assert.equal((await fetch(origin + '/v1/sync')).status, 401);
        assert.equal((await fetch(origin + '/v1/sync', { headers: headers(credential()) })).status, 401);
        assert.equal((await fetch(disabled + '/v1/sync', { headers: headers() })).status, 404);
        const empty = await fetch(origin + '/v1/sync', { headers: headers() }); assert.deepEqual(await empty.json(), { version: 0, envelope: null });
        assert.equal(empty.headers.get('cache-control'), 'private, no-store'); assert.equal(empty.headers.get('vary'), 'Authorization'); assert.equal(empty.headers.get('access-control-allow-origin'), null);
        const value = snapshot('Synthetic HTTP private journal');
        assert.equal((await send('/v1/sync', value, 'PUT')).status, 400);
        assert.equal((await send('/v1/sync', { ...value, userId: other }, 'PUT', a.accessToken, value.mutationId)).status, 422);
        const first = await send('/v1/sync', value, 'PUT', a.accessToken, value.mutationId); assert.equal(first.status, 200); const receipt = await first.json();
        assert.deepEqual(await (await send('/v1/sync', value, 'PUT', a.accessToken, value.mutationId)).json(), receipt);
        assert.deepEqual(await (await fetch(origin + '/v1/sync', { headers: headers(b.accessToken) })).json(), { version: 0, envelope: null });
        const stale = snapshot('Stale HTTP update'); assert.equal((await send('/v1/sync', stale, 'PUT', a.accessToken, stale.mutationId)).status, 409);
        const invalidUtf = await fetch(origin + '/v1/sync', { method: 'PUT', headers: headers(), body: new Uint8Array([255]) }); assert.equal(invalidUtf.status, 400);
        const input = photoInput(); assert.equal((await send('/v1/sync/photos', input, 'POST', a.accessToken, input.assetId)).status, 200);
        const photoPath = '/v1/sync/photos/' + input.assetId;
        assert.equal((await send(photoPath + '/complete', {})).status, 409);
        assert.equal((await fetch(origin + photoPath + '/status', { headers: headers(b.accessToken) })).status, 404);
        const tooLarge = await fetch(origin + photoPath + '/parts/0', { method: 'PUT', headers: { Authorization: headers().Authorization, 'Content-Type': 'application/octet-stream' }, body: new Uint8Array(PRIVATE_PHOTO_CHUNK_BYTES + 1) }); assert.equal(tooLarge.status, 413);
        for (let i = 0; i * PRIVATE_PHOTO_CHUNK_BYTES < picture.length; i++) {
          const uploaded = await fetch(origin + photoPath + '/parts/' + i, { method: 'PUT', headers: { Authorization: headers().Authorization, 'Content-Type': 'application/octet-stream' }, body: new Uint8Array(picture.subarray(i * PRIVATE_PHOTO_CHUNK_BYTES, (i + 1) * PRIVATE_PHOTO_CHUNK_BYTES)) }); assert.equal(uploaded.status, 200);
        }
        const complete = await send(photoPath + '/complete', {}); assert.equal(complete.status, 200); assert.equal((await complete.json()).complete, true);
        const download = await fetch(origin + photoPath, { headers: headers() }); assert.equal(download.status, 200); assert.equal(download.headers.get('content-type'), 'image/jpeg'); assert.equal(download.headers.get('x-content-sha256'), input.sha256); assert.deepEqual(Buffer.from(await download.arrayBuffer()), picture);
        assert.equal((await fetch(origin + photoPath, { headers: headers(b.accessToken) })).status, 404);
        assert.equal((await fetch(origin + '/packs/' + input.assetId)).status, 404); assert.equal((await fetch(origin + '/v1/recipes/' + input.assetId)).status, 404);
        const withPhoto = snapshot('Owned HTTP photo', 1, 2); withPhoto.records[0].assetIds = [input.assetId];
        assert.equal((await send('/v1/sync', withPhoto, 'PUT', a.accessToken, withPhoto.mutationId)).status, 200);
        assert.equal((await send(photoPath, {}, 'DELETE')).status, 422); assert.equal((await send(photoPath, { confirm: 'abandon-private-photo' }, 'DELETE')).status, 409);
        const detach = snapshot('Explicit photo detachment', 2, 3); assert.equal((await send('/v1/sync', detach, 'PUT', a.accessToken, detach.mutationId)).status, 200);
        assert.equal((await send(photoPath, { confirm: 'abandon-private-photo' }, 'DELETE')).status, 200);
        assert.equal((await fetch(origin + photoPath, { headers: headers() })).status, 404);
        await auth.logout(a.refreshToken); assert.equal((await fetch(origin + '/v1/sync', { headers: headers() })).status, 401);
        now += 16 * 60000; assert.equal((await fetch(origin + '/v1/sync', { headers: headers(b.accessToken) })).status, 401);
        const logs = JSON.stringify(log); for (const privateValue of [owner, other, input.assetId, a.accessToken, b.accessToken, value.records[0].payload!.title]) assert.ok(!logs.includes(String(privateValue)));
        assert.ok(logs.includes('/v1/sync/photos/:id/parts/:index'));
      } finally { server.closeAllConnections(); absent.closeAllConnections(); await Promise.all([new Promise<void>(r => server.close(() => r())), new Promise<void>(r => absent.close(() => r()))]); }
    });
    await t.test('production createRuntime wiring accepts a real full library and rejects unknown fields over authenticated HTTP', async () => {
      const { default: yauzl } = await import('yauzl');
      const zipBytes = await readFile(new URL('../../ios/PitTechTests/Fixtures/android-guide-meal-v10.zip', import.meta.url));
      const bytes: Buffer = await new Promise<Buffer>((resolve, reject) => {
        yauzl.fromBuffer(zipBytes, { lazyEntries: true }, (error, zip) => {
          if (error || !zip) { reject(error); return; }
          zip.on('error', reject);
          zip.on('end', () => reject(Error('Fixture data missing')));
          zip.on('entry', entry => {
            if (entry.fileName !== 'data/pittech.json') { zip.readEntry(); return; }
            zip.openReadStream(entry, (err, stream) => {
              if (err || !stream) { reject(err); zip.close(); return; }
              void (async () => { const chunks: Buffer[] = []; for await (const b of stream) chunks.push(Buffer.from(b)); zip.close(); resolve(Buffer.concat(chunks)); })().catch(reject);
            });
          });
          zip.readEntry();
        });
      });
      const archive = JSON.parse(bytes.toString('utf8'));
      const assets: Record<string, string> = {};
      for (const type of ['photos', 'recipeAssets', 'companionPhotos']) for (const row of archive[type] ?? []) assets[type + '|' + row.id] = randomUUID();
      const rows = projectPrivateArchive(archive, assets);
      assert.ok(rows.some(r => r.recordType === 'companionRecords'), 'production wiring must prove the newly supported document kinds');
      const value = { contractVersion: 1, baseVersion: 0, mutationId: randomUUID(), clientId: device, records: rows.map(r => ({ ...r, deleted: false, version: { counter: 1, clientId: device } })), conflicts: [] as never[] };
      const vars: Record<string, string> = {
        RATE_HMAC_KEY: credential(), DATABASE_URL: connectionString, STORAGE_MAX_BYTES: String(1024 ** 3), DAILY_UPLOAD_MAX_BYTES: String(1024 ** 3),
        DAILY_UPLOAD_MAX_COUNT: '1000', OBJECT_STORAGE: 'filesystem', OBJECT_DIRECTORY: root, NODE_ENV: 'test', PORT: '3000',
        AUTH_ENABLED: 'true', AUTH_HASH_KEY: credential(), AUTH_ENCRYPTION_KEY: credential(), RESEND_API_KEY: 're_synthetic_test_key',
        AUTH_FROM: 'signin@auth.pittech.app', AUTH_PUBLIC_ORIGIN: 'https://pittech.app', AUTH_DAILY_EMAIL_LIMIT: '100',
        PRIVATE_SYNC_ENABLED: 'true', PRIVATE_SYNC_DATABASE_URL: connectionString,
        PRIVATE_SYNC_CURRENT_KEY_ID: 'test-key-1', PRIVATE_SYNC_ENCRYPTION_KEYS: JSON.stringify({ 'test-key-1': credential(), 'test-key-2': credential() }),
      };
      const saved = new Map<string, string | undefined>();
      for (const [key, val] of Object.entries(vars)) { saved.set(key, process.env[key]); process.env[key] = val; }
      let runtime: ReturnType<typeof createRuntime> | undefined;
      try {
        runtime = createRuntime();
        const sync = runtime.sync; assert.ok(sync);
        const owner = await freshOwner(), sent: SignInMail[] = [];
        const auth = new Authentication(authPool, new AuthSecrets(credential(), credential()), { send: async mail => { sent.push(mail); } }, 100, () => now);
        const verifier = credential(), challenge = await auth.start({ email: owner + '@example.com', idempotencyId: randomUUID(), proofChallenge: proofHash(verifier) });
        await auth.deliverPending();
        const mail = sent.find(v => v.challengeId === challenge.challengeId); assert.ok(mail);
        const session = await auth.verify({ challengeId: challenge.challengeId, verifier, code: mail.code, idempotencyId: randomUUID() });
        const relay = new Relay(new MemoryRepository(), storage, budgets, () => now);
        const server = makeServer(relay, { ipKey: credential(), auth, uploadsEnabled: false, sync });
        server.listen(0, '127.0.0.1'); await once(server, 'listening');
        try {
          const origin = 'http://127.0.0.1:' + (server.address() as { port: number }).port;
          const headers = { Authorization: 'Bearer ' + session.accessToken, 'Content-Type': 'application/json' };
          const put = (body: unknown, key: string) => fetch(origin + '/v1/sync', { method: 'PUT', headers: { ...headers, 'Idempotency-Key': key }, body: JSON.stringify(body) });
          const first = await put(value, value.mutationId);
          assert.equal(first.status, 200);
          assert.equal((await first.json() as { version: number }).version, 1);
          const read = await fetch(origin + '/v1/sync', { headers });
          assert.equal(read.status, 200);
          assert.deepEqual(await read.json(), { version: 1, envelope: value });
          const tampered = structuredClone(value);
          tampered.baseVersion = 1; tampered.mutationId = randomUUID();
          const companion = tampered.records.find(r => r.recordType === 'companionRecords'); assert.ok(companion);
          (companion.payload.payload as Record<string, unknown>).injectedField = true;
          assert.equal((await put(tampered, tampered.mutationId)).status, 422);
          const unknown = structuredClone(value);
          unknown.baseVersion = 1; unknown.mutationId = randomUUID();
          const victim = unknown.records.find(r => r.recordType === 'companionRecords'); assert.ok(victim);
          victim.payload.kind = 'future-kind';
          assert.equal((await put(unknown, unknown.mutationId)).status, 422);
          assert.equal((await (await fetch(origin + '/v1/sync', { headers })).json() as { version: number }).version, 1);
        } finally { server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); }
      } finally {
        await runtime?.close();
        for (const [key, val] of saved) { if (val === undefined) delete process.env[key]; else process.env[key] = val; }
      }
    });
  } finally {
    await Promise.all([admin.end(), pool.end(), life.end(), authPool.end()]); await control.query(`DROP DATABASE ${database}`); await control.end(); await rm(root, { recursive: true, force: true });
  }
});
